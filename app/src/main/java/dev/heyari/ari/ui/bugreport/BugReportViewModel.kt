package dev.heyari.ari.ui.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.bugreport.AttachmentKind
import dev.heyari.ari.bugreport.AttachmentOffer
import dev.heyari.ari.bugreport.BugAttachment
import dev.heyari.ari.bugreport.BugReport
import dev.heyari.ari.bugreport.BugReportClient
import dev.heyari.ari.bugreport.BugReportCollector
import dev.heyari.ari.bugreport.BugReportHandoff
import dev.heyari.ari.bugreport.FiledReport
import dev.heyari.ari.bugreport.FiledReportRecord
import dev.heyari.ari.bugreport.SendOutcome
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Why a send did not land. Two types rather than one string because they need
 * two different things from the reporter: a correction, or another go.
 */
sealed interface SendError {
    /** The server refused the report. Sending it again unchanged achieves nothing. */
    data class Rejected(val reason: String) : SendError

    /** No network, a timeout, a 5xx. Worth retrying as-is. */
    data object Unreachable : SendError
}

/** Where the reporter is in the flow. Each step shows a different screen. */
enum class BugReportStep { EDITING, STAGING, REVIEWING, SENDING, SENT }

/**
 * What is about to be sent, exactly as the review screen renders it.
 *
 * [consented] gates the diagnostic files and nothing else. A report with the
 * box unticked still goes, carrying the description and the device block,
 * because making consent mandatory in practice would be a dark pattern
 * wearing a checkbox.
 */
data class BugReportUiState(
    val step: BugReportStep = BugReportStep.EDITING,
    val description: String = "",
    val privateNote: String = "",
    val consented: Boolean = false,
    val offers: List<AttachmentOffer> = emptyList(),
    val selected: Set<AttachmentKind> = emptySet(),
    /**
     * The files as they will actually be uploaded, written before the review
     * screen renders. Sizes come from disk rather than from an estimate,
     * because a review screen quoting a number it made up is worse than one
     * that quotes nothing.
     */
    val staged: List<BugAttachment> = emptyList(),
    val stackTrace: String? = null,
    val filed: FiledReport? = null,
    val error: SendError? = null,
) {
    val canSend: Boolean get() = description.isNotBlank() && step != BugReportStep.SENDING

    /** Nothing is sent from this list unless consent was given. */
    val sending: Set<AttachmentKind>
        get() = if (consented) selected else emptySet()

    val stagedBytes: Long get() = staged.sumOf { it.bytes }
}

@HiltViewModel
class BugReportViewModel @Inject constructor(
    private val collector: BugReportCollector,
    private val client: BugReportClient,
    private val settings: SettingsRepository,
    private val handoff: BugReportHandoff,
) : ViewModel() {

    private val _state = MutableStateFlow(BugReportUiState())
    val state: StateFlow<BugReportUiState> = _state.asStateFlow()

    private var screenshot: ByteArray? = null

    /**
     * Picks up the screenshot taken as the button was tapped, and the crash
     * trace if this report was started from the crash prompt. Both are null
     * for an ordinary report opened from a screen with nothing to capture.
     */
    fun start() {
        val taken = handoff.take()
        screenshot = taken.screenshot
        viewModelScope.launch {
            val offers = collector.offers(hasScreenshot = taken.screenshot != null)
            _state.update {
                it.copy(
                    offers = offers,
                    selected = offers.filter { offer -> offer.defaultOn }.map { o -> o.kind }.toSet(),
                    stackTrace = taken.crashTrace,
                )
            }
        }
    }

    fun setDescription(text: String) = _state.update { it.copy(description = text) }

    fun setPrivateNote(text: String) = _state.update { it.copy(privateNote = text) }

    fun setConsent(given: Boolean) = _state.update { it.copy(consented = given) }

    /**
     * "All recordings" is the union of the two specific audio kinds, so ticking
     * it unticks them and vice versa. Sending both would upload every clip
     * twice and charge the tester's daily budget for the privilege.
     */
    fun toggle(kind: AttachmentKind) = _state.update { state ->
        if (kind in state.selected) {
            state.copy(selected = state.selected - kind)
        } else {
            val cleared = when (kind) {
                AttachmentKind.ALL_AUDIO ->
                    state.selected - AttachmentKind.COMMANDS - AttachmentKind.WAKE_AUDIO
                AttachmentKind.COMMANDS, AttachmentKind.WAKE_AUDIO ->
                    state.selected - AttachmentKind.ALL_AUDIO
                else -> state.selected
            }
            state.copy(selected = cleared + kind)
        }
    }

    /**
     * Writes the chosen files out, then shows them. Staging here rather than at
     * send time is what lets the review screen quote real sizes — and it means
     * a kind that produced nothing is gone from the list before anyone is asked
     * to approve it.
     */
    fun review() {
        val current = _state.value
        _state.update { it.copy(step = BugReportStep.STAGING, error = null) }
        viewModelScope.launch {
            val staged = collector.stage(current.sending, screenshot)
            _state.update { it.copy(step = BugReportStep.REVIEWING, staged = staged) }
        }
    }

    fun backToEditing() = _state.update { it.copy(step = BugReportStep.EDITING) }

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        _state.update { it.copy(step = BugReportStep.SENDING, error = null) }

        viewModelScope.launch {
            val report = BugReport(
                installId = settings.installId(),
                description = current.description.trim(),
                privateNote = current.privateNote.trim().takeIf { it.isNotEmpty() },
                stackTrace = current.stackTrace,
                app = collector.appInfo(),
                setup = collector.setupInfo(),
                device = collector.deviceInfo(),
                skills = collector.installedSkills(),
                attachments = current.staged,
            )

            when (val outcome = client.send(report)) {
                is SendOutcome.Filed -> {
                    collector.clearStaging()
                    // Written before the screen changes: the delete token
                    // exists nowhere else, and losing it here would leave a
                    // report the reporter can never withdraw.
                    settings.addFiledReport(
                        FiledReportRecord(
                            reportId = outcome.report.reportId,
                            deleteToken = outcome.report.deleteToken,
                            issueNumber = outcome.report.issueNumber,
                            issueUrl = outcome.report.issueUrl,
                            title = current.description.trim().lineSequence().first().take(80),
                            filedAtMillis = System.currentTimeMillis(),
                        )
                    )
                    _state.update { it.copy(step = BugReportStep.SENT, filed = outcome.report) }
                }
                is SendOutcome.Rejected -> _state.update {
                    it.copy(step = BugReportStep.REVIEWING, error = SendError.Rejected(outcome.reason))
                }
                is SendOutcome.Failed -> _state.update {
                    it.copy(step = BugReportStep.REVIEWING, error = SendError.Unreachable)
                }
            }
        }
    }
}

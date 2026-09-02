package dev.heyari.ari.ui.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.bugreport.AttachmentKind
import dev.heyari.ari.bugreport.AttachmentOffer
import dev.heyari.ari.bugreport.BugReport
import dev.heyari.ari.bugreport.BugReportClient
import dev.heyari.ari.bugreport.BugReportCollector
import dev.heyari.ari.bugreport.FiledReport
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
enum class BugReportStep { EDITING, REVIEWING, SENDING, SENT }

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
    val stackTrace: String? = null,
    val filed: FiledReport? = null,
    val error: SendError? = null,
) {
    val canSend: Boolean get() = description.isNotBlank() && step != BugReportStep.SENDING

    /** Nothing is sent from this list unless consent was given. */
    val sending: Set<AttachmentKind>
        get() = if (consented) selected else emptySet()

    val sendingBytes: Long
        get() = offers.filter { it.kind in sending }.sumOf { it.bytes }
}

@HiltViewModel
class BugReportViewModel @Inject constructor(
    private val collector: BugReportCollector,
    private val client: BugReportClient,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BugReportUiState())
    val state: StateFlow<BugReportUiState> = _state.asStateFlow()

    private var screenshot: ByteArray? = null

    /**
     * [crashTrace] arrives from the crash prompt on the next launch after an
     * uncaught exception; it is null for a report the user started themselves.
     */
    fun start(crashTrace: String?, screenshotPng: ByteArray?) {
        screenshot = screenshotPng
        viewModelScope.launch {
            val offers = collector.offers(hasScreenshot = screenshotPng != null)
            _state.update {
                it.copy(
                    offers = offers,
                    selected = offers.filter { offer -> offer.defaultOn }.map { o -> o.kind }.toSet(),
                    stackTrace = crashTrace,
                )
            }
        }
    }

    fun setDescription(text: String) = _state.update { it.copy(description = text) }

    fun setPrivateNote(text: String) = _state.update { it.copy(privateNote = text) }

    fun setConsent(given: Boolean) = _state.update { it.copy(consented = given) }

    fun toggle(kind: AttachmentKind) = _state.update {
        it.copy(selected = if (kind in it.selected) it.selected - kind else it.selected + kind)
    }

    fun review() = _state.update { it.copy(step = BugReportStep.REVIEWING, error = null) }

    fun backToEditing() = _state.update { it.copy(step = BugReportStep.EDITING) }

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        _state.update { it.copy(step = BugReportStep.SENDING, error = null) }

        viewModelScope.launch {
            val attachments = collector.stage(current.sending, screenshot)
            val report = BugReport(
                installId = settings.installId(),
                description = current.description.trim(),
                privateNote = current.privateNote.trim().takeIf { it.isNotEmpty() },
                stackTrace = current.stackTrace,
                app = collector.appInfo(),
                setup = collector.setupInfo(),
                device = collector.deviceInfo(),
                skills = collector.installedSkills(),
                attachments = attachments,
            )

            when (val outcome = client.send(report)) {
                is SendOutcome.Filed -> {
                    collector.clearStaging()
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

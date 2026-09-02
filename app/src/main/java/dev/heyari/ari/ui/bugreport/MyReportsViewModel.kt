package dev.heyari.ari.ui.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.bugreport.BugReportClient
import dev.heyari.ari.bugreport.FiledReportRecord
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyReportsUiState(
    val reports: List<FiledReportRecord> = emptyList(),
    val withdrawing: String? = null,
    val confirming: FiledReportRecord? = null,
    val failed: Boolean = false,
)

@HiltViewModel
class MyReportsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val client: BugReportClient,
) : ViewModel() {

    private val _state = MutableStateFlow(MyReportsUiState())
    val state: StateFlow<MyReportsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.filedReports.collect { reports ->
                _state.update { it.copy(reports = reports) }
            }
        }
    }

    fun confirm(report: FiledReportRecord) = _state.update { it.copy(confirming = report, failed = false) }

    fun dismiss() = _state.update { it.copy(confirming = null) }

    /**
     * Withdraws a report: the server erases the files it holds and redacts the
     * public issue.
     *
     * An expired report is dropped from the list without asking the server —
     * its files are already gone by then, and the server has forgotten the id,
     * so a call would only ever be answered "no such report".
     */
    fun withdraw(report: FiledReportRecord) {
        _state.update { it.copy(confirming = null, withdrawing = report.reportId, failed = false) }
        viewModelScope.launch {
            val gone = if (report.isExpired(System.currentTimeMillis())) {
                true
            } else {
                client.withdraw(report.reportId, report.deleteToken)
            }
            if (gone) {
                settings.removeFiledReport(report.reportId)
                _state.update { it.copy(withdrawing = null) }
            } else {
                // The row stays. Removing it locally would strand a report the
                // server still holds, with the only token that could withdraw
                // it thrown away.
                _state.update { it.copy(withdrawing = null, failed = true) }
            }
        }
    }
}

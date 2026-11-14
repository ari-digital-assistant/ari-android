package dev.heyari.ari.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The debug screen's device facts, gathered once per visit. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    collector: DiagnosticsCollector,
) : ViewModel() {
    private val _facts = MutableStateFlow<DeviceDiagnostics?>(null)
    val facts: StateFlow<DeviceDiagnostics?> = _facts.asStateFlow()

    init {
        viewModelScope.launch { _facts.value = collector.collect() }
    }
}

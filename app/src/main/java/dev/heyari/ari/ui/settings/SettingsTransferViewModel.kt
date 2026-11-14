package dev.heyari.ari.ui.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.R
import dev.heyari.ari.data.ImportOutcome
import dev.heyari.ari.data.SettingsTransfer
import dev.heyari.ari.data.restartAri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

private const val TAG = "SettingsTransferVM"

/** An export is a few kilobytes. Anything past this is the wrong file, not a big export. */
private const val MAX_IMPORT_BYTES = 1_000_000

sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data class Finished(val outcome: ImportOutcome) : ImportState
    data object Unreadable : ImportState
}

@HiltViewModel
class SettingsTransferViewModel @Inject constructor(
    private val application: Application,
    private val transfer: SettingsTransfer,
) : ViewModel() {

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** A string resource to toast once, then [consumeExportMessage]. */
    private val _exportMessage = MutableStateFlow<Int?>(null)
    val exportMessage: StateFlow<Int?> = _exportMessage.asStateFlow()

    fun export(uri: Uri) {
        viewModelScope.launch {
            val written = try {
                val json = transfer.export()
                withContext(Dispatchers.IO) {
                    application.contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(json.toByteArray()) } != null
                }
            } catch (e: IOException) {
                Log.w(TAG, "could not write the export", e)
                false
            }
            _exportMessage.value =
                if (written) R.string.settings_backup_exported else R.string.settings_backup_export_failed
        }
    }

    fun consumeExportMessage() {
        _exportMessage.value = null
    }

    fun import(uri: Uri) {
        _importState.value = ImportState.Importing
        viewModelScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    application.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_IMPORT_BYTES) }
                        ?: throw IOException("no stream for $uri")
                }
            } catch (e: IOException) {
                Log.w(TAG, "could not read the import", e)
                _importState.value = ImportState.Unreadable
                return@launch
            }
            val outcome = if (bytes == null) ImportOutcome.NotAnExport else transfer.import(bytes.decodeToString())
            _importState.value = ImportState.Finished(outcome)
        }
    }

    fun dismissImport() {
        _importState.value = ImportState.Idle
    }

    fun restart() = restartAri(application)
}

/** The whole stream, or null when it runs past [limit] bytes. */
private fun InputStream.readAtMost(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        out.write(buffer, 0, n)
        if (out.size() > limit) return null
    }
}

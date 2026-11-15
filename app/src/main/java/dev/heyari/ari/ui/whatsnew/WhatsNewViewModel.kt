package dev.heyari.ari.ui.whatsnew

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Decides, once per launch of the home screen, whether to show [WhatsNewDialog]. */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _show = MutableStateFlow(false)
    val show: StateFlow<Boolean> = _show.asStateFlow()

    init {
        viewModelScope.launch {
            val seen = settingsRepository.whatsNewSeenVersion.first()
            val versionCode = BuildConfig.VERSION_CODE.toLong()
            if (seen >= versionCode) return@launch
            if (shouldShowWhatsNew(seen, versionCode, BuildConfig.VERSION_NAME, WhatsNew.VERSION, wasUpdated())) {
                _show.value = true
            } else {
                // A fresh install, or a release without notes: nothing to show,
                // now or on any later launch of this version.
                settingsRepository.setWhatsNewSeenVersion(versionCode)
            }
        }
    }

    fun dismiss() {
        _show.value = false
        viewModelScope.launch {
            settingsRepository.setWhatsNewSeenVersion(BuildConfig.VERSION_CODE.toLong())
        }
    }

    /** True when this install replaced an older one rather than arriving fresh. */
    private fun wasUpdated(): Boolean {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.lastUpdateTime > info.firstInstallTime
    }
}

package dev.heyari.ari.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.MainActivity
import dev.heyari.ari.R
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Fires on BOOT_COMPLETED (after first user unlock — Direct Boot mode blocks
 * earlier, and that's fine: we need DataStore-encrypted prefs anyway). If the
 * user has opted into "start listening on boot" in Settings, we bring the
 * wake word FGS up straight away. If a required permission is missing, we
 * post a one-off notification deep-linking into the app so they can fix it
 * without having to remember the service exists.
 *
 * Starting a `FOREGROUND_SERVICE_TYPE_MICROPHONE` service from the background
 * is normally blocked on Android 14+, but apps holding `SYSTEM_ALERT_WINDOW`
 * are on the exemption list — and Ari already holds SAW for the lock-screen
 * BAL path (see [WakeWordService.onWakeWordDetected]). Free win.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val enabled = runBlocking { settingsRepository.startOnBoot.first() }
        if (!enabled) {
            Log.i(TAG, "Boot received but start-on-boot is disabled — ignoring")
            return
        }

        val missing = missingPrerequisites(context)
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Boot received with start-on-boot enabled but prerequisites missing: $missing")
            postFixUpNotification(context, missing)
            return
        }

        Log.i(TAG, "Boot received — auto-starting wake word service")
        val serviceIntent = Intent(context, WakeWordService::class.java)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start WakeWordService on boot", t)
            postFixUpNotification(context, listOf("Service could not start"))
        }
    }

    private fun missingPrerequisites(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += "Microphone"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missing += "Notifications"
            }
        }
        if (!Settings.canDrawOverlays(context)) {
            missing += "Lock screen wake word"
        }
        return missing
    }

    private fun postFixUpNotification(context: Context, missing: List<String>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            CHANNEL_BOOT,
            "Start on boot",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shown when Ari couldn't start listening after your device booted"
        }
        nm.createNotificationChannel(channel)

        val openApp = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, REQUEST_OPEN_APP, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val missingText = missing.joinToString(", ")
        val notification = Notification.Builder(context, CHANNEL_BOOT)
            .setContentTitle("Ari couldn't start listening")
            .setContentText("Tap to fix: $missingText")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Ari is set to start listening when your device boots, but these need attention: $missingText. Tap to open Ari."
                )
            )
            .setSmallIcon(R.drawable.ic_ari_symbolic)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(BOOT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL_BOOT = "start_on_boot"
        private const val BOOT_NOTIFICATION_ID = 3
        private const val REQUEST_OPEN_APP = 10
    }
}

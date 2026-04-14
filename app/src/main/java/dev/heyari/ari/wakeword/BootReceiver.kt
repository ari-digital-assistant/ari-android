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
 * user has opted into "start listening on boot" in Settings, we try to bring
 * the wake word FGS up.
 *
 * Android 14+ (API 34) blocks mic-typed foreground services from starting in
 * background contexts like BOOT_COMPLETED — holding `SYSTEM_ALERT_WINDOW`,
 * being the default Assistant role holder, and other "exemptions" do NOT apply
 * to mic/camera FGS types. Verified on stock-ish Android 16 (GrapheneOS) with
 * Ari set as the default assistant: still blocked. On API 34+ we skip the
 * direct attempt and post a high-priority "Tap to start listening"
 * notification whose tap routes through [StartListeningActivity] — an
 * activity context being the only reliable foreground source for the FGS
 * start. On API < 34 the direct start works and we take that path.
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

        if (Build.VERSION.SDK_INT >= 34) {
            Log.i(TAG, "Boot received — API 34+, posting tap-to-start notification")
            postTapToStartNotification(context)
            return
        }

        Log.i(TAG, "Boot received — auto-starting wake word service")
        val serviceIntent = Intent(context, WakeWordService::class.java)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start WakeWordService on boot", t)
            postTapToStartNotification(context)
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
        createBootChannel(nm)

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
        private const val REQUEST_OPEN_APP = 10
    }
}

internal const val CHANNEL_BOOT = "start_on_boot"
internal const val BOOT_NOTIFICATION_ID = 3
private const val REQUEST_TAP_TO_START = 11

internal fun createBootChannel(nm: NotificationManager) {
    val channel = NotificationChannel(
        CHANNEL_BOOT,
        "Start on boot",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Shown when Ari needs your tap to start listening after boot"
    }
    nm.createNotificationChannel(channel)
}

/**
 * Posted when we can't silently bring the wake word FGS up on boot (API 34+
 * blocks mic FGS from background contexts, or the direct start failed). Tap
 * routes through [StartListeningActivity], whose activity-context start is
 * the one path the platform accepts for mic FGS when the app isn't visible.
 */
internal fun postTapToStartNotification(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    createBootChannel(nm)

    val startIntent = Intent(context, StartListeningActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val pi = PendingIntent.getActivity(
        context, REQUEST_TAP_TO_START, startIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = Notification.Builder(context, CHANNEL_BOOT)
        .setContentTitle("Ari")
        .setContentText("Tap to start listening")
        .setSmallIcon(R.drawable.ic_ari_symbolic)
        .setContentIntent(pi)
        .setAutoCancel(true)
        .build()

    nm.notify(BOOT_NOTIFICATION_ID, notification)
}

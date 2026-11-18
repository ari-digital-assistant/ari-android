package dev.heyari.ari.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.heyari.ari.bugreport.ReportReplyWorker

/**
 * Debug-only: runs the bug-report reply check now instead of at its next
 * 12-hour slot. WorkManager won't run periodic work early, even when the
 * job scheduler forces its job, so testing a reply notification otherwise
 * means waiting half a day.
 *
 *     adb shell am broadcast -n dev.heyari.ari/.debug.CheckRepliesReceiver
 */
class CheckRepliesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<ReportReplyWorker>().build())
    }
}

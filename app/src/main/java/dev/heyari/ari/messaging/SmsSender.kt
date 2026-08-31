package dev.heyari.ari.messaging

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.assistant.AssistantRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends an SMS outright, with nobody tapping anything.
 *
 * The only true send Ari has on Android — every other messenger reserves
 * sending for itself. That makes this the one path where a wrong recipient
 * cannot be caught by the user looking at a compose box, which is why the
 * skill reads the message back and asks first unless told not to.
 *
 * Refusal is not failure. When Ari can't send outright this reports why and
 * the caller opens the messaging app with the recipient and text filled in
 * instead — the message still gets there, it just costs a tap.
 *
 * Two things have to be true to send. Ari must hold [Manifest.permission.SEND_SMS],
 * and it must currently be the device's default assistant: that role is the
 * grounds on which Play permits an app like this to hold the permission at all,
 * and the policy requires we stop the moment the user picks another assistant.
 * The role is therefore checked on every send rather than trusted from install
 * time, and it is checked *first* — without it the permission must not be used
 * even where an earlier grant is technically still in place.
 */
@Singleton
class SmsSender @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assistantRole: AssistantRole,
) {
    sealed interface Result {
        data object Sent : Result
        data object NoPermission : Result

        /** Ari isn't the default assistant, so the permission may not be used. */
        data object NotDefaultAssistant : Result

        /** Handed to the network and refused by it. Distinct from [Failed],
         *  which means the send was never attempted. */
        data class NotSent(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun send(destination: String, text: String): Result {
        if (destination.isBlank() || text.isBlank()) return Result.Failed("nothing to send")
        if (!assistantRole.isDefaultAssistant()) return Result.NotDefaultAssistant
        if (!hasPermission()) return Result.NoPermission
        // getSystemService, not the API 31-deprecated SmsManager.getDefault().
        val sms = context.getSystemService(SmsManager::class.java)
            ?: return Result.Failed("no SMS subscription")
        return try {
            // Anything over one segment must go multipart, or the tail is
            // silently dropped — and a truncated message is worse than none,
            // because the sender believes it went.
            val parts = sms.divideMessage(text)
            val outcome = SendOutcome(parts.size)
            outcome.register()
            try {
                if (parts.size > 1) {
                    sms.sendMultipartTextMessage(
                        destination, null, parts, ArrayList(outcome.pendingIntents), null,
                    )
                } else {
                    sms.sendTextMessage(
                        destination, null, text, outcome.pendingIntents.first(), null,
                    )
                }
                outcome.await()
            } finally {
                outcome.unregister()
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "SMS refused for $destination", e)
            Result.Failed("invalid destination")
        } catch (e: SecurityException) {
            // Revoked between the check and the call.
            Log.w(TAG, "SMS permission revoked mid-send", e)
            Result.NoPermission
        }
    }

    /**
     * One send's worth of sent-status plumbing.
     *
     * `sendTextMessage` returns void: the only channel it has for reporting
     * failure is the PendingIntent it broadcasts a result code to. Without one,
     * "sent" meant nothing more than "the call didn't throw" — a text to a
     * number that couldn't receive it was reported as delivered, which is how a
     * message to a landline came back as a success.
     *
     * Multipart broadcasts once per segment, so the latch counts them all and
     * the worst code wins: a message whose tail was refused has not been sent.
     */
    private inner class SendOutcome(parts: Int) {
        private val action = "$SENT_ACTION.${nextSendId.incrementAndGet()}"
        private val latch = CountDownLatch(parts)
        private val worst = AtomicInteger(Activity.RESULT_OK)

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (resultCode != Activity.RESULT_OK) worst.set(resultCode)
                latch.countDown()
            }
        }

        // A distinct request code per segment, or they collapse into one
        // PendingIntent and the latch never reaches zero.
        val pendingIntents: List<PendingIntent> = (0 until parts).map { i ->
            PendingIntent.getBroadcast(
                context,
                i,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun register() {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        fun unregister() {
            runCatching { context.unregisterReceiver(receiver) }
        }

        fun await(): Result {
            val settled = latch.await(SENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!settled) {
                Log.w(TAG, "no sent status within ${SENT_TIMEOUT_SECONDS}s — assuming it went")
            }
            return outcomeOf(settled, worst.get()).also {
                if (it is Result.NotSent) Log.w(TAG, "network refused the message: ${it.reason}")
            }
        }
    }

    companion object {
        private const val TAG = "SmsSender"
        private const val SENT_ACTION = "dev.heyari.ari.SMS_SENT"
        private const val SENT_TIMEOUT_SECONDS = 5L
        private val nextSendId = AtomicLong()

        /**
         * What a send amounts to, given whether every segment reported back and
         * the worst code among those that did.
         *
         * Timing out is deliberately not a failure. The modem has the message
         * and it may well arrive, so calling it failed would send the user off
         * to compose a duplicate. Only an explicit error code counts against a
         * send — the wait is bounded because this blocks the turn, not because
         * the deadline means anything.
         */
        internal fun outcomeOf(settled: Boolean, worstCode: Int): Result = when {
            !settled -> Result.Sent
            worstCode == Activity.RESULT_OK -> Result.Sent
            else -> Result.NotSent(reasonFor(worstCode))
        }

        private fun reasonFor(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null pdu"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit exceeded"
            else -> "error $code"
        }
    }
}

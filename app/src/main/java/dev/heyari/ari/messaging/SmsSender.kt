package dev.heyari.ari.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Refusal is not failure. Without [Manifest.permission.SEND_SMS] this reports
 * [Result.NoPermission] and the caller opens the messaging app with the text
 * filled in instead — the message still gets there, it just costs a tap.
 */
@Singleton
class SmsSender @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    sealed interface Result {
        data object Sent : Result
        data object NoPermission : Result
        data class Failed(val reason: String) : Result
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun send(destination: String, text: String): Result {
        if (destination.isBlank() || text.isBlank()) return Result.Failed("nothing to send")
        if (!hasPermission()) return Result.NoPermission
        // getSystemService, not the API 31-deprecated SmsManager.getDefault().
        val sms = context.getSystemService(SmsManager::class.java)
            ?: return Result.Failed("no SMS subscription")
        return try {
            // Anything over one segment must go multipart, or the tail is
            // silently dropped — and a truncated message is worse than none,
            // because the sender believes it went.
            val parts = sms.divideMessage(text)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(destination, null, parts, null, null)
            } else {
                sms.sendTextMessage(destination, null, text, null, null)
            }
            Result.Sent
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "SMS refused for $destination", e)
            Result.Failed("invalid destination")
        } catch (e: SecurityException) {
            // Revoked between the check and the call.
            Log.w(TAG, "SMS permission revoked mid-send", e)
            Result.NoPermission
        }
    }

    private companion object {
        const val TAG = "SmsSender"
    }
}

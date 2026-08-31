package dev.heyari.ari.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.assistant.AssistantRole
import dev.heyari.ari.messaging.MessagingService
import dev.heyari.ari.messaging.MessagingServices
import dev.heyari.ari.messaging.SmsSender
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Honours the engine's `message` action by handing the text to a messaging app
 * with the body already filled in.
 *
 * Nothing here sends. Android offers no way to send on the user's behalf
 * through a third-party messaging app, so every path ends with the message
 * sitting in a compose surface waiting for a tap. That tap is also the user's
 * confirmation, which is why the skill doesn't ask before this — see
 * `2026-08-18-send-message-skill-design.md`.
 *
 * Two outcomes, and the difference matters to what Ari says next:
 *
 *  - **Targeted** — we know the service, it's installed, so the intent is
 *    scoped to that package and the user lands in that app's own picker.
 *  - **Chooser** — the service is unknown to us or the skill named none, so
 *    the system chooser runs and the user picks the app too.
 *
 * The chooser fallback is deliberate. A registry of known services will always
 * be missing whatever is popular where the user lives — LINE, Viber, Zalo,
 * KakaoTalk — and an unknown service should cost the user one extra tap, not
 * the whole feature.
 */
@Singleton
class MessageLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val services: MessagingServices,
    private val smsSender: SmsSender,
    private val assistantRole: AssistantRole,
) {
    sealed interface SendResult {
        /** Gone. Nobody tapped anything. */
        data class Sent(val serviceName: String) : SendResult

        /** The recipient's own chat, message typed. One tap and it's gone. */
        data class ReadyToSend(val serviceName: String) : SendResult

        /** Scoped to a named app; the user picks the recipient and taps send. */
        data class Prepared(val serviceName: String) : SendResult

        /** System chooser; the user picks the app as well as the recipient. */
        data object PreparedInChooser : SendResult

        /** The skill named a service we know, but it isn't on the device. */
        data class ServiceNotInstalled(val serviceName: String) : SendResult

        data class Failed(val reason: String) : SendResult
    }

    /** Where a message is headed, decided before any Android call is made. */
    sealed interface Plan {
        /** Chat targeted and body prefilled, via the service's own URI scheme.
         *  [pkg] is null for scheme-addressed services like `mailto:`, where
         *  the message goes to whichever client the user set as default. */
        data class Templated(
            val uris: List<String>,
            val pkg: String?,
            val action: MessagingService.IntentAction,
            val displayName: String,
        ) : Plan

        data class Targeted(val pkg: String, val displayName: String) : Plan
        data class NotInstalled(val displayName: String) : Plan
        data object Chooser : Plan
    }

    fun send(action: MessageAction): SendResult {
        if (action.text.isBlank()) return SendResult.Failed("empty message")

        // A true send, if the skill asked for one and we can honour it. When
        // we can't — no permission, no number — we fall through to the compose
        // paths below rather than failing. `delivery` is a request, not an
        // instruction, and the frontend reports what actually happened.
        trueSend(action)?.let { return it }

        val encoded = Uri.encode(action.text)
        val plan = plan(action.service, services.all(), action.recipientId, encoded, ::isInstalled)
        return when (plan) {
            is Plan.Templated -> {
                // Try each URI, then fall through to the share intent. A
                // template that doesn't resolve costs the user a tap, not the
                // message — losing what they dictated would be the worse
                // failure by a distance.
                val opened = plan.uris.any { startUri(it, plan.pkg, plan.action) }
                when {
                    opened -> SendResult.ReadyToSend(plan.displayName)
                    plan.pkg != null && startShare(action.text, plan.pkg) ->
                        SendResult.Prepared(plan.displayName)
                    startChooser(action.text) -> SendResult.PreparedInChooser
                    else -> SendResult.Failed("${plan.displayName} refused the message")
                }
            }
            is Plan.Targeted ->
                if (startShare(action.text, plan.pkg)) {
                    SendResult.Prepared(plan.displayName)
                } else {
                    SendResult.Failed("${plan.displayName} refused the share")
                }
            is Plan.NotInstalled -> SendResult.ServiceNotInstalled(plan.displayName)
            Plan.Chooser ->
                if (startChooser(action.text)) {
                    SendResult.PreparedInChooser
                } else {
                    SendResult.Failed("no app accepted the message")
                }
        }
    }

    /**
     * Whether this message would have gone hands-free but for the assistant
     * role. True only when the skill asked for a true send, the service can
     * actually do one, we know who to send it to, and the sole thing missing
     * is that Ari isn't the user's default assistant.
     *
     * Deliberately indifferent to whether [android.Manifest.permission.SEND_SMS]
     * is granted: without the role we never asked for it, so a missing grant is
     * a consequence of the same fact rather than a second reason. Drives the
     * one-line offer in [ActionHandler] — see [dev.heyari.ari.messaging.HandsFreeNudge].
     */
    fun handsFreeBlockedByRole(action: MessageAction): Boolean {
        if (action.delivery != DELIVERY_SEND) return false
        val service = action.service?.let { services[it] } ?: return false
        if (!service.canSend) return false
        if (action.recipientId.isNullOrBlank()) return false
        return !assistantRole.isDefaultAssistant()
    }

    /**
     * Returns non-null only when the message actually went. Every other
     * outcome — service can't send, no recipient resolved, not the default
     * assistant, permission refused — returns null so the caller composes
     * instead.
     */
    private fun trueSend(action: MessageAction): SendResult? {
        if (action.delivery != DELIVERY_SEND) return null
        val service = action.service?.let { services[it] } ?: return null
        if (!service.canSend) return null
        val destination = action.recipientId?.takeIf { it.isNotBlank() } ?: return null
        return when (val r = smsSender.send(destination, action.text)) {
            SmsSender.Result.Sent -> SendResult.Sent(service.displayName)
            SmsSender.Result.NoPermission -> null
            SmsSender.Result.NotDefaultAssistant -> null
            is SmsSender.Result.NotSent -> {
                // The network refused it, so falling through to compose is the
                // right answer for the same reason it is everywhere else here:
                // the user keeps what they dictated and can retry with a tap.
                // What must not happen is Ari saying "sent".
                Log.w(TAG, "network refused the send, composing instead: ${r.reason}")
                null
            }
            is SmsSender.Result.Failed -> {
                Log.w(TAG, "true send failed, composing instead: ${r.reason}")
                null
            }
        }
    }

    private fun startUri(
        uri: String,
        pkg: String?,
        action: MessagingService.IntentAction,
    ): Boolean = try {
        val name = when (action) {
            MessagingService.IntentAction.SENDTO -> Intent.ACTION_SENDTO
            MessagingService.IntentAction.VIEW -> Intent.ACTION_VIEW
        }
        context.startActivity(
            Intent(name, Uri.parse(uri))
                .apply { if (pkg != null) setPackage(pkg) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "${pkg ?: "default handler"} did not handle $uri", e)
        false
    }

    private fun startShare(text: String, pkg: String): Boolean = try {
        context.startActivity(
            shareIntent(text).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "$pkg did not handle a text/plain share", e)
        false
    }

    private fun startChooser(text: String): Boolean = try {
        context.startActivity(
            Intent.createChooser(shareIntent(text), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity handled the share chooser", e)
        false
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    companion object {
        private const val TAG = "MessageLauncher"
        private const val DELIVERY_SEND = "send"

        /**
         * Pure routing decision, kept out of [send] so it can be tested without
         * an Android framework. An unrecognised service id is not an error — it
         * falls to the chooser, which is how a messenger nobody has written a
         * catalogue entry for still reaches the user.
         *
         * Note every package in the catalogue must also appear in the
         * `<queries>` block, or [isInstalled] reports false for an app that is
         * plainly there.
         */
        fun plan(
            serviceId: String?,
            catalogue: Map<String, MessagingService>,
            recipientId: String?,
            encodedText: String,
            isInstalled: (String) -> Boolean,
        ): Plan {
            val service = serviceId?.let { catalogue[it.lowercase()] } ?: return Plan.Chooser
            val uris = service.chatUris(recipientId, encodedText)
            // No packages means the service is addressed by scheme, so there
            // is nothing to check for and nothing to scope the intent to.
            if (service.packages.isEmpty()) {
                return if (uris.isEmpty()) {
                    Plan.Chooser
                } else {
                    Plan.Templated(uris, null, service.intentAction, service.displayName)
                }
            }
            val pkg = service.packages.firstOrNull(isInstalled)
                ?: return Plan.NotInstalled(service.displayName)
            return if (uris.isEmpty()) {
                Plan.Targeted(pkg, service.displayName)
            } else {
                Plan.Templated(uris, pkg, service.intentAction, service.displayName)
            }
        }

        fun shareIntent(text: String): Intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
    }
}

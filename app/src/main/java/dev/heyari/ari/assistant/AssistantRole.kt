package dev.heyari.ari.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Ari is the device's current default assistant.
 *
 * Load-bearing for SMS. Play's SMS and Call Log policy lists "Default Assistant
 * handler" as a permitted use of `SEND_SMS`, on two conditions: the app must
 * already hold the role before it prompts for the permission, and it must stop
 * using the permission the moment it stops being the default handler. Both are
 * enforced here rather than in policy paperwork — the install flow asks this
 * before requesting the permission, and `SmsSender` asks again before every
 * send.
 *
 * Asked two ways because they can disagree. [RoleManager.isRoleHeld] is the
 * direct answer, but assistant selection is also surfaced through the Secure
 * settings the system reads when dispatching an assist gesture, and on some
 * devices only the latter reflects a user's pick. Either saying yes is enough:
 * a false negative costs the user hands-free sending they've legitimately
 * enabled, which is the failure worth avoiding.
 */
@Singleton
class AssistantRole @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun isDefaultAssistant(): Boolean = holdsRole() || isNamedInSecureSettings()

    private fun holdsRole(): Boolean =
        context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

    /**
     * `Settings.Secure.ASSISTANT` and `VOICE_INTERACTION_SERVICE` are `@hide`,
     * so the keys are spelled out. Reading Secure settings by name is public
     * API; only writing them is restricted. Each holds a flattened
     * [ComponentName], except on devices that store a bare package name.
     */
    private fun isNamedInSecureSettings(): Boolean = SECURE_KEYS.any { key ->
        val raw = Settings.Secure.getString(context.contentResolver, key)
            ?.takeIf { it.isNotBlank() }
            ?: return@any false
        val pkg = ComponentName.unflattenFromString(raw)?.packageName ?: raw
        pkg == context.packageName
    }

    private companion object {
        val SECURE_KEYS = listOf("assistant", "voice_interaction_service")
    }
}

/**
 * Deep-link to the system page where the user picks their assistant.
 *
 * Not `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)`: that returns null
 * on most devices, where the role is already held by the preinstalled
 * assistant, so there is no in-app dialog to offer and the settings page is the
 * only way through.
 */
fun openDefaultAssistantSettings(context: Context) {
    val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}

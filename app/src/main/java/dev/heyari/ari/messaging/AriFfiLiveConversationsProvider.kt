package dev.heyari.ari.messaging

import uniffi.ari_ffi.FfiLiveConversationsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the FFI contract to [LiveConversations].
 *
 * Names only — this is the boundary the whole privacy stance rests on. The
 * pending intents, packages and notification keys stay on this side of it.
 */
@Singleton
class AriFfiLiveConversationsProvider @Inject constructor(
    private val live: LiveConversations,
) : FfiLiveConversationsProvider {
    override fun `names`(): List<String> = live.names(System.currentTimeMillis())
}

package dev.heyari.ari.data.conversation

import dev.heyari.ari.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped single source of truth for the conversation message list.
 *
 * Both writers append here — the text path ([dev.heyari.ari.ui.conversation.ConversationViewModel])
 * and the voice path ([dev.heyari.ari.voice.VoiceSession], a singleton that
 * runs with no ViewModel alive, e.g. over the lock screen). The chat screen
 * observes [messages]. In-memory only: the log lives for the process lifetime
 * and is cleared on process death. (Unlike the old per-ViewModel list, it now
 * survives activity recreation / backgrounding — deliberately, so turns spoken
 * over the lock screen are still there when the screen reopens.)
 *
 * Mutations go through [MutableStateFlow.update], whose compare-and-set loop is
 * atomic, so voice (appending from Main) and text (from the ViewModel scope)
 * never race on the list.
 */
@Singleton
class ConversationLogRepository @Inject constructor() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    fun append(message: Message) {
        _messages.update { it + message }
    }

    fun clear() {
        _messages.update { emptyList() }
    }
}

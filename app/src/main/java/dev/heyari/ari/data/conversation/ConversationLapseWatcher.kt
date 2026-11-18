package dev.heyari.ari.data.conversation

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.R
import dev.heyari.ari.model.Message
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.ari_ffi.FfiConversationMemory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds "Ari has forgotten the conversation above" to the chat once the engine
 * stops remembering it (ari-android#51): the messages stay on screen until the
 * user clears them, but after a quiet spell (90 seconds on the Short setting)
 * Ari answers the next question as a fresh one.
 *
 * The engine only notices the lapse at the next turn, so after every turn this
 * asks how long is left, waits that out and asks again; a turn in between has
 * pushed the deadline back. The engine's clock and [delay] both stop during
 * deep sleep, so the wait never runs ahead of it.
 */
@Singleton
class ConversationLapseWatcher(
    private val logRepository: ConversationLogRepository,
    private val noteText: () -> String,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        logRepository: ConversationLogRepository,
    ) : this(
        logRepository,
        { context.getString(R.string.conversation_forgotten_note) },
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "watching for a forgotten conversation failed", e)
            },
        ),
    )

    private var watch: Job? = null

    /**
     * Call after every turn, with the engine's
     * [conversationMemory][uniffi.ari_ffi.AriEngine.conversationMemory]: the
     * turn may have started, carried on or ended the conversation.
     */
    @Synchronized
    fun turnFinished(conversationMemory: () -> FfiConversationMemory) {
        watch?.cancel()
        watch = scope.launch {
            while (true) {
                when (val memory = conversationMemory()) {
                    is FfiConversationMemory.Live -> delay(memory.forgottenInMs.toLong().coerceAtLeast(1))
                    FfiConversationMemory.Lapsed -> {
                        addNote()
                        return@launch
                    }
                    FfiConversationMemory.None -> return@launch
                }
            }
        }
    }

    private fun addNote() {
        val last = logRepository.messages.value.lastOrNull() ?: return
        if (last.isNote) return
        logRepository.append(Message(text = noteText(), isFromUser = false, isNote = true))
    }

    private companion object {
        const val TAG = "ConversationLapse"
    }
}

package dev.heyari.ari.model

import dev.heyari.ari.stt.SttState

data class ConversationState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isListening: Boolean = false,
    val wakeWordDetected: Boolean = false,
    val sttState: SttState = SttState.Idle,
    val needsFsnPermission: Boolean = false,
    val needsSetup: Boolean = false,
    /** False until startup checks have finished — prevents the onboarding card flashing on launch. */
    val setupChecked: Boolean = false,
)

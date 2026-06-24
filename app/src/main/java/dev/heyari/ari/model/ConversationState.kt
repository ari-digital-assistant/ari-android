package dev.heyari.ari.model

import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SttState

data class ConversationState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isListening: Boolean = false,
    val wakeWordDetected: Boolean = false,
    val sttState: SttState = SttState.Idle,
    val needsSetup: Boolean = false,
    /** False until startup checks have finished — prevents the onboarding card flashing on launch. */
    val setupChecked: Boolean = false,
    val sttDownload: ModelDownloadState = ModelDownloadState.Idle,
    val llmDownload: LlmDownloadState = LlmDownloadState.Idle,
    /**
     * True when the user picked the Cloud assistant option during
     * onboarding but hasn't yet installed and activated a cloud
     * assistant skill — drives an empty-state hint card directing
     * them to the Skills browser. Cleared once they activate any
     * assistant (cloud or otherwise) or revisit onboarding and pick
     * a non-cloud option.
     */
    val needsCloudAssistantSetup: Boolean = false,
)

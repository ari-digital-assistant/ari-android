package dev.heyari.ari.model

import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SttState
import dev.heyari.ari.ui.conversation.EmptyMode
import dev.heyari.ari.ui.conversation.GreetingModel

data class ConversationState(
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
    /**
     * Transient "still working" signal. Flipped true when processInput
     * blocks past [dev.heyari.ari.ui.conversation.ConversationViewModel]'s
     * still-working threshold, driving the animated [ThinkingIndicator]
     * bubble; cleared the moment the response lands. Never persisted to
     * the conversation log — it must not survive into the record.
     */
    val isThinking: Boolean = false,
    /** True while an in-place dictation session is streaming into the composer. */
    val isDictating: Boolean = false,
    /** True once the STT model is loaded — gates the composer mic button. */
    val sttReady: Boolean = false,
    /**
     * Which face the adaptive empty state shows when the conversation is
     * empty: [EmptyMode.FirstRun] (0 skills — browse-skills CTA) vs
     * [EmptyMode.SetUp] (greeting + suggestion chips). Computed off-thread
     * in [dev.heyari.ari.ui.conversation.ConversationViewModel].
     */
    val emptyMode: EmptyMode = EmptyMode.FirstRun,
    /**
     * The greeting model for the SetUp empty state — anonymous, or a
     * time-of-day-aware named greeting once the user's name is known from
     * remembered facts. Mapped to a translatable string in the composable,
     * never assembled as text here.
     */
    val greeting: GreetingModel = GreetingModel.Anonymous,
    /**
     * Suggestion chips for the SetUp empty state, sourced generically from
     * each installed skill's declared `.examples` (plus an optional
     * "Remember my name" chip). Tapping one submits it as a turn.
     */
    val suggestionChips: List<String> = emptyList(),
)

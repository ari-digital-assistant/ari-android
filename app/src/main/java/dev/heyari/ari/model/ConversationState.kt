package dev.heyari.ari.model

import dev.heyari.ari.listening.ListeningMode
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SttState
import dev.heyari.ari.ui.conversation.EmptyMode
import dev.heyari.ari.ui.conversation.GreetingModel

data class ConversationState(
    val inputText: String = "",
    /**
     * The microphone is genuinely open right now. Under listening modes this is
     * no longer the same as "Ari is switched on": a service standing by for a
     * schedule or a charger is running with the mic closed, and both the top-bar
     * control and the ambient aura should say so.
     */
    val isListening: Boolean = false,
    /** Drives which segment of the top-bar [dev.heyari.ari.ui.conversation.ListeningModeSwitch] is selected. */
    val listeningMode: ListeningMode = ListeningMode.DEFAULT,
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
     * True when the onboarding wizard finished with Schedule or Places ticked
     * under Custom listening but nothing actually configured — the wizard has
     * no room for a time picker or a map. Drives a reminder card, cleared once
     * a schedule or place exists (or the condition is unticked).
     */
    val needsListeningSetup: Boolean = false,
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

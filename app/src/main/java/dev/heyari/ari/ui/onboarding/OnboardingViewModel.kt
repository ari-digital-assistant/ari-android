package dev.heyari.ari.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Wizard-scoped state that doesn't belong in [SettingsRepository] or
 * [dev.heyari.ari.ui.settings.SettingsViewModel]. Tracks transient flow
 * decisions like "was mic denied" so the nav graph can skip screens.
 */
data class OnboardingState(
    /** True when the user denied RECORD_AUDIO and chose "Continue anyway". */
    val micDenied: Boolean = false,
    /** Which assistant category the user chose on screen 5. */
    val assistantChoice: AssistantChoice = AssistantChoice.NONE,
    /** True when this is a revisit (onboardingCompleted was already true). */
    val isRevisit: Boolean = false,
    /** Whether "start listening now" toggle is on (screen 3). */
    val startListeningNow: Boolean = true,
    /** The LLM model ID the user picked on the assistant screen (on-device branch). */
    val selectedLlmModelId: String? = null,
)

enum class AssistantChoice { NONE, ON_DEVICE, CLOUD }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        val alreadyCompleted = runBlocking { settingsRepository.onboardingCompleted.first() }
        _state.update { it.copy(isRevisit = alreadyCompleted) }
    }

    fun setMicDenied(denied: Boolean) {
        _state.update { it.copy(micDenied = denied) }
    }

    fun setAssistantChoice(choice: AssistantChoice) {
        _state.update { it.copy(assistantChoice = choice) }
    }

    fun setStartListeningNow(enabled: Boolean) {
        _state.update { it.copy(startListeningNow = enabled) }
    }

    fun setSelectedLlmModelId(id: String?) {
        _state.update { it.copy(selectedLlmModelId = id) }
    }

    fun completeOnboarding() {
        // Synchronous write — this must complete before the caller navigates
        // away and destroys this ViewModel's scope.
        runBlocking { settingsRepository.setOnboardingCompleted(true) }
    }
}

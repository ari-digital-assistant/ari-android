package dev.heyari.ari.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.router.RouterAvailability
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
    /** Which assistant category the user chose on screen 6. */
    val assistantChoice: AssistantChoice = AssistantChoice.NONE,
    /** True when this is a revisit (onboardingCompleted was already true). */
    val isRevisit: Boolean = false,
    /** Whether "start listening now" toggle is on (screen 4). */
    val startListeningNow: Boolean = true,
    /** The LLM model ID the user picked on the assistant screen (on-device branch). */
    val selectedLlmModelId: String? = null,
    /**
     * Locale picked on the language screen (screen 1). `null` until
     * the user makes (or accepts) a choice, after which it tracks the
     * persisted [SettingsRepository.activeLocale]. Re-rendered for
     * subsequent screens that want to dispatch on language (e.g.
     * skipping the STT model picker for non-English users).
     */
    val selectedLocale: String? = null,
    /**
     * Whether CI publishes a router model for [selectedLocale]. Defaults to
     * `true` so an unlanded probe still shows the download note and attempts
     * the download (which 404s harmlessly) rather than wrongly telling a
     * user they get no router.
     */
    val routerAvailable: Boolean = true,
)

enum class AssistantChoice { NONE, ON_DEVICE, CLOUD }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val routerAvailability: RouterAvailability,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        val alreadyCompleted = runBlocking { settingsRepository.onboardingCompleted.first() }
        // Seed selectedLocale from the persisted DataStore value so the
        // wizard's default reflects the user's prior choice on revisits.
        // First-run users get null here, which the LanguageScreen
        // resolves to SupportedLocales.defaultFromSystem() at render time.
        val persistedLocale = runBlocking { settingsRepository.activeLocale.first() }
        _state.update {
            it.copy(
                isRevisit = alreadyCompleted,
                selectedLocale = persistedLocale,
            )
        }
        // A returning user never calls setSelectedLocale, so the probe for
        // their seeded locale has to be fired here instead.
        viewModelScope.launch {
            val available = routerAvailability.isAvailable(persistedLocale)
            // Drop a late response for a locale the user has since changed
            // away from — this probe can still be in flight when the user
            // picks a different language on screen 1, and must not clobber
            // that screen's own (guarded) verdict.
            _state.update { if (it.selectedLocale == persistedLocale) it.copy(routerAvailable = available) else it }
        }
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

    /**
     * Persist the language picked on the LanguageScreen and update the
     * wizard state. The write goes through immediately so the next
     * screen (and the engine, via AriFfiLocaleProvider's flow
     * subscription — Phase 1) sees the new locale before the user
     * advances.
     */
    fun setSelectedLocale(code: String) {
        _state.update { it.copy(selectedLocale = code) }
        viewModelScope.launch {
            settingsRepository.setActiveLocale(code)
        }
        // Language is step 1 and the assistant screen is step 6, so this
        // round-trip has five screens to land before anyone needs the answer.
        viewModelScope.launch {
            val available = routerAvailability.isAvailable(code)
            // Drop a late response for a language the user has since changed
            // away from — a slow probe for English must not clobber Italian.
            _state.update { if (it.selectedLocale == code) it.copy(routerAvailable = available) else it }
        }
    }

    fun completeOnboarding() {
        // Synchronous write — this must complete before the caller navigates
        // away and destroys this ViewModel's scope.
        runBlocking {
            settingsRepository.setOnboardingCompleted(true)
            // Persist the cloud-choice signal so the conversation screen
            // can keep nagging the user to actually install one until
            // they do. Cleared elsewhere (skill-install flow + manual
            // assistant-pick from settings).
            val pendingCloud = _state.value.assistantChoice == AssistantChoice.CLOUD
            settingsRepository.setPendingCloudAssistantSetup(pendingCloud)
        }
    }
}

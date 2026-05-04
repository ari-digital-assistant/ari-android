package dev.heyari.ari.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.locale.SupportedLocales

/**
 * One row in the language picker — locale code, display name, and
 * whether it's the current selection.
 *
 * `displayName` is the language's self-name (`"English"`, `"Italiano"`)
 * — never translated, since users picking a language they don't yet
 * speak need to recognise it in its own form rather than in their
 * current locale's translation.
 */
private data class LanguageOption(
    val code: String,
    val displayName: String,
)

/** Per-locale display data. Keep in lockstep with [SupportedLocales.codes]. */
private val LANGUAGE_OPTIONS: List<LanguageOption> = listOf(
    LanguageOption(code = "en", displayName = "English"),
    LanguageOption(code = "it", displayName = "Italiano"),
)

/**
 * First onboarding screen: pick the language Ari should use.
 *
 * Defaults to the system language when supported (per
 * [SupportedLocales.defaultFromSystem]), otherwise English. Selection
 * commits immediately to [SettingsRepository.activeLocale] via
 * [OnboardingViewModel.setSelectedLocale] — subsequent screens render
 * in the chosen language without needing to re-traverse the wizard.
 *
 * The picker shows only locales Ari has end-to-end support for.
 * Adding a language is a one-line entry in [LANGUAGE_OPTIONS] +
 * the corresponding work in `SupportedLocales` and the engine.
 */
@Composable
fun LanguageScreen(
    onboardingViewModel: OnboardingViewModel,
    onNext: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val wizardState by onboardingViewModel.state.collectAsStateWithLifecycle()
    val selected = wizardState.selectedLocale ?: SupportedLocales.defaultFromSystem()

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_language_title),
        currentStep = 1,
        onBack = if (wizardState.isRevisit) onBack else null,
        primaryLabel = stringResource(R.string.onboarding_language_continue),
        onPrimary = onNext,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_language_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            for (option in LANGUAGE_OPTIONS) {
                val isSelected = option.code == selected
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .selectable(
                            selected = isSelected,
                            onClick = { onboardingViewModel.setSelectedLocale(option.code) },
                            role = Role.RadioButton,
                        ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            // selectable on the Card already routes the
                            // click; the radio is purely decorative.
                            onClick = null,
                        )
                        Spacer(Modifier.height(0.dp).then(Modifier))
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

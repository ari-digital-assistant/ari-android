package dev.heyari.ari.ui.onboarding

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R

@Composable
fun WelcomeScreen(
    onboardingViewModel: OnboardingViewModel,
    onGetStarted: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val wizardState by onboardingViewModel.state.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = "",
        currentStep = 1,
        onBack = if (wizardState.isRevisit) onBack else null,
        primaryLabel = stringResource(R.string.onboarding_get_started),
        onPrimary = onGetStarted,
        secondaryLabel = if (!wizardState.isRevisit) stringResource(R.string.onboarding_skip) else null,
        onSecondary = if (!wizardState.isRevisit) onSkip else null,
    ) {
        Spacer(Modifier.height(24.dp))

        // Animated WebP with alpha — ImageDecoder produces an
        // AnimatedImageDrawable that ImageView renders with transparency.
        // No video player needed, no extra dependencies.
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    val source = ImageDecoder.createSource(ctx.resources, R.raw.ari_welcome)
                    val drawable = ImageDecoder.decodeDrawable(source)
                    setImageDrawable(drawable)
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = 0
                        drawable.start()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )

        Spacer(Modifier.height(32.dp))

        val title = if (wizardState.isRevisit) {
            stringResource(R.string.onboarding_welcome_title_revisit)
        } else {
            stringResource(R.string.onboarding_welcome_title)
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

package dev.heyari.ari.ui.settings.pages

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heyari.ari.R
import dev.heyari.ari.ui.settings.AdviceMode
import dev.heyari.ari.ui.settings.FalseWakeAdviceViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import dev.heyari.ari.wakeword.FalseWakeRemedy
import java.text.DateFormat
import java.util.Date

/**
 * What Ari says when it has noticed something about its own hearing.
 *
 * One suggestion and a way out, never a menu. The user arrived because it keeps
 * going off, or because Ari wants to check it did not overcorrect; neither is
 * an invitation to explain probability cutoffs to somebody who was doing
 * something else a moment ago.
 */
@Composable
fun FalseWakeAdvicePage(
    mode: AdviceMode,
    onDone: () -> Unit,
    viewModel: FalseWakeAdviceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // Resolved during composition, not inside the effect, so a language change
    // cannot leave a stale string behind.
    val appliedMessage = when (val applied = state.applied) {
        is FalseWakeRemedy.StepDown -> stringResource(
            R.string.false_wake_advice_step_done,
            stringResource(applied.to.displayNameRes),
        )
        is FalseWakeRemedy.Tighten -> stringResource(R.string.false_wake_advice_tighten_done)
        FalseWakeRemedy.Exhausted, null -> null
    }

    LaunchedEffect(mode) { viewModel.load(mode) }
    LaunchedEffect(state.settled) {
        if (!state.settled) return@LaunchedEffect
        // The screen closes the moment the change is made, so without this the
        // only sign anything happened would be Ari waking up less.
        appliedMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        onDone()
    }

    SettingsScaffold(
        title = stringResource(R.string.false_wake_advice_title),
        onBack = onDone,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state.mode) {
                AdviceMode.BURST -> {
                    Text(
                        text = pluralStringResource(
                            R.plurals.false_wake_advice_burst_body,
                            state.falseWakes,
                            state.falseWakes,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    when (val remedy = state.remedy) {
                        is FalseWakeRemedy.StepDown -> Remedy(
                            explanation = stringResource(
                                R.string.false_wake_advice_step_body,
                                stringResource(state.current.displayNameRes),
                            ),
                            actionLabel = stringResource(
                                R.string.false_wake_advice_step_action,
                                stringResource(remedy.to.displayNameRes),
                            ),
                            onAccept = viewModel::accept,
                            onIgnore = viewModel::ignore,
                        )

                        is FalseWakeRemedy.Tighten -> Remedy(
                            explanation = stringResource(R.string.false_wake_advice_tighten_body),
                            actionLabel = stringResource(R.string.false_wake_advice_tighten_action),
                            onAccept = viewModel::accept,
                            onIgnore = viewModel::ignore,
                        )

                        FalseWakeRemedy.Exhausted, null -> {
                            Text(
                                text = stringResource(R.string.false_wake_advice_exhausted_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = viewModel::ignore, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.false_wake_advice_close))
                            }
                        }
                    }
                }

                AdviceMode.REVIEW -> {
                    Text(
                        text = stringResource(
                            R.string.false_wake_advice_review_body,
                            DateFormat.getDateInstance(DateFormat.MEDIUM)
                                .format(Date(state.tightenedAt)),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = viewModel::keepTightened,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.false_wake_advice_review_keep)) }
                    OutlinedButton(
                        onClick = viewModel::undoTightening,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.false_wake_advice_review_undo)) }
                }
            }
        }
    }
}

@Composable
private fun Remedy(
    explanation: String,
    actionLabel: String,
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
        OutlinedButton(onClick = onIgnore, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.false_wake_advice_ignore))
        }
    }
}

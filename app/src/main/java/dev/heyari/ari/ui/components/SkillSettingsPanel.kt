package dev.heyari.ari.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import uniffi.ari_ffi.FfiConfigField

/**
 * Renders a skill's `metadata.ari.settings` schema as an editable form.
 * Used by both the Assistants settings page (when a cloud assistant is
 * selected) and the per-skill detail page on the Installed tab — same
 * widgets, same write semantics, so the user's mental model is the same
 * wherever a setting appears.
 *
 * Field types this knows about: `text`, `secret`, `select`. Anything
 * else is silently skipped so the parser can grow new types
 * (`device_calendar`, etc.) without crashing the panel — though obviously
 * the new types won't render until they're added here.
 *
 * Writes happen on focus loss / radio click, not on every keystroke.
 * Keeps the FFI write rate low and gives users a chance to abandon a
 * partial edit by tapping back.
 *
 * Secrets are masked: the FFI returns the literal `"••••••••"` sentinel
 * when a value is set (never the real value), and we render that as a
 * placeholder until the user starts typing — at which point we treat
 * the input field as a fresh entry. There's no "show password" toggle
 * by design; the assumption is the user typed it once and trusts they
 * pasted the right thing.
 */
@Composable
fun SkillSettingsPanel(
    fields: List<FfiConfigField>,
    onValueChange: (key: String, value: String, isSecret: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fields.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (field in fields) {
            when (field.fieldType) {
                "text" -> TextField(field, onValueChange)
                "secret" -> SecretField(field, onValueChange)
                "select" -> SelectField(field, onValueChange)
                // Unknown type → skip silently. Lets the manifest schema
                // grow new field types without older client builds
                // crashing on encounter.
            }
        }
    }
}

@Composable
private fun TextField(
    field: FfiConfigField,
    onValueChange: (String, String, Boolean) -> Unit,
) {
    var localValue by remember(field.key) {
        mutableStateOf(field.currentValue ?: field.defaultValue ?: "")
    }
    val initial = remember(field.key) { field.currentValue ?: field.defaultValue ?: "" }
    val callback = rememberUpdatedState(onValueChange)
    OutlinedTextField(
        value = localValue,
        onValueChange = { localValue = it },
        label = { Text(field.label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (!state.isFocused && localValue.isNotEmpty() && localValue != initial) {
                    callback.value(field.key, localValue, false)
                }
            },
        singleLine = true,
    )
    // Defensive flush on dispose: if the user typed a new value and left
    // the screen via system back without first defocusing the field
    // (Compose doesn't always fire onFocusChanged on disposal), we'd
    // otherwise lose the input. Compare against `initial` so we don't
    // re-send unchanged values on every recompose-then-dispose.
    DisposableEffect(field.key) {
        onDispose {
            if (localValue.isNotEmpty() && localValue != initial) {
                callback.value(field.key, localValue, false)
            }
        }
    }
}

@Composable
private fun SecretField(
    field: FfiConfigField,
    onValueChange: (String, String, Boolean) -> Unit,
) {
    val hasExisting = field.currentValue == "••••••••"
    // When a secret is already stored we want the user to *see* that
    // — Material3 `placeholder` only renders while focused, which made
    // the field look empty on first glance and led to the user thinking
    // their saved API key had vanished. Seed the field's actual value
    // with bullets in that case so they're always visible. Combined
    // with PasswordVisualTransformation the literal bullets and the
    // mask both render as bullets, so it looks identical to a real
    // masked secret.
    var localValue by remember(field.key, hasExisting) {
        mutableStateOf(if (hasExisting) PLACEHOLDER_BULLETS else "")
    }
    // `dirty` flips the moment the user types anything — separates "the
    // user genuinely entered a new value" from "the field still holds
    // the placeholder bullets we seeded". Only dirty values get
    // persisted; otherwise a tap-and-tap-back on a stored secret would
    // re-write the bullet string over the real one in storage.
    var dirty by remember(field.key, hasExisting) { mutableStateOf(false) }
    val callback = rememberUpdatedState(onValueChange)
    OutlinedTextField(
        value = localValue,
        onValueChange = {
            if (!dirty) {
                dirty = true
                // Strip the seeded bullets so the first keystroke
                // doesn't end up appended to a literal bullet prefix.
                localValue = it.removePrefix(PLACEHOLDER_BULLETS)
            } else {
                localValue = it
            }
        },
        label = { Text(field.label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                // Clear the seeded bullets on focus so the user has an
                // empty field to type into, instead of having to manually
                // delete eight bullets first.
                if (state.isFocused && !dirty && hasExisting) {
                    localValue = ""
                }
            },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    // Single source of truth for persistence: flush on dispose if the
    // user actually typed something. Focus-loss flushing was racy (it
    // doubled up with dispose, doing two writes for every back-press)
    // and disposable-flush is what catches the system-back path
    // anyway, so we keep just the one.
    DisposableEffect(field.key) {
        onDispose {
            if (dirty && localValue.isNotEmpty()) {
                callback.value(field.key, localValue, true)
            }
        }
    }
}

private const val PLACEHOLDER_BULLETS = "••••••••"

@Composable
private fun SelectField(
    field: FfiConfigField,
    onValueChange: (String, String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        for (option in field.options) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                RadioButton(
                    selected = field.currentValue == option.value,
                    onClick = { onValueChange(field.key, option.value, false) },
                )
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

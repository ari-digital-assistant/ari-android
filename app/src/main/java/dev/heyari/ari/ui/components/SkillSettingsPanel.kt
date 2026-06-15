package dev.heyari.ari.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.heyari.ari.R
import dev.heyari.ari.calendar.CalendarProvider
import dev.heyari.ari.tasks.TasksProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.ari_ffi.FfiConfigField
import uniffi.ari_ffi.FfiSelectOption
import uniffi.ari_ffi.FfiSettingsQueryResult

/**
 * Hilt entry point so device-picker composables can grab the
 * Calendar / Tasks providers without going through a ViewModel. Each
 * picker is a small, self-contained widget — adding a dedicated VM
 * just to plumb a singleton would be more code than the picker
 * itself.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
private interface PlatformProviderEntryPoint {
    fun calendarProvider(): CalendarProvider
    fun tasksProvider(): TasksProvider
}

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
    querySkillSetting: suspend (field: String, values: Map<String, String>) -> FfiSettingsQueryResult,
    settingsAction: suspend (action: String, values: Map<String, String>) -> FfiSettingsQueryResult,
    modifier: Modifier = Modifier,
) {
    if (fields.isEmpty()) return

    // Index by key so the visibility check can look up the
    // referenced controller field in O(1). The state refreshes
    // whenever the ViewModel re-fetches after a setSkillSetting
    // write, so editing the controller reactively hides/shows gated
    // fields without any extra wiring here.
    val byKey = remember(fields) { fields.associateBy { it.key } }

    // Bumped by an action whose result asks for a refresh (`refresh ==
    // true`) — e.g. signing in via the OAuth action button, after which
    // the `agent_id` dynamic_select should re-fetch now that a token
    // exists. Threaded into every dependent query's LaunchedEffect key
    // list so a refresh re-runs them all.
    var refreshNonce by remember { mutableStateOf(0) }

    // Split top-level fields (rendered inline) from collapsed-group
    // fields (rendered inside a per-group expander). Grouping preserves
    // declaration order within each group. Kept generic: the group
    // label is whatever the skill's manifest declared on `collapsed_group`.
    val topLevel = remember(fields) { fields.filter { it.collapsedGroup == null } }
    val groups = remember(fields) {
        fields.filter { it.collapsedGroup != null }
            .groupBy { it.collapsedGroup!! }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (field in topLevel) {
            renderField(field, byKey, onValueChange, querySkillSetting, settingsAction, refreshNonce) {
                refreshNonce++
            }
        }
        for ((label, groupFields) in groups) {
            CollapsedGroup(label = label) {
                for (field in groupFields) {
                    renderField(field, byKey, onValueChange, querySkillSetting, settingsAction, refreshNonce) {
                        refreshNonce++
                    }
                }
            }
        }
    }
}

/**
 * Per-field rendering shared by both the top-level loop and the
 * collapsed-group bodies. Dispatches on `fieldType`, then appends the
 * help text (once, generically) and the orthogonal `validate` verdict.
 * Factored out so grouped and ungrouped fields stay byte-for-byte
 * identical — the only difference between them is the expander wrapper.
 */
@Composable
private fun renderField(
    field: FfiConfigField,
    byKey: Map<String, FfiConfigField>,
    onValueChange: (key: String, value: String, isSecret: Boolean) -> Unit,
    querySkillSetting: suspend (field: String, values: Map<String, String>) -> FfiSettingsQueryResult,
    settingsAction: suspend (action: String, values: Map<String, String>) -> FfiSettingsQueryResult,
    refreshNonce: Int,
    onRefresh: () -> Unit,
) {
    if (!isVisible(field, byKey)) return
    when (field.fieldType) {
        "text" -> TextField(field, onValueChange)
        "secret" -> SecretField(field, onValueChange)
        "select" -> SelectField(field, onValueChange)
        "device_calendar" -> DeviceCalendarField(field, onValueChange)
        "device_task_list" -> DeviceTaskListField(field, onValueChange)
        "dynamic_select" -> DynamicSelectField(field, byKey, onValueChange, querySkillSetting, refreshNonce)
        "action" -> ActionField(field, settingsAction, byKey, onRefresh)
        // Unknown type → skip silently. Lets the manifest schema
        // grow new field types without older client builds
        // crashing on encounter.
    }
    // Help text under any field, rendered once here so it's DRY across
    // all field types. The copy comes from the skill manifest's
    // `help_text` — nothing frontend- or skill-specific lives here.
    field.helpText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    // `validate` is orthogonal to fieldType: a validate field is
    // still a text/secret/etc. field that rendered its normal
    // widget above. Append the inline ✓/✗ verdict beneath it.
    // `dynamic_select` is excluded — it already surfaces its own
    // query status, so a second one would double up.
    if (field.validate && field.fieldType != "dynamic_select") {
        ValidateStatus(field, byKey, querySkillSetting, refreshNonce)
    }
}

/**
 * Disclosure wrapper for a `collapsed_group`. Collapsed by default so a
 * fallback path (e.g. "Use token authentication instead") stays out of
 * the way until the user deliberately opens it. The label is whatever
 * the manifest declared — no skill-specific strings here.
 */
@Composable
private fun CollapsedGroup(label: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "▾ $label" else "▸ $label")
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

/**
 * `action` field — a button that fires an effectful round-trip into the
 * skill (`settingsAction`) and renders the ✓/✗ verdict using the same
 * [DynState] visual language as `validate`. The skill supplies the
 * button label, the success message, and the error text; if the result
 * asks for a refresh (`refresh == true`) we bump the panel's refresh
 * nonce so every dependent query re-runs (e.g. re-fetch `agent_id`
 * after sign-in). Nothing here is skill-specific.
 */
@Composable
private fun ActionField(
    field: FfiConfigField,
    settingsAction: suspend (String, Map<String, String>) -> FfiSettingsQueryResult,
    byKey: Map<String, FfiConfigField>,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember(field.key) { mutableStateOf<DynState>(DynState.Idle) }
    // Hoist out of the coroutine: stringResource can only be called from a @Composable context,
    // not from inside scope.launch { … }. Capture once here and close over the val below.
    val actionFailed = stringResource(R.string.skill_panel_action_failed)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(
            enabled = status !is DynState.Loading,
            onClick = {
                status = DynState.Loading
                scope.launch {
                    val values = field.dependsOn.associateWith { k ->
                        byKey[k]?.let { it.currentValue ?: it.defaultValue ?: "" } ?: ""
                    }
                    val res = runCatching { settingsAction(field.key, values) }.getOrNull()
                    status = when {
                        res == null -> DynState.Failed(actionFailed)
                        res.ok -> {
                            if (res.refresh) onRefresh()
                            DynState.Validated(res.message)
                        }
                        else -> DynState.Failed(res.error ?: actionFailed)
                    }
                }
            },
        ) { Text(field.label) }
        when (val s = status) {
            DynState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.skill_panel_checking),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is DynState.Validated -> Text(
                text = "✓ " + (s.message ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )

            is DynState.Failed -> Text(
                text = "✗ " + s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )

            else -> {}
        }
    }
}

/**
 * Resolve `show_when` against the effective value of the referenced
 * field. Effective value = currentValue (what the user's stored), or
 * defaultValue (what the skill author declared) if nothing's stored
 * yet. Missing controller → hide defensively (the parser should have
 * rejected this at publish time, so seeing it here means either a
 * stale client build or a tampered bundle — either way, show-nothing
 * is the safer outcome than surfacing a field whose intended
 * visibility gate we can't evaluate).
 */
private fun isVisible(
    field: FfiConfigField,
    byKey: Map<String, FfiConfigField>,
): Boolean {
    val controllerKey = field.showWhenKey ?: return true
    val controller = byKey[controllerKey] ?: return false
    val effective = controller.currentValue ?: controller.defaultValue ?: return false
    return effective in field.showWhenEquals
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

/**
 * Render state for a skill-backed settings query — shared by both
 * `dynamic_select` (options aren't baked into the manifest; they're
 * fetched at settings-time once the field's `depends_on` siblings have
 * committed values) and `validate` fields (which run the same query but
 * only care about pass/fail). The states map directly to what the user
 * sees: nothing-to-do, in-flight, resolved options, a successful
 * validation (`{ok:true, message}` with no options), or a failure.
 */
private sealed interface DynState {
    object Idle : DynState
    object Loading : DynState
    data class Options(val opts: List<FfiSelectOption>) : DynState

    /**
     * A successful query that returned a `message` and no options — the
     * shape a `validate` field's `settings_query` produces (`{ok:true,
     * message}`). Kept distinct from [Options] so a validate success
     * doesn't get swallowed as "empty options" and lose its message.
     */
    data class Validated(val message: String?) : DynState
    data class Failed(val message: String) : DynState
}

/**
 * Shared, debounced fetch driving any skill-backed settings query.
 * Both [DynamicSelectField] and [ValidateStatus] read their dependency
 * values the same way visibility gating does (effective value =
 * currentValue, falling back to defaultValue), key a [LaunchedEffect]
 * on a stable serialisation of those values so a no-op recompose won't
 * re-fire, debounce 400ms, then fold the result into a [DynState].
 *
 * `attempt` lets a caller force a re-run (the dropdown's Retry button);
 * pass a value that changes to trigger a fresh fetch. Returns the
 * current state for the caller to render however it likes.
 */
@Composable
private fun rememberSettingsQuery(
    field: FfiConfigField,
    byKey: Map<String, FfiConfigField>,
    querySkillSetting: suspend (String, Map<String, String>) -> FfiSettingsQueryResult,
    attempt: Int,
    refreshNonce: Int,
): DynState {
    val depValues: Map<String, String> = field.dependsOn.associateWith { k ->
        byKey[k]?.let { it.currentValue ?: it.defaultValue ?: "" } ?: ""
    }
    val allPresent = field.dependsOn.isNotEmpty() && depValues.values.all { it.isNotBlank() }
    val depKey = depValues.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
    var state by remember(field.key) { mutableStateOf<DynState>(DynState.Idle) }
    // Resolve the i18n fallback up here: the fold below runs inside a
    // LaunchedEffect (not a @Composable scope), so stringResource can't
    // be called there. Capturing it as a local val keeps the failure
    // message translatable.
    val queryFailed = stringResource(R.string.skill_panel_query_failed)

    LaunchedEffect(depKey, allPresent, attempt, refreshNonce) {
        if (!allPresent) {
            state = DynState.Idle
            return@LaunchedEffect
        }
        state = DynState.Loading
        delay(400) // Debounce — collapse a flurry of dependency edits into one fetch.
        val res = runCatching { querySkillSetting(field.key, depValues) }.getOrNull()
        state = when {
            res == null -> DynState.Failed(queryFailed)
            // A validate-style success: ok, no options, just a message.
            res.ok && res.options.isEmpty() && res.message != null -> DynState.Validated(res.message)
            res.ok -> DynState.Options(res.options)
            else -> DynState.Failed(res.error ?: queryFailed)
        }
    }

    return state
}

/**
 * Inline ✓/✗ status for a field declared with `validate == true` (e.g.
 * a credential the skill can verify, like the Home Assistant token). The
 * field renders its normal widget (text/secret) above this — we only
 * append the verdict. Driven by the SAME debounced [rememberSettingsQuery]
 * as `dynamic_select`, keyed on the field's `depends_on`: ✓ shows the
 * skill's success `message`, ✗ shows its `error`. Stays invisible until
 * the dependencies are present (nothing to validate yet).
 */
@Composable
private fun ValidateStatus(
    field: FfiConfigField,
    byKey: Map<String, FfiConfigField>,
    querySkillSetting: suspend (String, Map<String, String>) -> FfiSettingsQueryResult,
    refreshNonce: Int,
) {
    when (val s = rememberSettingsQuery(field, byKey, querySkillSetting, attempt = 0, refreshNonce = refreshNonce)) {
        DynState.Idle -> {} // Deps not present yet — nothing to validate.
        DynState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.skill_panel_checking),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is DynState.Validated -> Text(
            text = "✓ " + (s.message ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        is DynState.Failed -> Text(
            text = "✗ " + s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        // A validate query that happened to return options is malformed
        // for a validate field — ignore rather than render a stray picker.
        is DynState.Options -> {}
    }
}

/**
 * `dynamic_select` field — a radio picker whose options are fetched
 * from the skill at settings-time rather than declared statically.
 *
 * The fetch auto-fires (debounced) the moment every `depends_on`
 * sibling has a non-blank committed value, and re-fires whenever those
 * values change. Until then we sit in [DynState.Idle] with a hint to
 * fill the upstream fields first. A failed fetch surfaces the skill's
 * error message plus a Retry button.
 *
 * Sibling values are read the same way visibility gating reads them:
 * effective value = currentValue, falling back to defaultValue. We key
 * the [LaunchedEffect] on a stable serialisation of those values so a
 * recompose that doesn't actually change a dependency won't re-fire the
 * query, but an edit to any dependency will.
 */
@Composable
private fun DynamicSelectField(
    field: FfiConfigField,
    byKey: Map<String, FfiConfigField>,
    onValueChange: (String, String, Boolean) -> Unit,
    querySkillSetting: suspend (String, Map<String, String>) -> FfiSettingsQueryResult,
    refreshNonce: Int,
) {
    // Retry lives here (the dropdown's button); bumping it re-keys the
    // shared query's LaunchedEffect to force a fresh fetch. A panel
    // refresh (`refreshNonce`) also re-runs the query — both are wired
    // into the shared query's LaunchedEffect key list.
    var attempt by remember(field.key) { mutableStateOf(0) }
    val state = rememberSettingsQuery(field, byKey, querySkillSetting, attempt, refreshNonce)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        when (val s = state) {
            DynState.Idle -> Text(
                text = stringResource(R.string.skill_panel_enter_deps_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )

            DynState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.skill_panel_checking),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is DynState.Failed -> Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(onClick = { attempt++ }) {
                    Text(stringResource(R.string.skill_panel_retry))
                }
            }

            // A dropdown's query is expected to return options. A bare
            // validated message (no options) means the skill treated this
            // like a validate field — treat it as "no options" rather than
            // dropping a stray ✓ into a picker.
            is DynState.Validated -> Text(
                text = stringResource(R.string.skill_panel_no_options),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )

            is DynState.Options -> {
                if (s.opts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skill_panel_no_options),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                for (o in s.opts) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        RadioButton(
                            selected = field.currentValue == o.value,
                            onClick = { onValueChange(field.key, o.value, false) },
                        )
                        Text(
                            text = o.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * `device_calendar` field — render a radio picker populated from
 * `CalendarContract.Calendars` at runtime. Gated on the READ_CALENDAR
 * permission: if it isn't granted yet, show a single "Allow access"
 * button that triggers the system grant flow. Re-queries the calendar
 * list when the permission flips.
 */
@Composable
private fun DeviceCalendarField(
    field: FfiConfigField,
    onValueChange: (String, String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val provider = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PlatformProviderEntryPoint::class.java)
            .calendarProvider()
    }

    // Ask for read AND write together — the user's already consenting
    // to "this picker lists calendars that Ari will write to", so
    // splitting into two prompts (one to see the list, another later
    // at action time to actually insert) is gratuitous and risks the
    // second prompt surprising the user while they're expecting the
    // skill to just work.
    var hasPerm by remember {
        mutableStateOf(
            provider.hasReadPermission() && provider.hasWritePermission(),
        )
    }
    var calendars by remember(hasPerm) {
        mutableStateOf(if (hasPerm) provider.listCalendars() else emptyList())
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        hasPerm = allGranted
        if (allGranted) calendars = provider.listCalendars()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (!hasPerm) {
            Text(
                text = "Calendar access is needed to list your calendars and save events to them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            FilledTonalButton(
                onClick = {
                    launcher.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    )
                },
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            ) { Text(stringResource(R.string.skill_panel_allow_calendar)) }
            return@Column
        }

        if (calendars.isEmpty()) {
            Text(
                text = stringResource(R.string.skill_panel_no_calendars),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            return@Column
        }

        for (cal in calendars) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                RadioButton(
                    selected = field.currentValue == cal.id.toString(),
                    onClick = { onValueChange(field.key, cal.id.toString(), false) },
                )
                Column {
                    Text(
                        text = cal.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (cal.accountName.isNotBlank() && cal.accountName != cal.displayName) {
                        Text(
                            text = cal.accountName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * `device_task_list` field — render a radio picker populated from the
 * OpenTasks `ContentProvider` at runtime. Three states:
 *
 * - No OpenTasks-providing app installed → show the curated install
 *   card. The Tasks destination radio in the parent panel should
 *   already be disabled in this case, but if a manifest puts the
 *   picker in front of the user anyway we still degrade gracefully.
 * - Provider installed but no lists configured → "No task lists
 *   found" hint.
 * - Lists available → standard radio picker.
 */
@Composable
private fun DeviceTaskListField(
    field: FfiConfigField,
    onValueChange: (String, String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val provider = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PlatformProviderEntryPoint::class.java)
            .tasksProvider()
    }

    // Re-poll on every recompose triggered by a lifecycle resume —
    // the user might install a Tasks app from the install card and
    // come back, and we want the picker to light up without a
    // restart.
    var providerInstalled by remember { mutableStateOf(provider.isProviderInstalled()) }
    var hasPerm by remember(providerInstalled) {
        mutableStateOf(providerInstalled && provider.hasAllPermissions())
    }
    var taskLists by remember(providerInstalled, hasPerm) {
        mutableStateOf(if (providerInstalled && hasPerm) provider.listTaskLists() else emptyList())
    }
    LaunchedEffect(Unit) {
        providerInstalled = provider.isProviderInstalled()
        hasPerm = providerInstalled && provider.hasAllPermissions()
        if (providerInstalled && hasPerm) taskLists = provider.listTaskLists()
    }

    // Ask for read AND write together — same reasoning as the
    // calendar picker: consenting once up front beats a second
    // prompt popping up at action time.
    val tasksPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        hasPerm = granted
        if (granted) taskLists = provider.listTaskLists()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (!providerInstalled) {
            NoTasksAppCard(
                modifier = Modifier.padding(start = 8.dp),
                onRefresh = {
                    providerInstalled = provider.isProviderInstalled()
                    hasPerm = providerInstalled && provider.hasReadPermission()
                    if (providerInstalled && hasPerm) taskLists = provider.listTaskLists()
                },
            )
            return@Column
        }

        if (!hasPerm) {
            // Runtime-granted dangerous perms — each compatible app
            // defines its own namespace (org.dmfs.* vs org.tasks.*).
            // The provider helper hands back whichever two match the
            // resolved authority.
            val read = provider.requiredReadPermission()
            val write = provider.requiredWritePermission()
            Text(
                text = "Access to your tasks app is needed to list your task lists and save tasks to them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            FilledTonalButton(
                onClick = {
                    if (read != null && write != null) {
                        tasksPermLauncher.launch(arrayOf(read, write))
                    }
                },
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                enabled = read != null && write != null,
            ) { Text(stringResource(R.string.skill_panel_allow_tasks)) }
            return@Column
        }

        if (taskLists.isEmpty()) {
            // Tasks.org's OpenTasks bridge deliberately only publishes
            // CalDAV-synced lists, not local-only ones — a known
            // limitation that will absolutely bite a user with a
            // fresh Tasks.org install and no sync account. Call it
            // out specifically rather than suggesting they "create a
            // list", because their local lists already exist; the
            // bridge just doesn't forward them.
            val authority = provider.currentAuthority()
            val message = if (authority == "org.tasks.opentasks") {
                "Tasks.org only exposes CalDAV-synced lists here, not local-only lists. " +
                    "Either add a CalDAV account in Tasks.org, or install OpenTasks for " +
                    "local-list support."
            } else {
                "No task lists found. Open your tasks app and create one, then come back."
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            return@Column
        }

        for (list in taskLists) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                RadioButton(
                    selected = field.currentValue == list.id.toString(),
                    onClick = { onValueChange(field.key, list.id.toString(), false) },
                )
                Column {
                    Text(
                        text = list.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (list.accountName.isNotBlank() && list.accountName != list.displayName) {
                        Text(
                            text = list.accountName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Curated card surfaced when the user picks the Tasks destination on
 * a device with no OpenTasks-compatible app installed. Lists four
 * known-good open-source options with deep links to their Play Store
 * pages, plus a generic search fallback. Three or four hand-picked
 * apps reads more honestly than a vague "search the store" dump.
 */
@Composable
private fun NoTasksAppCard(
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "No compatible tasks app",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Skills that create tasks go through the OpenTasks bridge. OpenTasks is recommended — " +
                    "Tasks.org works too but only exposes its CalDAV-synced lists, not local-only ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            for (app in TASKS_APP_SUGGESTIONS) {
                TextButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=${app.packageName}"),
                            )
                            context.startActivity(intent)
                        }.onFailure {
                            // Play Store may not be present (e.g. on a degoogled
                            // device). Fall back to the web URL.
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            "https://play.google.com/store/apps/details?id=${app.packageName}",
                                        ),
                                    ),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = app.tagline,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://search?q=opentasks+caldav"),
                            ),
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.skill_panel_browse_more))
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onRefresh) { Text(stringResource(R.string.skill_panel_refresh_after_install)) }
        }
    }
}

private data class TasksAppSuggestion(
    val packageName: String,
    val label: String,
    val tagline: String,
)

/**
 * Hand-picked OpenTasks-compatible apps, in recommendation order.
 *
 * OpenTasks (the dmfs reference app) leads because it's the only
 * widely-used option that exposes *local* task lists through its
 * ContentProvider — Tasks.org deliberately confines its OpenTasks
 * bridge to CalDAV-synced lists, so a user running Tasks.org with no
 * sync account will find Ari can't see any of their lists. The
 * NoTasksAppCard copy calls that out.
 */
private val TASKS_APP_SUGGESTIONS = listOf(
    TasksAppSuggestion(
        "org.dmfs.tasks",
        "OpenTasks",
        "Recommended — reference implementation, exposes local task lists directly.",
    ),
    TasksAppSuggestion(
        "org.tasks",
        "Tasks.org",
        "Only exposes CalDAV-synced lists to Ari. Local-only lists won't appear.",
    ),
    TasksAppSuggestion(
        "at.techbee.jtx",
        "jtx Board",
        "Journals, notes and tasks in one CalDAV-syncing app.",
    ),
)

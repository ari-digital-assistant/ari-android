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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.heyari.ari.reminders.CalendarProvider
import dev.heyari.ari.reminders.TasksProvider
import uniffi.ari_ffi.FfiConfigField

/**
 * Hilt entry point so device-picker composables can grab the
 * Calendar / Tasks providers without going through a ViewModel. Each
 * picker is a small, self-contained widget — adding a dedicated VM
 * just to plumb a singleton would be more code than the picker
 * itself.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
private interface RemindersProviderEntryPoint {
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
    modifier: Modifier = Modifier,
) {
    if (fields.isEmpty()) return

    // Index by key so the visibility check can look up the
    // referenced controller field in O(1). The state refreshes
    // whenever the ViewModel re-fetches after a setSkillSetting
    // write, so editing the controller reactively hides/shows gated
    // fields without any extra wiring here.
    val byKey = remember(fields) { fields.associateBy { it.key } }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (field in fields) {
            if (!isVisible(field, byKey)) continue
            when (field.fieldType) {
                "text" -> TextField(field, onValueChange)
                "secret" -> SecretField(field, onValueChange)
                "select" -> SelectField(field, onValueChange)
                "device_calendar" -> DeviceCalendarField(field, onValueChange)
                "device_task_list" -> DeviceTaskListField(field, onValueChange)
                // Unknown type → skip silently. Lets the manifest schema
                // grow new field types without older client builds
                // crashing on encounter.
            }
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
            .fromApplication(context.applicationContext, RemindersProviderEntryPoint::class.java)
            .calendarProvider()
    }

    var hasPerm by remember { mutableStateOf(provider.hasReadPermission()) }
    var calendars by remember(hasPerm) {
        mutableStateOf(if (hasPerm) provider.listCalendars() else emptyList())
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPerm = granted
        if (granted) calendars = provider.listCalendars()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (!hasPerm) {
            Text(
                text = "Calendar access is needed to list your calendars.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            FilledTonalButton(
                onClick = { launcher.launch(Manifest.permission.READ_CALENDAR) },
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            ) { Text("Allow calendar access") }
            return@Column
        }

        if (calendars.isEmpty()) {
            Text(
                text = "No writable calendars found on this device.",
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
            .fromApplication(context.applicationContext, RemindersProviderEntryPoint::class.java)
            .tasksProvider()
    }

    // Re-poll on every recompose triggered by a lifecycle resume —
    // the user might install a Tasks app from the install card and
    // come back, and we want the picker to light up without a
    // restart.
    var providerInstalled by remember { mutableStateOf(provider.isProviderInstalled()) }
    var hasPerm by remember(providerInstalled) {
        mutableStateOf(providerInstalled && provider.hasReadPermission())
    }
    var taskLists by remember(providerInstalled, hasPerm) {
        mutableStateOf(if (providerInstalled && hasPerm) provider.listTaskLists() else emptyList())
    }
    LaunchedEffect(Unit) {
        providerInstalled = provider.isProviderInstalled()
        hasPerm = providerInstalled && provider.hasReadPermission()
        if (providerInstalled && hasPerm) taskLists = provider.listTaskLists()
    }

    val tasksPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
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
            // Runtime-granted dangerous perm — each compatible app
            // defines its own namespace (org.dmfs.* vs org.tasks.*).
            // The provider helper hands back whichever one matches
            // the resolved authority.
            val perm = provider.requiredReadPermission()
            Text(
                text = "Access to your tasks app is needed to list your task lists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            FilledTonalButton(
                onClick = { perm?.let(tasksPermLauncher::launch) },
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                enabled = perm != null,
            ) { Text("Allow tasks access") }
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
                text = "Ari saves reminders through the OpenTasks bridge. OpenTasks is recommended — " +
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
                Text("Search the store for more")
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onRefresh) { Text("I've installed one — refresh") }
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

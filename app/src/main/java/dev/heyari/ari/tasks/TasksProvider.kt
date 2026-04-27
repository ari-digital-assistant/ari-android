package dev.heyari.ari.tasks

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper around the OpenTasks `ContentProvider` (authority
 * `org.dmfs.tasks`) — the de-facto cross-app tasks API on Android.
 * OpenTasks isn't a system component; it's surfaced by Tasks.org /
 * jtx Board / OpenTasks (the app) / etc, each under their own
 * authority. The first thing every method does is check the
 * authority is actually resolvable.
 *
 * This is a **platform wrapper**, not a skill feature. Any skill
 * declaring `Capability::Tasks` can reach it via the `ari::tasks_*`
 * host imports; this Kotlin surface is also used by generic Android
 * UI (e.g. the `device_task_list` picker in `SkillSettingsPanel`).
 *
 * On a device with no provider installed every method gracefully
 * degrades: [isProviderInstalled] returns false, [listTaskLists] and
 * [primaryTaskList] return empty / null, and [insertTask] is a no-op.
 * The picker composable and action handlers use this to short-circuit
 * the Tasks branch without throwing.
 */
@Singleton
class TasksProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * One row from the OpenTasks `tasklists` table reduced to picker-
     * relevant fields. `accountName` disambiguates lists that share a
     * display name across CalDAV accounts (common with DAVx5 syncing
     * "Personal" from two different servers).
     */
    data class DeviceTaskList(
        val id: Long,
        val displayName: String,
        val accountName: String,
    )

    /**
     * The authority of the first installed OpenTasks-compatible
     * provider, or null if none is installed. Resolved every call —
     * cheap (PackageManager lookup) and correct across app
     * install/uninstall without a caching dance.
     */
    private fun resolveAuthority(): String? {
        for (authority in KNOWN_AUTHORITIES) {
            val found = runCatching {
                context.packageManager.resolveContentProvider(authority, 0) != null
            }.getOrDefault(false)
            if (found) return authority
        }
        return null
    }

    /**
     * Whether any installed app provides an OpenTasks-compatible
     * ContentProvider. Cheap call — `PackageManager.resolveContentProvider`
     * against each known authority until one hits.
     */
    fun isProviderInstalled(): Boolean = resolveAuthority() != null

    /**
     * The authority string of the currently-installed provider, or
     * null if none is installed. Exposed publicly so the UI can tune
     * its messaging per provider — Tasks.org's OpenTasks bridge has a
     * specific known limitation (CalDAV-synced lists only) that's
     * worth calling out when the picker comes back empty.
     */
    fun currentAuthority(): String? = resolveAuthority()

    /**
     * The runtime read-permission the currently-installed provider
     * expects, or null if no provider is installed. Each compatible
     * app defines its own permission namespace: OpenTasks uses
     * `org.dmfs.permission.*`, Tasks.org uses `org.tasks.permission.*`.
     * We declare both in the manifest and look up the right one based
     * on whichever provider we resolved.
     */
    fun requiredReadPermission(): String? = permissionFor(resolveAuthority())

    /** Same as [requiredReadPermission] but for writes. */
    fun requiredWritePermission(): String? = permissionFor(resolveAuthority(), write = true)

    private fun permissionFor(authority: String?, write: Boolean = false): String? {
        val perm = when (authority) {
            "org.dmfs.tasks" -> if (write) "org.dmfs.permission.WRITE_TASKS" else "org.dmfs.permission.READ_TASKS"
            "org.tasks.opentasks" -> if (write) "org.tasks.permission.WRITE_TASKS" else "org.tasks.permission.READ_TASKS"
            else -> return null
        }
        return perm
    }

    /**
     * Whether the runtime tasks read permission is granted for the
     * currently-installed provider. Null authority → false (no
     * provider to be granted against).
     */
    fun hasReadPermission(): Boolean {
        val perm = requiredReadPermission() ?: return false
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether both read AND write permissions are granted. The
     * picker asks for both up front so that enumerating task lists
     * and inserting tasks later both succeed without a second prompt
     * at action time.
     */
    fun hasAllPermissions(): Boolean {
        val read = requiredReadPermission() ?: return false
        val write = requiredWritePermission() ?: return false
        return ContextCompat.checkSelfPermission(context, read) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, write) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Every task list the user has. Empty if no provider is installed
     * or the per-provider READ_TASKS permission isn't granted yet.
     * Sorted by display name.
     *
     * No "writable filter" like the calendar helper — OpenTasks
     * doesn't expose access-level distinctions on its public schema,
     * so we trust whatever the provider hands us.
     */
    fun listTaskLists(): List<DeviceTaskList> {
        val authority = resolveAuthority() ?: return emptyList()
        val listsUri = Uri.parse("content://$authority/tasklists")
        val out = mutableListOf<DeviceTaskList>()
        val projection = arrayOf(LISTS_ID, LISTS_NAME, LISTS_ACCOUNT_NAME)

        runCatching {
            context.contentResolver.query(listsUri, projection, null, null, null)
        }
            .onFailure { e ->
                Log.w(TAG, "task list query on $authority failed: ${e.message}")
            }
            .getOrNull()
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: "(unnamed)"
                    val account = cursor.getString(2) ?: ""
                    out.add(DeviceTaskList(id, name, account))
                }
            }

        return out.sortedBy { it.displayName }
    }

    /**
     * The first available task list, or null if none exist (no
     * provider, or provider exists but no lists configured yet — the
     * latter can happen on a fresh Tasks.org install before the user
     * has set up an account).
     */
    fun primaryTaskList(): DeviceTaskList? = listTaskLists().firstOrNull()

    /**
     * Insert a task into [taskListId]. `dueMillis` is optional —
     * untimed tasks (the shopping-list use case) pass null and the
     * task ends up with no due date. `dueAllDay` controls whether
     * OpenTasks treats a present `dueMillis` as a wall-clock date or
     * as a precise instant; the caller sets this based on whether
     * the source descriptor had a time-of-day.
     *
     * Returns the inserted task's row id, or null on failure.
     */
    fun insertTask(
        taskListId: Long,
        title: String,
        dueMillis: Long? = null,
        dueAllDay: Boolean = false,
        tzId: String? = null,
    ): Long? {
        val authority = resolveAuthority() ?: run {
            Log.w(TAG, "insertTask: no OpenTasks-compatible provider installed")
            return null
        }
        val tasksUri = Uri.parse("content://$authority/tasks")

        val values = ContentValues().apply {
            put(TASKS_LIST_ID, taskListId)
            put(TASKS_TITLE, title)
            if (dueMillis != null) {
                put(TASKS_DUE, dueMillis)
                // IS_ALLDAY=1 means the provider stores the due date
                // as a wall-clock date, ignoring the time portion of
                // dueMillis. IS_ALLDAY=0 is the default and means
                // dueMillis is a precise UTC instant. We also write
                // the timezone whenever IS_ALLDAY=0 — required by
                // some OpenTasks implementations to render the task
                // at the right local time.
                if (dueAllDay) {
                    put(TASKS_IS_ALLDAY, 1)
                } else {
                    put(TASKS_IS_ALLDAY, 0)
                    put(TASKS_TZ, tzId ?: java.util.TimeZone.getDefault().id)
                }
            }
        }

        val taskUri = runCatching {
            context.contentResolver.insert(tasksUri, values)
        }
            .onFailure { e ->
                Log.w(TAG, "task insert failed: ${e.message}")
            }
            .getOrNull()
            ?: return null

        return ContentUris.parseId(taskUri)
    }

    /**
     * One row from the OpenTasks `tasks` table reduced to query-
     * relevant fields. `dueMillis` is always non-null in the result
     * set because [queryTasksInRange] filters by due time; untimed
     * tasks (no due date) don't fit a range query and are skipped.
     */
    data class DeviceTaskRow(
        val id: Long,
        val title: String,
        val dueMillis: Long,
        val dueAllDay: Boolean,
        val listId: Long,
    )

    /**
     * Tasks with `due` in the half-open interval `[startMillis,
     * endMillis)`, ordered by due ascending and capped at [limit].
     * Untimed tasks are skipped (no `due` column value to filter on).
     * Soft-deleted rows are excluded — OpenTasks marks them with
     * `_deleted=1` rather than removing the row, and we don't want
     * "what's on my list" to surface stale entries the user already
     * cancelled.
     *
     * Empty list when no provider is installed, the per-provider
     * READ_TASKS permission is missing, or the range is empty.
     */
    fun queryTasksInRange(
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<DeviceTaskRow> {
        if (limit <= 0 || endMillis <= startMillis) return emptyList()
        val authority = resolveAuthority() ?: return emptyList()
        val tasksUri = Uri.parse("content://$authority/tasks")
        val projection = arrayOf(
            TASKS_ID,
            TASKS_TITLE,
            TASKS_DUE,
            TASKS_IS_ALLDAY,
            TASKS_LIST_ID,
        )
        // OpenTasks soft-deletion column. Filtering with `IS NOT 1`
        // (rather than `=0`) catches both NULL and 0, in case the
        // provider stops setting an explicit zero.
        val selection = "$TASKS_DUE >= ? AND $TASKS_DUE < ? AND ($TASKS_DELETED IS NULL OR $TASKS_DELETED != 1)"
        val args = arrayOf(startMillis.toString(), endMillis.toString())
        val sortOrder = "$TASKS_DUE ASC"

        val out = mutableListOf<DeviceTaskRow>()
        runCatching {
            context.contentResolver.query(tasksUri, projection, selection, args, sortOrder)
        }
            .onFailure { e -> Log.w(TAG, "task range query failed: ${e.message}") }
            .getOrNull()
            ?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1) ?: continue
                    if (cursor.isNull(2)) continue
                    val due = cursor.getLong(2)
                    val allDay = cursor.getInt(3) == 1
                    val listId = cursor.getLong(4)
                    out.add(DeviceTaskRow(id, title, due, allDay, listId))
                }
            }
        return out
    }

    /**
     * Hard-delete a task by id. Returns true if the row existed and
     * was removed.
     *
     * OpenTasks has two delete modes:
     *   1. Plain `delete()` → soft-delete (`_DELETED=1`, queues for
     *      CalDAV sync). Tasks.org's UI keeps showing the row until
     *      the next sync purges it — wrong UX for a user-initiated
     *      cancel of a just-inserted row.
     *   2. Delete with `caller_is_syncadapter=true` + `account_name` +
     *      `account_type` query params → hard-delete, row gone
     *      immediately.
     *
     * We go for (2): query the account fields off the task row
     * first, then pass them to the delete. Falls back to soft-delete
     * if the account query fails — the user still gets some action,
     * and the sync will eventually clean up.
     */
    fun deleteTask(taskId: Long): Boolean {
        val authority = resolveAuthority() ?: return false
        val tasksBase = Uri.parse("content://$authority/tasks")
        val rowUri = ContentUris.withAppendedId(tasksBase, taskId)

        val account = runCatching {
            context.contentResolver.query(
                rowUri,
                arrayOf(TASKS_ACCOUNT_NAME, TASKS_ACCOUNT_TYPE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    val type = c.getString(1)
                    if (!name.isNullOrBlank() && !type.isNullOrBlank()) name to type else null
                } else null
            }
        }.getOrNull()

        val deleteUri = if (account != null) {
            rowUri.buildUpon()
                .appendQueryParameter("caller_is_syncadapter", "true")
                .appendQueryParameter("account_name", account.first)
                .appendQueryParameter("account_type", account.second)
                .build()
        } else {
            Log.w(TAG, "task $taskId: account query returned nothing; falling back to soft delete")
            rowUri
        }

        val deleted = runCatching { context.contentResolver.delete(deleteUri, null, null) }
            .onFailure { Log.w(TAG, "task delete failed for id=$taskId: ${it.message}") }
            .getOrNull()
            ?: return false
        return deleted > 0
    }

    companion object {
        private const val TAG = "TasksProvider"

        /**
         * Authorities we'll probe for an OpenTasks-compatible provider,
         * in probe order. Each compatible app picks its own name, so
         * "does the user have a tasks app" is a list-scan rather than
         * a single lookup. Keep the manifest `<queries><provider>`
         * entries in sync with this list or API 30+ package visibility
         * will hide otherwise-installed apps from us.
         *
         * Known entries:
         * - `org.dmfs.tasks` — OpenTasks (the original reference app).
         * - `org.tasks.opentasks` — Tasks.org (forks the OpenTasks
         *   provider class but registers under its own authority).
         */
        private val KNOWN_AUTHORITIES = listOf(
            "org.dmfs.tasks",
            "org.tasks.opentasks",
        )

        // Column names taken from the OpenTasks schema documented at
        // https://github.com/dmfs/opentasks/blob/master/opentasks-contract/
        // src/main/java/org/dmfs/tasks/contract/TaskContract.java
        // Hard-coded as strings rather than depending on the contract
        // library — adds a single transitive Maven dep for half a
        // dozen string constants we'd never want to change anyway.

        // tasklists columns
        private const val LISTS_ID = "_id"
        private const val LISTS_NAME = "list_name"
        private const val LISTS_ACCOUNT_NAME = "account_name"

        // tasks columns
        private const val TASKS_ID = "_id"
        private const val TASKS_LIST_ID = "list_id"
        private const val TASKS_TITLE = "title"
        private const val TASKS_DUE = "due"
        private const val TASKS_IS_ALLDAY = "is_allday"
        private const val TASKS_TZ = "tz"
        private const val TASKS_DELETED = "_deleted"
        // Denormalised account columns on the tasks URI — used by the
        // hard-delete path to satisfy Tasks.org's "sync adapters must
        // specify an account" check.
        private const val TASKS_ACCOUNT_NAME = "account_name"
        private const val TASKS_ACCOUNT_TYPE = "account_type"
    }
}

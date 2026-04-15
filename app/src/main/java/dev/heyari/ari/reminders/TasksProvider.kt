package dev.heyari.ari.reminders

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper around the OpenTasks `ContentProvider` (authority
 * `org.dmfs.tasks`) for the reminder skill's Tasks / Both destination
 * modes. OpenTasks isn't a system component — it's surfaced by
 * Tasks.org / jtx Board / OpenTasks (the app) / etc, so the first
 * thing every method does is check the authority is actually
 * resolvable.
 *
 * On a device with no provider installed every method gracefully
 * degrades: [isProviderInstalled] returns false, [listTaskLists] and
 * [primaryTaskList] return empty / null, and [insertTask] is a no-op.
 * The picker composable and action handler use this to short-circuit
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
     * Whether any installed app provides the OpenTasks ContentProvider
     * authority. Cheap call — just a `PackageManager.resolveContentProvider`
     * lookup, no permission needed.
     */
    fun isProviderInstalled(): Boolean {
        return runCatching {
            context.packageManager.resolveContentProvider(AUTHORITY, 0) != null
        }.getOrDefault(false)
    }

    /**
     * Every task list the user has. Empty if no provider is installed
     * or if the OpenTasks-defined READ_TASKS permission isn't granted.
     * Sorted by display name.
     *
     * No "writable filter" like the calendar helper — OpenTasks
     * doesn't expose access-level distinctions on its public schema,
     * so we trust whatever the provider hands us.
     */
    fun listTaskLists(): List<DeviceTaskList> {
        if (!isProviderInstalled()) return emptyList()

        val out = mutableListOf<DeviceTaskList>()
        val projection = arrayOf(LISTS_ID, LISTS_NAME, LISTS_ACCOUNT_NAME)

        runCatching {
            context.contentResolver.query(LISTS_URI, projection, null, null, null)
        }
            .onFailure { e ->
                Log.w(TAG, "task list query failed: ${e.message}")
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
     * as a precise instant; the action handler sets this to true for
     * date-only descriptors and false for local-clock ones.
     *
     * Returns the inserted task's row id, or null on failure.
     */
    fun insertTask(
        taskListId: Long,
        title: String,
        dueMillis: Long? = null,
        dueAllDay: Boolean = false,
    ): Long? {
        if (!isProviderInstalled()) {
            Log.w(TAG, "insertTask: no OpenTasks provider installed")
            return null
        }

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
                    put(TASKS_TZ, java.util.TimeZone.getDefault().id)
                }
            }
        }

        val taskUri = runCatching {
            context.contentResolver.insert(TASKS_URI, values)
        }
            .onFailure { e ->
                Log.w(TAG, "task insert failed: ${e.message}")
            }
            .getOrNull()
            ?: return null

        return ContentUris.parseId(taskUri)
    }

    companion object {
        private const val TAG = "TasksProvider"

        /** OpenTasks ContentProvider authority. Stable since 2014. */
        const val AUTHORITY = "org.dmfs.tasks"

        // Column names taken from the OpenTasks schema documented at
        // https://github.com/dmfs/opentasks/blob/master/opentasks-contract/
        // src/main/java/org/dmfs/tasks/contract/TaskContract.java
        // Hard-coded as strings rather than depending on the contract
        // library — adds a single transitive Maven dep for half a
        // dozen string constants we'd never want to change anyway.

        private val LISTS_URI: Uri = Uri.parse("content://$AUTHORITY/tasklists")
        private val TASKS_URI: Uri = Uri.parse("content://$AUTHORITY/tasks")

        // tasklists columns
        private const val LISTS_ID = "_id"
        private const val LISTS_NAME = "list_name"
        private const val LISTS_ACCOUNT_NAME = "account_name"

        // tasks columns
        private const val TASKS_LIST_ID = "list_id"
        private const val TASKS_TITLE = "title"
        private const val TASKS_DUE = "due"
        private const val TASKS_IS_ALLDAY = "is_allday"
        private const val TASKS_TZ = "tz"
    }
}

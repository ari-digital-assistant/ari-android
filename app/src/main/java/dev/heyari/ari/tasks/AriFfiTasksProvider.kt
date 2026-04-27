package dev.heyari.ari.tasks

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiInsertTaskParams
import uniffi.ari_ffi.FfiTaskList
import uniffi.ari_ffi.FfiTaskRow
import uniffi.ari_ffi.FfiTasksProvider

/**
 * Bridges the engine's foreign-callback [FfiTasksProvider] trait to
 * the Android-native [TasksProvider]. Any skill declaring
 * `Capability::Tasks` and calling `ari::tasks_*` host imports from
 * WASM ends up here.
 *
 * Returns 0 for "no row id" (the engine's sentinel for failure) on
 * insert, matching the ABI documented in the FFI crate. Delete
 * failures propagate as `false`; the skill decides whether to treat
 * a missing id as surprising or routine.
 */
@Singleton
class AriFfiTasksProvider @Inject constructor(
    private val tasks: TasksProvider,
) : FfiTasksProvider {

    override fun isProviderInstalled(): Boolean = tasks.isProviderInstalled()

    override fun listLists(): List<FfiTaskList> = tasks.listTaskLists().map {
        FfiTaskList(
            id = it.id.toULong(),
            displayName = it.displayName,
            accountName = it.accountName,
        )
    }

    override fun insert(params: FfiInsertTaskParams): ULong {
        val rowId = tasks.insertTask(
            taskListId = params.listId.toLong(),
            title = params.title,
            dueMillis = params.dueMs,
            dueAllDay = params.dueAllDay,
            tzId = params.tzId,
        )
        return rowId?.toULong() ?: 0UL
    }

    override fun delete(id: ULong): Boolean = tasks.deleteTask(id.toLong())

    override fun queryInRange(
        startMs: Long,
        endMs: Long,
        limit: UInt,
    ): List<FfiTaskRow> = tasks.queryTasksInRange(
        startMillis = startMs,
        endMillis = endMs,
        limit = limit.toInt(),
    ).map { row ->
        FfiTaskRow(
            id = row.id.toULong(),
            title = row.title,
            dueMs = row.dueMillis,
            dueAllDay = row.dueAllDay,
            listId = row.listId.toULong(),
        )
    }
}

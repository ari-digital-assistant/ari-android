package dev.heyari.ari.tasks

import uniffi.ari_ffi.AriEngine

/**
 * Name the engine knows the user's task lists by. A skill's example phrase
 * binds a slot to it — `add {item} to {list:tasks.lists}` — so "add bananas
 * to family shopping" routes on the strength of "family shopping" naming a
 * real list, with no word "list" in the sentence and no assistant involved.
 */
const val TASK_LISTS_VOCABULARY = "tasks.lists"

/**
 * Push the user's current task-list names into the engine.
 *
 * Queries the tasks provider, so call it off the main thread. No provider (or
 * no lists) pushes nothing, which drops the constraint rather than blocking
 * every phrase that names it.
 */
fun AriEngine.pushTaskListVocabulary(tasks: TasksProvider) {
    setVocabulary(TASK_LISTS_VOCABULARY, tasks.listTaskLists().map { it.displayName })
}

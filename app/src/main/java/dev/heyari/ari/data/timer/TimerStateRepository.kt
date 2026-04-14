package dev.heyari.ari.data.timer

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.timerDataStore by preferencesDataStore(name = "ari_timers")

/**
 * Canonical Android-side timer list. Single source of truth for the UI and
 * the notification / alarm layers.
 *
 * Write paths:
 * - `applyEvents` — called from [dev.heyari.ari.actions.TimerCoordinator] when
 *   the skill emits a timer action. Applies the skill's `events` to the local
 *   list and then reconciles against the skill's full `timers` snapshot so
 *   any accumulated drift heals immediately.
 * - `debugInsertTimer` — test hook used by the M3 UI smoke, bypasses the skill.
 *
 * Read paths:
 * - [state] — the full list, collected by screens that render the timer strip.
 * - [observe] — a per-id flow so each [dev.heyari.ari.ui.conversation.TimerCard]
 *   only recomposes when its own timer changes.
 */
@Singleton
class TimerStateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<List<Timer>>(emptyList())
    val state: StateFlow<List<Timer>> = _state.asStateFlow()

    // Completes when the initial DataStore load has finished. BootReceiver
    // awaits this before scheduling alarms, otherwise it reads the
    // still-empty in-memory state and silently drops every live timer.
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        // Prime in-memory state from DataStore, then keep writes in-flight
        // from explicit saves below. We don't collect DataStore on an ongoing
        // basis because we're the only writer.
        scope.launch {
            val stored = context.timerDataStore.data.map { prefs -> prefs[KEY_TIMERS] }.first()
            val parsed = stored?.let { decode(it) } ?: emptyList()
            _state.value = parsed
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()

    /**
     * Replace the local list wholesale with [snapshot]. Used as the final
     * reconciliation step after applying individual events — the skill's
     * `timers` field in every action response is the canonical truth.
     */
    fun replaceAll(snapshot: List<Timer>) {
        _state.value = snapshot
        persist(snapshot)
    }

    fun observe(timerId: String): kotlinx.coroutines.flow.Flow<Timer?> =
        state.map { list -> list.firstOrNull { it.id == timerId } }

    /**
     * Inserts a timer without going through the skill. Used by the M3 UI
     * smoke to exercise card rendering + tick + cancel in isolation. Do not
     * call this from production code paths — the skill must own the list.
     */
    fun debugInsertTimer(timer: Timer) {
        val updated = _state.value + timer
        _state.value = updated
        persist(updated)
    }

    /**
     * Removes one timer by id. UI-initiated cancels go through the skill
     * (`engine.processInput("cancel my X timer")`) in the real path; this is
     * the final write that lands when the skill confirms.
     */
    fun removeById(id: String) {
        val updated = _state.value.filterNot { it.id == id }
        if (updated.size != _state.value.size) {
            _state.value = updated
            persist(updated)
        }
    }

    private fun persist(list: List<Timer>) {
        scope.launch {
            runCatching {
                context.timerDataStore.edit { prefs ->
                    prefs[KEY_TIMERS] = encode(list)
                }
            }.onFailure { Log.w(TAG, "failed to persist timers", it) }
        }
    }

    private fun encode(list: List<Timer>): String {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name ?: JSONObject.NULL)
                    put("endTsMs", t.endTsMs)
                    put("createdTsMs", t.createdTsMs)
                },
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<Timer> =
        runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Timer>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Timer(
                    id = o.getString("id"),
                    name = if (o.isNull("name")) null else o.getString("name"),
                    endTsMs = o.getLong("endTsMs"),
                    createdTsMs = o.getLong("createdTsMs"),
                )
            }
            out
        }.getOrElse {
            Log.w(TAG, "discarding corrupt timers blob", it)
            emptyList()
        }

    private companion object {
        const val TAG = "TimerState"
        val KEY_TIMERS = stringPreferencesKey("timers_v1")
    }
}

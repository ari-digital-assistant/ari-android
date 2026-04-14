package dev.heyari.ari.actions

import android.util.Log
import dev.heyari.ari.data.timer.Timer
import dev.heyari.ari.data.timer.TimerStateRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.notifications.TimerNotifier
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies a `{"action":"timer",...}` envelope from the `dev.heyari.timer`
 * skill. The skill owns its `storage_kv` and always sends the full authoritative
 * `timers` snapshot, so Android can replace its mirror wholesale without
 * diffing — a dropped event or process death mid-update self-heals on the
 * next utterance. The first `create` event in `events` (if any) is surfaced
 * back as an [Attachment.Timer] so the bubble renders a live countdown card.
 */
@Singleton
class TimerCoordinator @Inject constructor(
    private val timerRepository: TimerStateRepository,
    private val alarmScheduler: TimerAlarmScheduler,
    private val notifier: TimerNotifier,
) {
    fun handle(envelope: JSONObject): ActionResult.Spoken {
        val parsed = parseEnvelope(envelope)
        val previous = timerRepository.state.value
        val snapshot = parsed.snapshot ?: previous.also {
            Log.w(TAG, "envelope missing `timers` array; keeping local state")
        }
        timerRepository.replaceAll(snapshot)
        for (diff in diffAlarmWork(previous, snapshot)) {
            when (diff) {
                is AlarmWork.Schedule -> {
                    alarmScheduler.schedule(diff.timer)
                    notifier.showOngoing(diff.timer)
                }
                is AlarmWork.Cancel -> {
                    alarmScheduler.cancel(diff.id)
                    notifier.dismissOngoing(diff.id)
                }
            }
        }
        return ActionResult.Spoken(text = parsed.speak, attachments = parsed.attachments)
    }

    private companion object {
        const val TAG = "TimerCoordinator"
    }
}

/**
 * Pure parsing result — nothing on this struct depends on Android or Hilt,
 * so [parseEnvelope] and [diffAlarmWork] are unit-testable directly.
 */
internal data class ParsedEnvelope(
    val speak: String,
    val snapshot: List<Timer>?,
    val attachments: List<Attachment>,
)

internal sealed class AlarmWork {
    data class Schedule(val timer: Timer) : AlarmWork()
    data class Cancel(val id: String) : AlarmWork()
}

internal fun parseEnvelope(envelope: JSONObject): ParsedEnvelope {
    val speak = envelope.optString("speak").ifBlank { "Timer updated." }
    val snapshot = envelope.optJSONArray("timers")?.let { parseTimers(it) }
    val attachments = firstCreatedAttachment(envelope.optJSONArray("events"))
    return ParsedEnvelope(speak = speak, snapshot = snapshot, attachments = attachments)
}

/**
 * Diff previous and current lists to produce the alarm/notification work
 * the coordinator needs to do. Everything in `current` yields a Schedule
 * (new or unchanged — scheduling is idempotent via FLAG_UPDATE_CURRENT);
 * everything dropped from `previous` yields a Cancel.
 */
internal fun diffAlarmWork(previous: List<Timer>, current: List<Timer>): List<AlarmWork> {
    val previousIds = previous.map { it.id }.toSet()
    val currentIds = current.map { it.id }.toSet()
    val out = ArrayList<AlarmWork>(previous.size + current.size)
    for (id in previousIds - currentIds) out += AlarmWork.Cancel(id)
    for (timer in current) out += AlarmWork.Schedule(timer)
    return out
}

private fun firstCreatedAttachment(events: org.json.JSONArray?): List<Attachment> {
    if (events == null || events.length() == 0) return emptyList()
    for (i in 0 until events.length()) {
        val ev = events.optJSONObject(i) ?: continue
        if (ev.optString("kind") == "create") {
            val id = ev.optString("id")
            if (id.isNotBlank()) return listOf(Attachment.Timer(id))
        }
    }
    return emptyList()
}

private fun parseTimers(arr: org.json.JSONArray): List<Timer> {
    val out = ArrayList<Timer>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id").ifBlank { continue }
        val name = if (o.isNull("name")) null else o.optString("name").ifBlank { null }
        val end = o.optLong("end_ts_ms", 0L)
        val created = o.optLong("created_ts_ms", 0L)
        if (end <= 0L) {
            Log.w("TimerCoordinator", "skipping timer with non-positive end_ts_ms: $o")
            continue
        }
        out += Timer(id = id, name = name, endTsMs = end, createdTsMs = created)
    }
    return out
}

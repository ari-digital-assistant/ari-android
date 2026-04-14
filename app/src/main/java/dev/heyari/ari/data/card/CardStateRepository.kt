package dev.heyari.ari.data.card

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.notifications.AlertSpecCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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

private val Context.cardDataStore by preferencesDataStore(name = "ari_cards")

/**
 * Canonical Android-side card list. Single source of truth for the UI and
 * the alarm/notification layers.
 *
 * Skills are authoritative for their card sets; this repo mirrors what the
 * latest envelope said. [applyEnvelope] handles dismiss-then-upsert
 * semantics in one go so callers can't get the order wrong.
 *
 * Persistence is hand-rolled JSON (matching the Timer-era pattern; no
 * kotlinx.serialization in the project). Persisted so cards with active
 * countdowns survive process death and re-render correctly when the user
 * reopens the app.
 */
@Singleton
class CardStateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<List<Card>>(emptyList())
    val state: StateFlow<List<Card>> = _state.asStateFlow()

    // BootReceiver awaits this before reading; otherwise it would race the
    // initial DataStore load and silently re-schedule from an empty list.
    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val stored = context.cardDataStore.data
                .map { prefs -> prefs[KEY_CARDS] }
                .first()
            _state.value = stored?.let(::decode) ?: emptyList()
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()

    fun observe(cardId: String): Flow<Card?> =
        state.map { list -> list.firstOrNull { it.id == cardId } }

    /**
     * Apply an envelope's dismissals + upserts atomically. Dismiss first,
     * then upsert by id (existing entries with the same id are replaced).
     */
    fun applyEnvelope(dismissCardIds: List<String>, upsertCards: List<Card>) {
        val next = _state.value.toMutableList()
        if (dismissCardIds.isNotEmpty()) {
            next.removeAll { it.id in dismissCardIds }
        }
        for (card in upsertCards) {
            val idx = next.indexOfFirst { it.id == card.id }
            if (idx >= 0) next[idx] = card else next += card
        }
        _state.value = next
        persist(next)
    }

    /**
     * Insert a card without going through the skill. Debug hook only —
     * production callers must apply skill-emitted envelopes.
     */
    fun debugInsertCard(card: Card) {
        applyEnvelope(emptyList(), listOf(card))
    }

    /**
     * Remove a card by id. Used by [dev.heyari.ari.actions.CardExpiryReceiver]
     * after firing an `on_complete.alert` and from [BootReceiver] after
     * draining a missed countdown.
     */
    fun removeById(id: String) {
        val next = _state.value.filterNot { it.id == id }
        if (next.size != _state.value.size) {
            _state.value = next
            persist(next)
        }
    }

    private fun persist(list: List<Card>) {
        scope.launch {
            runCatching {
                context.cardDataStore.edit { it[KEY_CARDS] = encode(list) }
            }.onFailure { Log.w(TAG, "failed to persist cards", it) }
        }
    }

    private fun encode(list: List<Card>): String {
        val arr = JSONArray()
        for (c in list) arr.put(encodeCard(c))
        return arr.toString()
    }

    private fun decode(raw: String): List<Card> =
        runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Card>(arr.length())
            for (i in 0 until arr.length()) {
                decodeCard(arr.getJSONObject(i))?.let { out += it }
            }
            out
        }.getOrElse {
            Log.w(TAG, "discarding corrupt cards blob", it)
            emptyList()
        }

    private companion object {
        const val TAG = "CardState"
        val KEY_CARDS = stringPreferencesKey("cards_v1")
    }
}

// -- Encode -----------------------------------------------------------------

private fun encodeCard(c: Card): JSONObject = JSONObject().apply {
    put("id", c.id)
    put("skillId", c.skillId)
    put("title", c.title)
    putOrNull("subtitle", c.subtitle)
    putOrNull("body", c.body)
    putOrNull("icon", c.icon)
    putOrNull("countdownToTsMs", c.countdownToTsMs)
    putOrNull("startedAtTsMs", c.startedAtTsMs)
    putOrNull("progress", c.progress?.toDouble())
    put("accent", c.accent.name)
    put(
        "actions",
        JSONArray().apply {
            for (a in c.actions) put(encodeCardAction(a))
        },
    )
    c.onComplete?.let { put("onComplete", encodeOnComplete(it)) }
}

private fun encodeCardAction(a: CardAction): JSONObject = JSONObject().apply {
    put("id", a.id)
    put("label", a.label)
    putOrNull("utterance", a.utterance)
    put("style", a.style.name)
}

private fun encodeOnComplete(o: OnComplete): JSONObject = JSONObject().apply {
    o.alert?.let { put("alert", AlertSpecCodec.encodeObject(it)) }
    put("dismissCard", o.dismissCard)
}

private fun JSONObject.putOrNull(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

// -- Decode -----------------------------------------------------------------

private fun decodeCard(o: JSONObject): Card? {
    val id = o.optString("id").ifEmpty { return null }
    val skillId = o.optString("skillId")
    val title = o.optString("title")
    return Card(
        id = id,
        skillId = skillId,
        title = title,
        subtitle = o.optStringOrNull("subtitle"),
        body = o.optStringOrNull("body"),
        icon = o.optStringOrNull("icon"),
        countdownToTsMs = o.optLongOrNull("countdownToTsMs"),
        startedAtTsMs = o.optLongOrNull("startedAtTsMs"),
        progress = o.optDoubleOrNull("progress")?.toFloat(),
        accent = runCatching { Card.Accent.valueOf(o.optString("accent", "DEFAULT")) }
            .getOrDefault(Card.Accent.DEFAULT),
        actions = o.optJSONArray("actions")?.let(::decodeCardActions).orEmpty(),
        onComplete = o.optJSONObject("onComplete")?.let(::decodeOnComplete),
    )
}

private fun decodeCardActions(arr: JSONArray): List<CardAction> {
    val out = ArrayList<CardAction>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out += CardAction(
            id = o.optString("id"),
            label = o.optString("label"),
            utterance = o.optStringOrNull("utterance"),
            style = runCatching { CardAction.Style.valueOf(o.optString("style", "DEFAULT")) }
                .getOrDefault(CardAction.Style.DEFAULT),
        )
    }
    return out
}

private fun decodeOnComplete(o: JSONObject): OnComplete = OnComplete(
    alert = o.optJSONObject("alert")?.let { AlertSpecCodec.decodeObject(it) },
    dismissCard = o.optBoolean("dismissCard", true),
)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

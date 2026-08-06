package dev.heyari.ari.listening

import android.util.Log
import androidx.annotation.StringRes
import dev.heyari.ari.R
import org.json.JSONArray
import org.json.JSONException

/**
 * When Ari is allowed to have the microphone open.
 *
 * Always-on listening costs roughly 15% of a phone's battery a day. Every other
 * optimisation is a percentage off the cost of an hour of listening; this is the
 * one that changes how many hours there are.
 */
enum class ListeningMode(
    @StringRes val labelRes: Int,
    @StringRes val blurbRes: Int,
) {
    /** Listen whenever the service is up. The pre-modes behaviour, and the default. */
    ALWAYS(
        labelRes = R.string.listening_mode_always_label,
        blurbRes = R.string.listening_mode_always_blurb,
    ),

    /** Never open the mic on our own. Ari is summoned by hand — the composer mic
     *  button, the assist gesture, or a headset button. */
    NEVER(
        labelRes = R.string.listening_mode_never_label,
        blurbRes = R.string.listening_mode_never_blurb,
    ),

    /** Listen when ANY ticked [ListeningCondition] holds. */
    CUSTOM(
        labelRes = R.string.listening_mode_custom_label,
        blurbRes = R.string.listening_mode_custom_blurb,
    ),
    ;

    val slug: String get() = name.lowercase()

    companion object {
        /**
         * Unknown or absent slugs fall back to [ALWAYS]. An upgrade must not
         * silently stop listening on someone who never asked it to.
         */
        val DEFAULT = ALWAYS

        fun fromSlug(slug: String?): ListeningMode =
            entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}

/**
 * The conditions selectable under [ListeningMode.CUSTOM]. Any one of them being
 * true opens the mic — they are ORed, not ANDed, because "listen while I'm
 * driving OR at my desk" is what people actually mean.
 */
enum class ListeningCondition(
    @StringRes val labelRes: Int,
    @StringRes val blurbRes: Int,
) {
    SCREEN_ON(
        labelRes = R.string.listening_condition_screen_label,
        blurbRes = R.string.listening_condition_screen_blurb,
    ),
    CHARGING(
        labelRes = R.string.listening_condition_charging_label,
        blurbRes = R.string.listening_condition_charging_blurb,
    ),
    HEADSET(
        labelRes = R.string.listening_condition_headset_label,
        blurbRes = R.string.listening_condition_headset_blurb,
    ),
    SCHEDULE(
        labelRes = R.string.listening_condition_schedule_label,
        blurbRes = R.string.listening_condition_schedule_blurb,
    ),
    PLACE(
        labelRes = R.string.listening_condition_place_label,
        blurbRes = R.string.listening_condition_place_blurb,
    ),
    ;

    val slug: String get() = name.lowercase()

    companion object {
        fun fromSlug(slug: String?): ListeningCondition? =
            entries.firstOrNull { it.slug == slug }
    }
}

internal fun encodeConditions(conditions: Set<ListeningCondition>): String {
    val arr = JSONArray()
    // Sorted by declaration order so the stored blob is stable and a no-op save
    // doesn't churn the DataStore file.
    conditions.sortedBy { it.ordinal }.forEach { arr.put(it.slug) }
    return arr.toString()
}

/**
 * Unknown slugs are dropped rather than failing the whole set — a condition
 * removed in a later version shouldn't cost the user the other four.
 */
internal fun decodeConditions(raw: String?): Set<ListeningCondition> {
    if (raw.isNullOrBlank()) return emptySet()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length())
            .mapNotNull { ListeningCondition.fromSlug(arr.optString(it)) }
            .toSet()
    } catch (e: JSONException) {
        Log.w("ListeningCondition", "Corrupt condition store — dropping all conditions", e)
        emptySet()
    }
}

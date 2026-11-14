package dev.heyari.ari.data

import androidx.annotation.StringRes
import dev.heyari.ari.R
import uniffi.ari_ffi.FfiConversationLength

/**
 * How much of a conversation Ari keeps for follow-up questions, and how long
 * a silence it waits out before forgetting it. The engine owns the numbers
 * (`ari_engine::ConversationLength`); the blurbs repeat them for the user.
 */
enum class ConversationLength(
    @param:StringRes val titleRes: Int,
    @param:StringRes val blurbRes: Int,
    val ffi: FfiConversationLength,
) {
    SHORT(
        R.string.settings_conversation_length_short,
        R.string.settings_conversation_length_short_blurb,
        FfiConversationLength.SHORT,
    ),
    MEDIUM(
        R.string.settings_conversation_length_medium,
        R.string.settings_conversation_length_medium_blurb,
        FfiConversationLength.MEDIUM,
    ),
    LONG(
        R.string.settings_conversation_length_long,
        R.string.settings_conversation_length_long_blurb,
        FfiConversationLength.LONG,
    ),
    ;

    val slug: String get() = name.lowercase()

    companion object {
        /** Unknown or missing slugs get [SHORT], Ari's behaviour before the setting existed. */
        fun fromSlug(slug: String?): ConversationLength =
            entries.firstOrNull { it.slug == slug } ?: SHORT
    }
}

/**
 * Longest personality the settings field accepts. Matches the engine's
 * `ari_core::MAX_PERSONALITY_CHARS`, which cuts anything longer anyway.
 */
const val MAX_PERSONALITY_CHARS = 500

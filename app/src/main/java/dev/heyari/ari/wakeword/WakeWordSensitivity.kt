package dev.heyari.ari.wakeword

/**
 * User-adjustable wake word sensitivity. Overrides the per-model compile-time
 * cutoff and sliding window at runtime, because the right values are
 * environment-dependent — a single hardcoded threshold cannot simultaneously
 * serve a silent studio and a noisy family kitchen.
 *
 * Semantics follow the user's intuition: HIGH = fires more readily (lower
 * cutoff, shorter confirmation window), LOW = strictest (highest cutoff,
 * longest window). This matches how Alexa / Google Home label the same
 * setting. Easy to get backwards — don't.
 */
enum class WakeWordSensitivity(
    val probabilityCutoff: Float,
    val slidingWindowSize: Int,
    val displayName: String,
    val description: String,
) {
    HIGH(
        probabilityCutoff = 0.95f,
        slidingWindowSize = 5,
        displayName = "High",
        description = "Fires easily. Use only in very quiet rooms.",
    ),
    MEDIUM(
        probabilityCutoff = 0.985f,
        slidingWindowSize = 10,
        displayName = "Medium",
        description = "Recommended for most homes.",
    ),
    LOW(
        probabilityCutoff = 0.99f,
        slidingWindowSize = 14,
        displayName = "Low",
        description = "Stricter. Use if Ari wakes when she shouldn't.",
    );

    companion object {
        val DEFAULT = MEDIUM

        fun fromName(name: String?): WakeWordSensitivity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

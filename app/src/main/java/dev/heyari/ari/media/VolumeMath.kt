package dev.heyari.ari.media

import kotlin.math.roundToInt

/** Maps a 0..100 percentage to a 0..[maxVolume] stream index, clamped and rounded. */
fun percentToStreamVolume(percent: Int, maxVolume: Int): Int {
    val clamped = percent.coerceIn(0, 100)
    return (clamped / 100.0 * maxVolume).roundToInt().coerceIn(0, maxVolume)
}

package dev.heyari.ari.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/**
 * Material 3 has no success or warning role, so a permission tick, a
 * "recommended" stamp and a con bullet had nowhere to come from and were
 * written as literal hex. Literals don't survive a theme switch: the dark
 * green that reads well on a light card is nearly invisible on a dark one.
 *
 * Two variants each, chosen for contrast against surfaceVariant — the
 * container these actually appear on — rather than pulled from a palette.
 */
data class AriSemanticColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
)

val LightSemanticColors = AriSemanticColors(
    success = Color(0xFF2E7D32),
    warning = Color(0xFFF57C00),
    danger = Color(0xFFC62828),
)

val DarkSemanticColors = AriSemanticColors(
    success = Color(0xFF81C784),
    warning = Color(0xFFFFB74D),
    danger = Color(0xFFEF9A9A),
)

val LocalAriSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * Whichever of [preferred] or [fallback] actually reads against [background].
 *
 * Dynamic colour does not guarantee that a `*Container` role is dark in a dark
 * scheme. A Pixel on Android 17 hands back `tertiaryContainer` = #F0EFAB — a
 * light yellow — at night, while `primaryContainer` and `secondaryContainer`
 * are properly dark. So anything painted onto a container cannot reason from
 * the role name about which of an accent's two variants will show up: `error`
 * is a light pink in that scheme, and light pink on light yellow is invisible.
 *
 * Comparing measured contrast is the only thing that holds across schemes.
 * [fallback] should be the container's own `on*` role, which is the one colour
 * guaranteed to contrast with it.
 */
fun readableOn(background: Color, preferred: Color, fallback: Color): Color =
    if (contrastRatio(preferred, background) >= contrastRatio(fallback, background)) {
        preferred
    } else {
        fallback
    }

/** WCAG relative-luminance contrast ratio, 1.0 (identical) to 21.0 (max). */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

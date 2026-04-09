package dev.heyari.ari.voice

/**
 * Strips a leading wake phrase from a transcript so the engine never sees it
 * and so endpoint detection inside [dev.heyari.ari.stt.SpeechRecognizer] can
 * tell "user only said the wake phrase" apart from "user said something real".
 *
 * Patterns covered:
 *   - Optional opener: hey / ok / okay / hi / hello
 *   - Required name: ari / ary / arie / airy / harry / jarvis / jarviz
 *     (sherpa frequently mishears "ari" as "harry" or "airy" — list grows
 *     empirically as we see new transcription artefacts)
 *   - Trailing punctuation, comma, full stop, etc., is eaten
 *
 * Anything before the first match position is also dropped — sometimes the
 * pre-roll catches a stray ambient word and we don't want it polluting the
 * query either. If no wake phrase is found at all, returns the input trimmed.
 */
// Two-stage strip:
//   1. Try to match opener + name (the strict case).
//   2. If no name token was found, fall back to stripping a bare leading
//      opener ("ok", "okay", "hey", "hi", "hello") because sherpa sometimes
//      drops the name entirely on the way out (e.g. "ok ari what time" →
//      "Okay what time"). This is mildly risky if the user genuinely starts
//      a query with "ok" — accepted cost.
private val WAKE_PHRASE_REGEX = Regex(
    "^.*?\\b(?:hey|ok|okay|hi|hello)?\\s*(?:ari|ary|arie|arrie|airy|harry|hari|hairy|ori|orie|re|ray|rae|jarvis|jarviz)\\b[\\s,.!?:;]*",
    setOf(RegexOption.IGNORE_CASE),
)

private val LEADING_OPENER_REGEX = Regex(
    "^\\s*(?:hey|ok|okay|hi|hello)\\b[\\s,.!?:;]*",
    setOf(RegexOption.IGNORE_CASE),
)

fun stripWakePhrase(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val afterFull = WAKE_PHRASE_REGEX.replaceFirst(trimmed, "")
    if (afterFull != trimmed) return afterFull.trim()
    // Strict regex didn't match. Fall back to stripping a bare leading
    // opener — sherpa sometimes elides the wake-word name entirely, leaving
    // just "okay what time is it" with no recognisable "ari" token.
    return LEADING_OPENER_REGEX.replaceFirst(trimmed, "").trim()
}

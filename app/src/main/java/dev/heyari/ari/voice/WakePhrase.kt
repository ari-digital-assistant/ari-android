package dev.heyari.ari.voice

/**
 * Strips a leading wake phrase from a transcript so the engine never sees it
 * and so endpoint detection inside [dev.heyari.ari.stt.SpeechRecognizer] can
 * tell "user only said the wake phrase" apart from "user said something real".
 *
 * Patterns covered:
 *   - Optional opener: hey / ok / okay / hi / hello (+ per-locale additions)
 *   - Required name: ari / ary / arie / airy / harry / jarvis / jarviz
 *     (sherpa frequently mishears "ari" as "harry" or "airy" — list grows
 *     empirically as we see new transcription artefacts; per-locale additions
 *     get appended via [WakeMishearTable])
 *   - Trailing punctuation, comma, full stop, etc., is eaten
 *
 * Anything before the first match position is also dropped — sometimes the
 * pre-roll catches a stray ambient word and we don't want it polluting the
 * query either. If no wake phrase is found at all, returns the input trimmed.
 *
 * The wake-word model is always English regardless of Ari's active locale
 * (per the multi-language plan: per-language wake training is parked), so the
 * baseline patterns are English. Per-locale entries in [WakeMishearTable]
 * cover sherpa's locale-specific mishears (e.g. an Italian transcriber might
 * render "hey ari" as something English users never see).
 */

/**
 * Per-locale wake-phrase mishear additions. Empty for every locale at first;
 * populated as native speakers report sherpa mishearing the English wake
 * word in their language. Adding an entry is a one-line edit here — no FFI,
 * no engine change. The baseline English patterns (in [stripWakePhrase])
 * apply on top regardless.
 */
data class WakeMishearEntries(
    val extraOpeners: List<String> = emptyList(),
    val extraNames: List<String> = emptyList(),
)

object WakeMishearTable {
    private val TABLE: Map<String, WakeMishearEntries> = mapOf(
        "en" to WakeMishearEntries(),
        // Italian: no native-speaker mishears collected yet. Wake-word
        // model is still English ("hey ari"), so the baseline patterns
        // already cover the common case. Add Italian-specific
        // transcription artefacts here as they're reported.
        "it" to WakeMishearEntries(),
    )

    /** Returns the entries for [locale] (lowercased), or an empty entry if unknown. */
    fun forLocale(locale: String): WakeMishearEntries =
        TABLE[locale.lowercase()] ?: WakeMishearEntries()
}

// Two-stage strip:
//   1. Try to match opener + name (the strict case).
//   2. If no name token was found, fall back to stripping a bare leading
//      opener ("ok", "okay", "hey", "hi", "hello") because sherpa sometimes
//      drops the name entirely on the way out (e.g. "ok ari what time" →
//      "Okay what time"). This is mildly risky if the user genuinely starts
//      a query with "ok" — accepted cost.
private val BASE_OPENERS = listOf("hey", "ok", "okay", "hi", "hello")
private val BASE_NAMES = listOf(
    "ari", "ary", "arie", "arrie", "airy", "harry", "hari", "hairy",
    "ori", "orie", "re", "ray", "rae", "jarvis", "jarviz",
)

private fun buildWakeRegex(extraOpeners: List<String>, extraNames: List<String>): Regex {
    val openers = (BASE_OPENERS + extraOpeners).distinct().joinToString("|") { Regex.escape(it) }
    val names = (BASE_NAMES + extraNames).distinct().joinToString("|") { Regex.escape(it) }
    return Regex(
        "^.*?\\b(?:$openers)?\\s*(?:$names)\\b[\\s,.!?:;]*",
        setOf(RegexOption.IGNORE_CASE),
    )
}

private fun buildLeadingOpenerRegex(extraOpeners: List<String>): Regex {
    val openers = (BASE_OPENERS + extraOpeners).distinct().joinToString("|") { Regex.escape(it) }
    return Regex(
        "^\\s*(?:$openers)\\b[\\s,.!?:;]*",
        setOf(RegexOption.IGNORE_CASE),
    )
}

// Cache compiled regexes per locale — building a Regex from string source
// allocates and parses; this strip is on the hot transcription path.
private val regexCache = mutableMapOf<String, Pair<Regex, Regex>>()

private fun regexesFor(locale: String): Pair<Regex, Regex> {
    val key = locale.lowercase()
    regexCache[key]?.let { return it }
    val entries = WakeMishearTable.forLocale(key)
    val pair = buildWakeRegex(entries.extraOpeners, entries.extraNames) to
        buildLeadingOpenerRegex(entries.extraOpeners)
    regexCache[key] = pair
    return pair
}

/**
 * Strip the wake phrase from [text], using locale-specific mishears
 * stacked on top of the baseline English patterns. [locale] should be
 * an ISO 639-1 lowercase code (`"en"`, `"it"`, …); unknown locales fall
 * back to baseline-only behaviour.
 */
fun stripWakePhrase(text: String, locale: String = "en"): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val (full, leading) = regexesFor(locale)
    val afterFull = full.replaceFirst(trimmed, "")
    if (afterFull != trimmed) return afterFull.trim()
    // Strict regex didn't match. Fall back to stripping a bare leading
    // opener — sherpa sometimes elides the wake-word name entirely, leaving
    // just "okay what time is it" with no recognisable "ari" token.
    return leading.replaceFirst(trimmed, "").trim()
}

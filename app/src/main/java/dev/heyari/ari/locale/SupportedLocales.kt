package dev.heyari.ari.locale

import java.util.Locale

/**
 * The set of languages Ari currently has end-to-end support for —
 * STT, skill regexes, prompts, presentation strings. Extending this
 * list does not on its own enable a language; the corresponding
 * resources (Whisper-turbo download, prompt files, skill `lang:`
 * entries, `strings/{locale}.json`) must also be in place.
 *
 * Codes are ISO 639-1 lowercase. Region variants ("en-GB") collapse
 * to their language code on the host side (see [defaultFromSystem]).
 */
object SupportedLocales {
    /**
     * Currently supported language codes. Order is the canonical
     * picker order — English first as the universal fallback,
     * Italian as the first non-English implementation.
     */
    val codes: List<String> = listOf("en", "it")

    /** True if [code] is one of Ari's supported language codes. */
    fun isSupported(code: String): Boolean = code in codes

    /**
     * The system language code if Ari supports it, otherwise English.
     * Used as the default when no explicit locale has been set yet.
     *
     * Resolved **once per process** via `lazy` and cached for the
     * lifetime of the JVM. `Locale.getDefault()` is NOT a stable
     * read on Android during the first few hundred ms of startup —
     * Android applies per-app locale overrides asynchronously, and
     * different threads can observe different values until that
     * settles. Caching the first read sidesteps the race entirely:
     * whatever locale was active when the first caller asked is
     * what every subsequent caller (skill loader, recogniser config,
     * prompt selector, …) sees too.
     *
     * `Locale.getDefault().language` returns the ISO 639-1 lowercase
     * code on Android (with the legacy "iw"/"in"/"ji" overrides for
     * Hebrew/Indonesian/Yiddish — none of which are in our supported
     * set, so the legacy quirk doesn't bite us).
     */
    fun defaultFromSystem(): String = systemDefault

    private val systemDefault: String by lazy {
        val systemCode = Locale.getDefault().language
        if (isSupported(systemCode)) systemCode else "en"
    }
}

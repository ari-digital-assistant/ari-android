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
     * `Locale.getDefault().language` returns the ISO 639-1 lowercase
     * code on Android (with the legacy "iw"/"in"/"ji" overrides for
     * Hebrew/Indonesian/Yiddish — none of which are in our supported
     * set, so the legacy quirk doesn't bite us).
     */
    fun defaultFromSystem(): String {
        val systemCode = Locale.getDefault().language
        return if (isSupported(systemCode)) systemCode else "en"
    }
}

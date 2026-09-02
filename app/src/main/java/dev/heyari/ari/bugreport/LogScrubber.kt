package dev.heyari.ari.bugreport

import java.net.URI

/**
 * A value the app stored and therefore knows is sensitive, with the name it
 * should be replaced by.
 *
 * The label is the point. `<redacted: home-assistant_token>` still tells a
 * maintainer which credential was in play at that line, which a row of
 * asterisks does not.
 */
data class KnownSecret(val label: String, val value: String)

/**
 * Removes credentials and personal details from a log before it leaves the
 * device.
 *
 * Two passes, in this order. First the values the app actually holds —
 * [SecretStore][dev.heyari.ari.data.SecretStore] entries, configured
 * endpoints, saved place names — because those are exact and can be redacted
 * with certainty. Then a short list of patterns for things the app never
 * stored but a log can still carry.
 *
 * Deliberately conservative. Over-eager scrubbing destroys the diagnostic
 * value that made us collect the log in the first place: a rule that ate
 * router confidence scores or timestamps would leave a redacted log nobody can
 * debug from. Bare local phone numbers and unrecognised addresses will survive
 * this, which is exactly why a scrubbed log still goes to private storage and
 * never into a public issue, and why the report screen offers a separate
 * private note rather than asking people to trust the scrubber with free text.
 */
class LogScrubber(knownSecrets: List<KnownSecret>) {

    // Longest first. A short secret that happens to be a substring of a longer
    // one would otherwise chop the longer one in half and leave its tail in
    // the clear.
    private val known: List<KnownSecret> = knownSecrets
        .flatMap { listOf(it) + hostOf(it) }
        .filter { it.value.length >= MIN_LENGTH }
        .distinctBy { it.value }
        .sortedByDescending { it.value.length }

    fun scrub(text: String): String {
        var out = text
        for (secret in known) {
            out = out.replace(secret.value, redacted(secret.label))
        }
        for (rule in RULES) {
            out = rule.applyTo(out)
        }
        return out
    }

    /**
     * A stored endpoint is usually logged as a full URL, but not always — the
     * bare host turns up in DNS failures and certificate errors on its own.
     */
    private fun hostOf(secret: KnownSecret): List<KnownSecret> {
        if (!secret.value.startsWith("http://") && !secret.value.startsWith("https://")) {
            return emptyList()
        }
        val host = runCatching { URI(secret.value).host }.getOrNull() ?: return emptyList()
        return listOf(KnownSecret(secret.label, host))
    }

    private class Rule(
        val label: String,
        val regex: Regex,
        /**
         * Which capture group holds the sensitive part. Zero redacts the whole
         * match. Anything else must be the final group in the pattern, because
         * everything from its start to the end of the match is replaced —
         * that is what keeps `Authorization:` readable while its value goes.
         */
        val redactGroup: Int = 0,
    ) {
        fun applyTo(text: String): String = regex.replace(text) { match ->
            val token = redacted(label)
            if (redactGroup == 0) return@replace token
            val group = match.groups[redactGroup] ?: return@replace match.value
            match.value.substring(0, group.range.first - match.range.first) + token
        }
    }

    private companion object {
        // Below this, a stored value is more likely to be a locale or a unit
        // than a credential, and redacting "en" everywhere would be worse than
        // useless.
        const val MIN_LENGTH = 8

        fun redacted(label: String) = "<redacted: $label>"

        val RULES = listOf(
            Rule("email", Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")),

            Rule("jwt", Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+""")),

            // Keeps the field name so the line still says what was missing.
            // The optional scheme word is part of group 2 on purpose: in
            // `Authorization: Bearer ghp_...` the secret is the third token,
            // and a rule that stopped at "Bearer" would redact the scheme and
            // publish the credential.
            Rule(
                "credential",
                Regex(
                    """(?i)\b(bearer|token|api[_-]?key|apikey|password|secret|authorization)\b""" +
                        """(["']?\s*[:=]\s*["']?(?:(?:bearer|basic)\s+)?)([^\s"',}]+)"""
                ),
                redactGroup = 3,
            ),

            // A scheme with no field name in front of it, as logged by most
            // HTTP clients. Runs after the rule above so it cannot fire a
            // second time on what that one already redacted.
            Rule(
                "credential",
                Regex("""(?i)\b(?:bearer|basic)\s+([A-Za-z0-9._~+/=-]{8,})"""),
                redactGroup = 1,
            ),

            Rule("ip address", Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b(?::\d{1,5})?""")),

            Rule("mac address", Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")),

            // Only with an explicit country prefix. Eight bare digits is also
            // a timestamp, a byte count and a version code, and redacting
            // those would gut the log.
            Rule("phone number", Regex("""\+\d[\d\s-]{7,}\d""")),

            // A pair, never a lone decimal — routing confidences are decimals
            // and they are frequently the whole reason the log was collected.
            Rule(
                "coordinates",
                Regex("""-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}"""),
            ),
        )
    }
}

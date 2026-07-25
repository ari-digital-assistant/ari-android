package dev.heyari.ari.deeplink

import dev.heyari.ari.ui.Routes
import java.net.URI

/**
 * Maps a `heyari.dev/skills/...` App-Link URL to an in-app navigation route.
 *
 * - `https://heyari.dev/skills/<id>` → the browse-source skill detail for `<id>`
 * - `https://heyari.dev/skills` or `/skills/` → the in-app skills list
 * - anything else (wrong host, non-skills path, unparseable) → null (don't navigate)
 *
 * Pure and Android-free (takes a String, not android.net.Uri) so it's unit-testable
 * on the JVM. MainActivity passes `intent.data?.toString()`.
 */
fun skillDeepLinkRoute(url: String?): String? {
    val uri = try { URI(url ?: return null) } catch (e: Exception) { return null }
    if (uri.scheme?.equals("https", ignoreCase = true) != true) return null
    if (!uri.host.equals("heyari.dev", ignoreCase = true)) return null
    val path = uri.path ?: return null
    if (path != "/skills" && !path.startsWith("/skills/")) return null

    // Strip "/skills" then any leading/trailing slash → "" (bare) or the id.
    val id = path.removePrefix("/skills").trim('/')
    return when {
        id.isEmpty() -> Routes.skills()
        !id.contains('/') && id.matches(Regex("[a-z0-9.-]+")) -> Routes.skillDetail(id, "browse")
        else -> Routes.skills() // malformed extra segments → safe fallback to the list
    }
}

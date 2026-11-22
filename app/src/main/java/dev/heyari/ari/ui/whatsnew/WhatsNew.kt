package dev.heyari.ari.ui.whatsnew

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import dev.heyari.ari.R

/**
 * The release notes shown once, on the first launch after an update, so
 * testers hear what changed without an email. Rewrite them for each release:
 * a few lines a user cares about, not the changelog. They show only when
 * [VERSION] is the build's versionName, so a release that forgets to rewrite
 * them shows nothing rather than the previous release's news.
 */
internal object WhatsNew {
    const val VERSION = "0.2.2"

    val items = listOf(
        WhatsNewItem(Icons.Default.FormatBold, R.string.whats_new_0_2_2_formatting_title, R.string.whats_new_0_2_2_formatting_body),
        WhatsNewItem(Icons.Default.WbSunny, R.string.whats_new_0_2_2_cards_title, R.string.whats_new_0_2_2_cards_body),
        WhatsNewItem(Icons.Default.DarkMode, R.string.whats_new_0_2_2_black_title, R.string.whats_new_0_2_2_black_body),
        WhatsNewItem(Icons.Default.Build, R.string.whats_new_0_2_2_fixes_title, R.string.whats_new_0_2_2_fixes_body),
    )

    /**
     * Skill changes reach a phone through the home screen's update banner, not
     * with the app. True when some of [items] arrive that way, so the dialog
     * says where to get them.
     */
    const val NEEDS_SKILL_UPDATES = false

    /** Every change in every release, for whoever wants more than these few lines. */
    const val CHANGELOG_URL = "https://github.com/ari-digital-assistant/ari-android/blob/main/CHANGELOG.md"
}

internal data class WhatsNewItem(
    val icon: ImageVector,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
)

/**
 * Whether to show the notes now. Only after an update ([updated] is false on a
 * fresh install, which gets onboarding instead), only once per versionCode, and
 * only when the notes were written for this versionName.
 */
internal fun shouldShowWhatsNew(
    seenVersionCode: Long,
    versionCode: Long,
    versionName: String,
    notesVersion: String,
    updated: Boolean,
): Boolean = updated && seenVersionCode < versionCode && versionName == notesVersion

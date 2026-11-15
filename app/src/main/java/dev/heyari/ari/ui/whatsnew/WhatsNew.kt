package dev.heyari.ari.ui.whatsnew

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Translate
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
    const val VERSION = "0.1.2"

    val items = listOf(
        WhatsNewItem(Icons.Default.Translate, R.string.whats_new_0_1_2_italian_title, R.string.whats_new_0_1_2_italian_body),
        WhatsNewItem(Icons.Default.Directions, R.string.whats_new_0_1_2_directions_title, R.string.whats_new_0_1_2_directions_body),
        WhatsNewItem(Icons.Default.Home, R.string.whats_new_0_1_2_home_title, R.string.whats_new_0_1_2_home_body),
    )

    /**
     * Skill changes reach a phone through the home screen's update banner, not
     * with the app. True when some of [items] arrive that way, so the dialog
     * says where to get them.
     */
    const val NEEDS_SKILL_UPDATES = true
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

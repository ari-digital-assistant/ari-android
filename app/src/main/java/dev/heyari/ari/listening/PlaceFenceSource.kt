package dev.heyari.ari.listening

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import dev.heyari.ari.R

/**
 * Which fences decide whether the device is at a listening place.
 *
 * Play Services geofencing is the better mechanism — it has dwell handling,
 * batching and power management the platform's proximity alerts do not. But on
 * GrapheneOS it runs inside sandboxed Play, where two things go wrong: the fix
 * underneath it is vague enough that a 100m home circle never fires reliably,
 * and Ari's process is killed outright whenever gmscompat's own process dies.
 *
 * So on that one platform the choice is worth offering, and worth defaulting to
 * [BOTH]: two mechanisms watching the same circle, either of which may be the
 * one that notices.
 */
enum class PlaceFenceSource(
    @StringRes val labelRes: Int,
    @StringRes val blurbRes: Int,
) {
    /** Play Services geofencing alone. What every build did before this existed. */
    PLAY(
        labelRes = R.string.place_fences_play_label,
        blurbRes = R.string.place_fences_play_blurb,
    ),

    /** The platform's own proximity alerts alone, with no Play Services involved. */
    PLATFORM(
        labelRes = R.string.place_fences_platform_label,
        blurbRes = R.string.place_fences_platform_blurb,
    ),

    /** Register both and believe whichever one speaks first. */
    BOTH(
        labelRes = R.string.place_fences_both_label,
        blurbRes = R.string.place_fences_both_blurb,
    ),
    ;

    val slug: String get() = name.lowercase()

    val usesPlay: Boolean get() = this == PLAY || this == BOTH
    val usesPlatform: Boolean get() = this == PLATFORM || this == BOTH

    companion object {
        /**
         * [BOTH] where Play Services is sandboxed, [PLAY] everywhere else.
         *
         * An ordinary device has no reason to pay for two sets of fences: Play
         * Services there is a system app with a good fix behind it, and the
         * second set would only ever agree with it. The default is deliberately
         * not a stored value, so a device that later stops being one or starts
         * being the other picks up the right answer on its own.
         */
        fun defaultFor(context: Context): PlaceFenceSource =
            if (playServicesAreSandboxed(context)) BOTH else PLAY

        fun fromSlug(slug: String?, fallback: PlaceFenceSource): PlaceFenceSource =
            entries.firstOrNull { it.slug == slug } ?: fallback
    }
}

/**
 * Whether Play Services here is GrapheneOS's sandboxed build rather than the
 * system one.
 *
 * Asked by package rather than by fingerprint or build tags, which GrapheneOS
 * keeps deliberately close to stock. The question that actually matters isn't
 * "is this GrapheneOS" anyway — it's "is Play Services something that can be
 * killed and take Ari with it", and the presence of the compatibility layer is
 * exactly that.
 *
 * Requires the matching `<queries>` entry in the manifest; without it Android
 * 11+ answers "not installed" for every package Ari hasn't declared.
 */
fun playServicesAreSandboxed(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(GMSCOMPAT_PACKAGE, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

const val GMSCOMPAT_PACKAGE = "app.grapheneos.gmscompat"

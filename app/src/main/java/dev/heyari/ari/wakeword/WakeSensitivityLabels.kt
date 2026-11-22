package dev.heyari.ari.wakeword

import androidx.annotation.StringRes
import dev.heyari.ari.R
import uniffi.ari_ffi.FfiWakeSensitivity

// The levels themselves, and the rule that HIGH fires most readily and LOW is
// strictest, live in the engine (ari-voice's WakeSensitivity). Only their
// wording is the app's.

@get:StringRes
val FfiWakeSensitivity.displayNameRes: Int
    get() = when (this) {
        FfiWakeSensitivity.HIGH -> R.string.wakeword_sensitivity_high_label
        FfiWakeSensitivity.MEDIUM -> R.string.wakeword_sensitivity_medium_label
        FfiWakeSensitivity.LOW -> R.string.wakeword_sensitivity_low_label
    }

@get:StringRes
val FfiWakeSensitivity.descriptionRes: Int
    get() = when (this) {
        FfiWakeSensitivity.HIGH -> R.string.wakeword_sensitivity_high_description
        FfiWakeSensitivity.MEDIUM -> R.string.wakeword_sensitivity_medium_description
        FfiWakeSensitivity.LOW -> R.string.wakeword_sensitivity_low_description
    }

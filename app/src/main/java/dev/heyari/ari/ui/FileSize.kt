package dev.heyari.ari.ui

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A byte count for display, in the user's locale and in decimal units
 * (1 MB = 1,000,000 bytes) so it matches the sizes quoted in model
 * descriptions. Android's formatter has used decimal units since API 26.
 */
@Composable
fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

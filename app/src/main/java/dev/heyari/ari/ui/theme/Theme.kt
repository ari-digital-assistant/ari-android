package dev.heyari.ari.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Phones without dynamic colour (Android 10 and 11) get these. They are the
// Material 3 tonal-spot schemes for the logo's orange, #FE8823, generated with
// Google's material-color-utilities: the same algorithm Android 12+ runs on a
// wallpaper, so both paths produce the same kind of scheme. Every role is set,
// because any role left out falls back to Material's baseline purple.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB786),
    onPrimary = Color(0xFF502400),
    primaryContainer = Color(0xFF6E390D),
    onPrimaryContainer = Color(0xFFFFDCC6),
    inversePrimary = Color(0xFF8A5023),
    secondary = Color(0xFFE4BFA8),
    onSecondary = Color(0xFF422B1B),
    secondaryContainer = Color(0xFF5B4130),
    onSecondaryContainer = Color(0xFFFFDCC6),
    tertiary = Color(0xFFC9CA94),
    onTertiary = Color(0xFF31320A),
    tertiaryContainer = Color(0xFF48491F),
    onTertiaryContainer = Color(0xFFE5E6AE),
    background = Color(0xFF19120D),
    onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF19120D),
    onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD7C3B7),
    surfaceTint = Color(0xFFFFB786),
    inverseSurface = Color(0xFFF0DFD7),
    inverseOnSurface = Color(0xFF382E29),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9F8D83),
    outlineVariant = Color(0xFF52443C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF413731),
    surfaceContainer = Color(0xFF261E19),
    surfaceContainerHigh = Color(0xFF312823),
    surfaceContainerHighest = Color(0xFF3D332D),
    surfaceContainerLow = Color(0xFF221A15),
    surfaceContainerLowest = Color(0xFF140D08),
    surfaceDim = Color(0xFF19120D),
    primaryFixed = Color(0xFFFFDCC6),
    primaryFixedDim = Color(0xFFFFB786),
    onPrimaryFixed = Color(0xFF311400),
    onPrimaryFixedVariant = Color(0xFF6E390D),
    secondaryFixed = Color(0xFFFFDCC6),
    secondaryFixedDim = Color(0xFFE4BFA8),
    onSecondaryFixed = Color(0xFF2B1708),
    onSecondaryFixedVariant = Color(0xFF5B4130),
    tertiaryFixed = Color(0xFFE5E6AE),
    tertiaryFixedDim = Color(0xFFC9CA94),
    onTertiaryFixed = Color(0xFF1C1D00),
    onTertiaryFixedVariant = Color(0xFF48491F),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF8A5023),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC6),
    onPrimaryContainer = Color(0xFF6E390D),
    inversePrimary = Color(0xFFFFB786),
    secondary = Color(0xFF755846),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC6),
    onSecondaryContainer = Color(0xFF5B4130),
    tertiary = Color(0xFF5F6134),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5E6AE),
    onTertiaryContainer = Color(0xFF48491F),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A15),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF4DED3),
    onSurfaceVariant = Color(0xFF52443C),
    surfaceTint = Color(0xFF8A5023),
    inverseSurface = Color(0xFF382E29),
    inverseOnSurface = Color(0xFFFEEDE5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF84746A),
    outlineVariant = Color(0xFFD7C3B7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceContainer = Color(0xFFFCEBE2),
    surfaceContainerHigh = Color(0xFFF6E5DC),
    surfaceContainerHighest = Color(0xFFF0DFD7),
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE7D7CE),
    primaryFixed = Color(0xFFFFDCC6),
    primaryFixedDim = Color(0xFFFFB786),
    onPrimaryFixed = Color(0xFF311400),
    onPrimaryFixedVariant = Color(0xFF6E390D),
    secondaryFixed = Color(0xFFFFDCC6),
    secondaryFixedDim = Color(0xFFE4BFA8),
    onSecondaryFixed = Color(0xFF2B1708),
    onSecondaryFixedVariant = Color(0xFF5B4130),
    tertiaryFixed = Color(0xFFE5E6AE),
    tertiaryFixedDim = Color(0xFFC9CA94),
    onTertiaryFixed = Color(0xFF1C1D00),
    onTertiaryFixedVariant = Color(0xFF48491F),
)

@Composable
fun AriTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalAriSemanticColors provides
            if (darkTheme) DarkSemanticColors else LightSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

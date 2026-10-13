package app.floatdeck.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * FloatDeck brand theme (Material 3 Expressive, brand orange).
 *
 * Both schemes derive from the app icon palette: coral #F96A3E + yellow #FFD908.
 * Primary is deepened to #B03A12 in light mode and warmed to #EE7545 in dark
 * mode for readable contrast.
 * Follows the system dark setting; every screen color reads from ColorScheme
 * roles, shared across orientations, phones and tablets.
 */

private val LightColors =
    lightColorScheme(
        primary = Color(0xFFB03A12),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBD1),
        onPrimaryContainer = Color(0xFF460D00),
        secondaryContainer = Color(0xFFF6DED1),
        onSecondaryContainer = Color(0xFF4F1E0A),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        background = Color(0xFFFFF8F5),
        onBackground = Color(0xFF231914),
        surface = Color(0xFFFFF8F5),
        onSurface = Color(0xFF231914),
        surfaceVariant = Color(0xFFF3DED6),
        onSurfaceVariant = Color(0xFF85736B),
        outlineVariant = Color(0xFFE8D9D1),
        surfaceContainerLow = Color(0xFFFAF0EB),
        surfaceContainer = Color(0xFFF6EAE4),
        surfaceContainerHigh = Color(0xFFF0E3DC),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFEE7545),
        onPrimary = Color(0xFF5C1904),
        primaryContainer = Color(0xFF82300C),
        onPrimaryContainer = Color(0xFFFFDBD1),
        secondaryContainer = Color(0xFF3E2C21),
        onSecondaryContainer = Color(0xFFF6DED1),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        background = Color(0xFF1B110D),
        onBackground = Color(0xFFF1DFD8),
        surface = Color(0xFF1B110D),
        onSurface = Color(0xFFF1DFD8),
        surfaceVariant = Color(0xFF52443B),
        onSurfaceVariant = Color(0xFFD6C2B8),
        outlineVariant = Color(0xFF52423A),
        surfaceContainerLow = Color(0xFF231812),
        surfaceContainer = Color(0xFF271C16),
        surfaceContainerHigh = Color(0xFF332620),
    )

/** App theme: follows the system dark mode by default. */
@Composable
fun FloatDeckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

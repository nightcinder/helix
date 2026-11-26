package com.nightcinder.helix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cockpit Dark Theme Palette
val CyanPrimary = Color(0xFF00E5FF)
val CyanPrimaryContainer = Color(0xFF004D56)
val OnCyanPrimary = Color(0xFF001F24)
val OnCyanPrimaryContainer = Color(0xFF80F2FF)

val EmeraldAccent = Color(0xFF00E676)
val EmeraldContainer = Color(0xFF004D27)
val AmberAccent = Color(0xFFFFB300)
val AmberContainer = Color(0xFF5A3E00)
val RoseError = Color(0xFFFF5252)
val RoseErrorContainer = Color(0xFF5A0000)

val CockpitBackground = Color(0xFF0A0F14)
val CockpitSurface = Color(0xFF101720)
val CockpitSurfaceVariant = Color(0xFF182230)
val CockpitSurfaceBright = Color(0xFF223042)
val CockpitOnBackground = Color(0xFFE2E8F0)
val CockpitOnSurface = Color(0xFFE2E8F0)
val CockpitOnSurfaceVariant = Color(0xFF94A3B8)
val CockpitOutline = Color(0xFF334155)

private val CockpitDarkColorScheme: ColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = OnCyanPrimary,
    primaryContainer = CyanPrimaryContainer,
    onPrimaryContainer = OnCyanPrimaryContainer,
    secondary = EmeraldAccent,
    onSecondary = Color(0xFF00391A),
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = Color(0xFF69F0AE),
    tertiary = AmberAccent,
    onTertiary = Color(0xFF3E2800),
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFFFFD54F),
    background = CockpitBackground,
    onBackground = CockpitOnBackground,
    surface = CockpitSurface,
    onSurface = CockpitOnSurface,
    surfaceVariant = CockpitSurfaceVariant,
    onSurfaceVariant = CockpitOnSurfaceVariant,
    surfaceBright = CockpitSurfaceBright,
    error = RoseError,
    onError = Color(0xFF410002),
    errorContainer = RoseErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = CockpitOutline,
)

@Composable
fun HelixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Project Helix is a dark-first homelab cockpit interface
    MaterialTheme(
        colorScheme = CockpitDarkColorScheme,
        content = content
    )
}

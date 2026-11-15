package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AtvColors = darkColorScheme(
    primary = Atv.Signal,
    onPrimary = Atv.OnSignal,
    primaryContainer = Atv.SignalSoft,
    onPrimaryContainer = Atv.Ink,
    secondary = Atv.InkMuted,
    onSecondary = Atv.OnFocus,
    secondaryContainer = Atv.Surface3,
    onSecondaryContainer = Atv.Ink,
    tertiary = Atv.Signal,
    background = Atv.Bg,
    onBackground = Atv.Ink,
    surface = Atv.Bg,
    onSurface = Atv.Ink,
    surfaceVariant = Atv.Surface1,
    onSurfaceVariant = Atv.InkMuted,
    surfaceContainerLowest = Atv.Bg,
    surfaceContainerLow = Atv.Surface1,
    surfaceContainer = Atv.Surface1,
    surfaceContainerHigh = Atv.Surface1,
    surfaceContainerHighest = Atv.Surface3,
    outline = Atv.InkFaint,
    outlineVariant = Atv.Surface3,
    error = Atv.Signal,
    onError = Atv.OnSignal,
)

private val AtvTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Inter),
        displayMedium = t.displayMedium.copy(fontFamily = Inter),
        displaySmall = t.displaySmall.copy(fontFamily = Inter),
        headlineLarge = t.headlineLarge.copy(fontFamily = Inter),
        headlineMedium = t.headlineMedium.copy(fontFamily = Inter),
        headlineSmall = t.headlineSmall.copy(fontFamily = Inter),
        titleLarge = t.titleLarge.copy(fontFamily = Inter),
        titleMedium = t.titleMedium.copy(fontFamily = Inter),
        titleSmall = t.titleSmall.copy(fontFamily = Inter),
        bodyLarge = t.bodyLarge.copy(fontFamily = Inter),
        bodyMedium = t.bodyMedium.copy(fontFamily = Inter),
        bodySmall = t.bodySmall.copy(fontFamily = Inter),
        labelLarge = t.labelLarge.copy(fontFamily = Inter),
        labelMedium = t.labelMedium.copy(fontFamily = Inter),
        labelSmall = t.labelSmall.copy(fontFamily = Inter),
    )
}

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AtvColors, typography = AtvTypography, content = content)
}

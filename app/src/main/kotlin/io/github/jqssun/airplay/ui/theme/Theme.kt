package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material is only used by the advanced settings, the logs and dialogs; the TV screens draw with
// the Atv tokens directly.
private val AtvColors = darkColorScheme(
    primary = Atv.Pepe,
    onPrimary = Atv.OnPepe,
    primaryContainer = Atv.ButacaAlta,
    onPrimaryContainer = Atv.Crema,
    inversePrimary = Atv.PepeDark,
    secondary = Atv.Palomita,
    onSecondary = Atv.OnPalomita,
    secondaryContainer = Atv.Pasillo,
    onSecondaryContainer = Atv.Crema,
    tertiary = Atv.Palomita,
    onTertiary = Atv.OnPalomita,
    background = Atv.Noche,
    onBackground = Atv.Crema,
    surface = Atv.Noche,
    onSurface = Atv.Crema,
    surfaceVariant = Atv.Butaca,
    onSurfaceVariant = Atv.Lavanda,
    surfaceTint = Color.Transparent,
    inverseSurface = Atv.Crema,
    inverseOnSurface = Atv.PepeInk,
    surfaceContainerLowest = Atv.Deep,
    surfaceContainerLow = Atv.Butaca,
    surfaceContainer = Atv.Butaca,
    surfaceContainerHigh = Atv.ButacaAlta,
    surfaceContainerHighest = Atv.Pasillo,
    outline = Atv.Faint,
    outlineVariant = Atv.Pasillo,
    error = Atv.Stop,
    onError = Color.White,
    scrim = Atv.Scrim,
)

private fun TextStyle.title() = copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold)
// nothing under 13sp on the TV
private fun TextStyle.text(weight: FontWeight = FontWeight.Medium) = copy(fontFamily = Nunito, fontWeight = weight).let {
    if (it.fontSize < 13.sp) it.copy(fontSize = 13.sp, lineHeight = 18.sp) else it
}

private val AtvTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.title(),
        displayMedium = t.displayMedium.title(),
        displaySmall = t.displaySmall.title(),
        headlineLarge = t.headlineLarge.title(),
        headlineMedium = t.headlineMedium.title(),
        headlineSmall = t.headlineSmall.title(),
        titleLarge = t.titleLarge.title(),
        titleMedium = t.titleMedium.title(),
        titleSmall = t.titleSmall.title(),
        bodyLarge = t.bodyLarge.text(),
        bodyMedium = t.bodyMedium.text(),
        bodySmall = t.bodySmall.text(),
        labelLarge = t.labelLarge.text(FontWeight.ExtraBold),
        labelMedium = t.labelMedium.text(FontWeight.Bold),
        labelSmall = t.labelSmall.text(FontWeight.Bold),
    )
}

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AtvColors, typography = AtvTypography, content = content)
}

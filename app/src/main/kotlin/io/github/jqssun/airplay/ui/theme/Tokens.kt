package io.github.jqssun.airplay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R

/**
 * Design tokens of the "Noche de peli en el sofá" look: a dark night room, solid rounded cards,
 * and whatever the remote focuses painted in popcorn yellow ([Palomita]).
 *
 * The UI canvas is 960x540 dp (1920x1080 at 320 dpi); the prototypes' px map 1:1 to dp.
 */
object Atv {
    // ---------- palette ----------
    /** Noche: screen background. */
    val Noche = Color(0xFF16122B)
    /** Noche profunda: screensaver background. */
    val Deep = Color(0xFF0B0916)
    /** Butaca: cards, setting rows, chips. */
    val Butaca = Color(0xFF231D3D)
    /** Butaca alta: unfocused buttons, artwork placeholder. */
    val ButacaAlta = Color(0xFF2E2650)
    /** Pasillo: borders, toggle off, progress track, icon tiles. */
    val Pasillo = Color(0xFF3A3160)
    /** Suelo: the ellipse painted under Pepe. */
    val Floor = Color(0xFF221B3D)
    /** Palomita: focus fill, progress, eyebrows. */
    val Palomita = Color(0xFFFFC94A)
    /** Ink on [Palomita]. */
    val OnPalomita = Color(0xFF2A1A00)
    /** Pale popcorn: focused icon tiles, Pepe's second knob. */
    val PalomitaSoft = Color(0xFFFFE2A3)
    /** Pepe: brand coral, toggle on, live chip. */
    val Pepe = Color(0xFFFF6B5B)
    /** Ink on [Pepe]. */
    val OnPepe = Color(0xFF2A0F0B)
    /** Pepe's feet and grill. */
    val PepeDark = Color(0xFFD9483B)
    /** Pepe's screen (cream). */
    val PepeScreen = Color(0xFFFFF3DE)
    /** Pepe's face ink; also the ink on [Crema] surfaces (focused rows, speech bubbles). */
    val PepeInk = Color(0xFF2A1633)
    /** Stop: destructive buttons (white text keeps 4.6:1). */
    val Stop = Color(0xFFD63B3B)
    /** Red text on a focused (cream) destructive button. */
    val StopInk = Color(0xFFB42C2C)
    /** Crema: primary text. */
    val Crema = Color(0xFFFFF6EA)
    /** Lavanda: secondary text and idle icons. */
    val Lavanda = Color(0xFFC8BFDA)
    /** Faint text (screensaver, hints). */
    val Faint = Color(0xFF9C93B5)
    /** Secondary text on [Crema] surfaces (focused row subtitles). */
    val OnCremaMuted = Color(0xFF5C4F6E)
    /** Dim layer over video or a backdrop (connection lost, stop confirmation). */
    val Scrim = Color(0xFF0A0816).copy(alpha = 0.75f)
    /** Band behind the mirroring overlay's bottom controls. */
    val ScrimBand = Color(0xFF120E24).copy(alpha = 0.86f)

    // ---------- semantic aliases (screens use these) ----------
    val Bg = Noche
    val Ink = Crema
    val InkMuted = Lavanda
    val InkFaint = Faint
    /** Ink on the focused (cream) row surface. */
    val OnFocus = PepeInk
    val OnFocusMuted = OnCremaMuted

    // ---------- spacing ----------
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space8 = 32.dp
    val Space10 = 40.dp
    val Space12 = 48.dp
    val SafeX = 48.dp
    val SafeY = 27.dp

    // ---------- radii and shapes ----------
    val RadiusSm = 12.dp
    /** Step chips, icon tiles. */
    val RadiusTile = 16.dp
    /** Setting rows, speech bubbles. */
    val RadiusRow = 18.dp
    val RadiusMd = 20.dp
    /** Device rows, PIN digits. */
    val RadiusCard = 22.dp
    /** Panels, artwork. */
    val RadiusLg = 28.dp
    val ShapeSm = RoundedCornerShape(RadiusSm)
    val ShapeTile = RoundedCornerShape(RadiusTile)
    val ShapeRow = RoundedCornerShape(RadiusRow)
    val ShapeMd = RoundedCornerShape(RadiusMd)
    val ShapeCard = RoundedCornerShape(RadiusCard)
    val ShapeLg = RoundedCornerShape(RadiusLg)
    val ShapePill = RoundedCornerShape(percent = 50)

    // ---------- focus ----------
    /** Scale of a focused button or card. Rows don't scale: the ring and cream fill are enough. */
    const val ScaleFocus = 1.04f
    const val ScalePressed = 0.97f
    /** Soft glow ring around a focused pill button / card (Palomita at 22%). */
    val RingButton = 5.dp
    /** Glow ring around a focused round icon button. */
    val RingRound = 6.dp
    /** Solid Palomita ring around a focused setting row. */
    val RingRow = 4.dp
    const val RingGlowAlpha = 0.22f
    const val RingDangerAlpha = 0.35f
}

// one instance of a variable font per weight (API 26+; older APIs get the file's default instance)
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Fredoka (variable, wght 300-700): titles, display, the clock, Pepe's bubbles. */
val Fredoka = FontFamily(
    variable(R.font.fredoka, 400),
    variable(R.font.fredoka, 500),
    variable(R.font.fredoka, 600),
    variable(R.font.fredoka, 700),
)

/** Nunito (variable, wght 200-1000): body text, buttons, captions. */
val Nunito = FontFamily(
    variable(R.font.nunito, 400),
    variable(R.font.nunito, 500),
    variable(R.font.nunito, 600),
    variable(R.font.nunito, 700),
    variable(R.font.nunito, 800),
)

/** Type scale from the prototypes (sizes in sp = px at 960x540). Nothing goes under 13sp. */
object AtvType {
    private fun style(family: FontFamily, size: Int, line: Int, weight: Int, tracking: Float = 0f) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = FontWeight(weight),
        letterSpacing = tracking.sp,
    )

    // Fredoka
    /** Screensaver clock. */
    val Clock = style(Fredoka, 112, 116, 500)
    /** PIN digits. */
    val Pin = style(Fredoka, 76, 84, 700)
    /** Home title ("¿Ponemos una peli?"). */
    val Display = style(Fredoka, 46, 52, 600)
    /** Screen titles (audio, no network, devices, settings). */
    val Title = style(Fredoka, 36, 42, 600)
    /** Titles inside panels and dialogs (request, stop, lost). */
    val SectionTitle = style(Fredoka, 32, 38, 600)
    /** Lead-in lines ("¿Hola? ¿Sigues ahí?"). */
    val Headline = style(Fredoka, 22, 28, 600)
    /** Wordmark, overlay captions ("¡Que aproveche!"). */
    val HeadlineSmall = style(Fredoka, 20, 26, 600)
    /** Pepe's speech bubble. */
    val Bubble = style(Fredoka, 18, 24, 600)

    // Nunito
    val BodyLarge = style(Nunito, 18, 26, 500)
    val Body = style(Nunito, 17, 25, 500)
    val Callout = style(Nunito, 16, 23, 500)
    val Caption = style(Nunito, 14, 20, 500)
    val CaptionStrong = style(Nunito, 14, 20, 700)
    /** Timers, version line, progress labels. */
    val Footnote = style(Nunito, 13, 18, 700)
    /** Uppercase kicker above titles; paint it [Atv.Palomita]. */
    val Eyebrow = style(Nunito, 13, 18, 800, 2f)
    val Button = style(Nunito, 17, 22, 800)
    val RowTitle = style(Nunito, 16, 22, 800)
    val RowSubtitle = style(Nunito, 13, 18, 500)
    val RowValue = style(Nunito, 15, 20, 800)
}

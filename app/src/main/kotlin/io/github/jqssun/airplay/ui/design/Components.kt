package io.github.jqssun.airplay.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType

// ---------- brand ----------

/** Pepe-TV glyph + "AirPlay TV" in Fredoka (top-left of the home screen). */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Mark(Modifier.size(30.dp))
        Text(
            stringResource(R.string.app_name),
            style = AtvType.HeadlineSmall.copy(letterSpacing = 0.2.sp),
            color = Atv.Ink,
            maxLines = 1,
        )
    }
}

/** The small Pepe TV glyph (`R.drawable.logo_mark`, 30x30 viewport). */
@Composable
fun Mark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_mark),
        contentDescription = null,
        modifier = modifier,
    )
}

// ---------- text helpers ----------

/** Uppercase yellow kicker above a title ("BUENAS NOCHES, FAMILIA"). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Atv.Palomita) {
    Text(text.uppercase(), style = AtvType.Eyebrow, color = color, modifier = modifier)
}

// ---------- status chip ----------

enum class ChipKind {
    /** Butaca + Pasillo border + Palomita dot with a soft ring ("Esperando · TV"). */
    WAITING,

    /** Like [WAITING] with a Lavanda dot ("Sin red", "Detenido"). */
    NEUTRAL,

    /** Pepe coral fill, dark ink and dot ("Duplicando · iPhone"). */
    LIVE,

    /** Butaca + 2dp coral border + coral icon ("Conexión perdida"). */
    ALERT,
}

/**
 * Pill-shaped status chip, 34dp high. [icon] replaces the dot (tinted Palomita, or OnPepe on
 * [ChipKind.LIVE]); on [ChipKind.ALERT] it defaults to a warning sign tinted coral.
 */
@Composable
fun StatusChip(text: String, kind: ChipKind, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val shape = Atv.ShapePill
    val surface = when (kind) {
        ChipKind.LIVE -> Modifier.card(shape, Atv.Pepe)
        ChipKind.ALERT -> Modifier.card(shape, Atv.Butaca, border = Atv.Pepe, borderWidth = 2.dp)
        ChipKind.WAITING, ChipKind.NEUTRAL -> Modifier.card(shape, Atv.Butaca, border = Atv.Pasillo)
    }
    val ink = if (kind == ChipKind.LIVE) Atv.OnPepe else Atv.Ink
    val loud = kind == ChipKind.LIVE || kind == ChipKind.ALERT
    Row(
        modifier = modifier.height(34.dp).then(surface).padding(start = Atv.Space3, end = Atv.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Atv.Space2),
    ) {
        when {
            kind == ChipKind.ALERT ->
                Icon(icon ?: Icons.Rounded.Warning, null, tint = Atv.Pepe, modifier = Modifier.size(16.dp))
            icon != null ->
                Icon(icon, null, tint = if (kind == ChipKind.LIVE) Atv.OnPepe else Atv.Palomita, modifier = Modifier.size(16.dp))
            else -> ChipDot(
                color = when (kind) {
                    ChipKind.LIVE -> Atv.OnPepe
                    ChipKind.NEUTRAL -> Atv.Lavanda
                    else -> Atv.Palomita
                },
                glow = kind == ChipKind.WAITING,
            )
        }
        Text(
            text,
            style = if (loud) AtvType.CaptionStrong.copy(fontWeight = FontWeight.ExtraBold) else AtvType.CaptionStrong,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChipDot(color: Color, glow: Boolean) {
    Spacer(
        Modifier.size(9.dp).drawBehind {
            if (glow) drawCircle(color.copy(alpha = 0.2f), radius = size.minDimension / 2 + 4.dp.toPx())
            drawCircle(color)
        }
    )
}

// ---------- buttons ----------

/**
 * Pill button, 52dp high. Unfocused ButacaAlta + Crema; focused Palomita + dark ink, 1.04 scale
 * and a soft yellow ring. [danger]: Stop red + white; focused Crema + red text + red ring.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        style = if (danger) FocusStyle.Danger else FocusStyle.Button,
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = Atv.Space6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(text, style = AtvType.Button, maxLines = 1)
        }
    }
}

/** Round icon button (prev / play / next): 56dp default, 72dp for the main action. Same focus rule as [PillButton]. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    FocusSurface(onClick = onClick, modifier = modifier.size(size), style = FocusStyle.Round) {
        Icon(icon, contentDescription, modifier = Modifier.size(size * 0.39f).align(Alignment.Center))
    }
}

// ---------- settings ----------

/**
 * Column of [SettingRow]s, 8dp apart. Pads itself by the focus ring width so the ring of the
 * first/last row isn't clipped; pass [scroll] to make it scroll (the padding stays inside).
 */
@Composable
fun SettingsList(
    modifier: Modifier = Modifier,
    scroll: ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .then(if (scroll != null) Modifier.verticalScroll(scroll) else Modifier)
            .padding(Atv.RingRow),
        verticalArrangement = Arrangement.spacedBy(Atv.Space2),
        content = content,
    )
}

/**
 * Setting row: Butaca card, radius 18, icon + title (16 extra-bold) + subtitle (13 Lavanda) +
 * trailing [value] (Palomita) / [chevron] / [trailing] slot. Focused: Crema background, Pepe ink,
 * 4dp Palomita ring, no scale. The subtitle shows up to [subtitleLines] lines.
 */
@Composable
fun SettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    subtitleLines: Int = 2,
    trailing: (@Composable RowScope.(focused: Boolean) -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        radius = Atv.RadiusRow,
        style = FocusStyle.Row,
        focusScale = 1f,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { focused ->
        val muted = if (focused) Atv.OnFocusMuted else Atv.InkMuted
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Atv.Space4, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (focused) Atv.OnFocus else Atv.InkMuted, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = AtvType.RowTitle, color = LocalContentColor.current)
                if (subtitle != null) {
                    Text(subtitle, style = AtvType.RowSubtitle, color = muted, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = AtvType.RowValue,
                    color = if (focused) Atv.OnFocus else Atv.Palomita,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
            trailing?.invoke(this, focused)
            if (chevron) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    null,
                    tint = if (focused) Atv.OnFocus else Atv.InkMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** On: coral track, white knob right. Off: Pasillo track, Lavanda knob left. 46x26. */
@Composable
fun Toggle(checked: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) Atv.Pepe else Atv.Pasillo, tween(150), label = "toggleTrack")
    val knob by animateColorAsState(if (checked) Color.White else Atv.Lavanda, tween(150), label = "toggleKnob")
    val position by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "togglePosition")
    Box(modifier.size(46.dp, 26.dp).clip(Atv.ShapePill).background(track).padding(3.dp)) {
        Box(Modifier.offset(x = 20.dp * position).size(20.dp).clip(Atv.ShapePill).background(knob))
    }
}

// ---------- progress ----------

/** Progress / countdown bar: Pasillo track, [color] fill (Palomita; Pepe coral for the request timeout). */
@Composable
fun TrackBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = Atv.Palomita,
) {
    Box(modifier.height(height).clip(Atv.ShapePill).background(Atv.Pasillo)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(Atv.ShapePill).background(color))
    }
}

// ---------- Pepe's props ----------

/** Which corner of a [SpeechBubble] is the small (4dp) "tail" corner, pointing at Pepe. */
enum class BubbleTail { BottomStart, BottomEnd, TopStart, TopEnd }

/** Pepe's speech bubble: Crema, Pepe-ink Fredoka 600, radius 18 with one 4dp tail corner. */
@Composable
fun SpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
    tail: BubbleTail = BubbleTail.BottomStart,
    style: TextStyle = AtvType.Bubble,
) {
    val round = Atv.RadiusRow
    val tip = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (tail == BubbleTail.TopStart) tip else round,
        topEnd = if (tail == BubbleTail.TopEnd) tip else round,
        bottomEnd = if (tail == BubbleTail.BottomEnd) tip else round,
        bottomStart = if (tail == BubbleTail.BottomStart) tip else round,
    )
    Text(
        text,
        style = style,
        color = Atv.PepeInk,
        modifier = modifier.clip(shape).background(Atv.Crema).padding(horizontal = Atv.Space4, vertical = 10.dp),
    )
}

/** The flat ellipse Pepe stands on (#221B3D; darker on dimmed screens). Size it with [modifier]. */
@Composable
fun FloorShadow(modifier: Modifier = Modifier, color: Color = Atv.Floor) {
    Spacer(modifier.drawBehind { drawOval(color) })
}

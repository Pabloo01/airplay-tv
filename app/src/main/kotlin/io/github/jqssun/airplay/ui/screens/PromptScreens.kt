package io.github.jqssun.airplay.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.design.BubbleTail
import io.github.jqssun.airplay.ui.design.ChipKind
import io.github.jqssun.airplay.ui.design.Eyebrow
import io.github.jqssun.airplay.ui.design.FloorShadow
import io.github.jqssun.airplay.ui.design.Panel
import io.github.jqssun.airplay.ui.design.PillButton
import io.github.jqssun.airplay.ui.design.SpeechBubble
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.StatusChip
import io.github.jqssun.airplay.ui.design.Pepe
import io.github.jqssun.airplay.ui.design.PepeMood
import io.github.jqssun.airplay.ui.design.TrackBar
import io.github.jqssun.airplay.ui.design.Wall
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import kotlinx.coroutines.delay

@Composable
private fun rememberCountdown(deadline: Long): Long {
    var remaining by remember(deadline) { mutableLongStateOf(deadline - SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (remaining > 0) {
            delay(250)
            remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }
    }
    return remaining
}

// ---------- 2 · a new device knocks ----------

@Composable
fun RequestScreen(request: AirPlayService.ClientRequest, onAnswer: (Boolean) -> Unit) {
    val remaining = rememberCountdown(request.deadline)
    val allowFocus = remember { FocusRequester() }
    LaunchedEffect(request) { allowFocus.requestFocusUntilLanded() }
    BackHandler { onAnswer(false) }
    val name = request.name.ifBlank { stringResource(R.string.device_generic) }
    val card: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Atv.Space3)) {
            IconTile(deviceIcon(request.model))
            Eyebrow(stringResource(R.string.request_eyebrow))
        }
        Text(
            stringResource(R.string.request_title, name),
            style = AtvType.SectionTitle,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(stringResource(R.string.request_desc), style = AtvType.Body, color = Atv.InkMuted)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(Atv.Space3)) {
            PillButton(
                stringResource(R.string.btn_allow_device),
                icon = Icons.Rounded.Check,
                onClick = { onAnswer(true) },
                modifier = Modifier.focusRequester(allowFocus),
            )
            PillButton(stringResource(R.string.btn_reject), onClick = { onAnswer(false) })
        }
        Text(
            stringResource(R.string.request_note),
            style = AtvType.Caption,
            color = Atv.InkMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrackBar(
                remaining / AirPlayService.CLIENT_REQUEST_TIMEOUT_MS.toFloat(),
                Modifier.weight(1f),
                color = Atv.Pepe,
            )
            Text(
                stringResource(R.string.request_timeout, (remaining / 1000).toInt()),
                style = AtvType.Footnote,
                color = Atv.InkMuted,
            )
        }
    }
    val bubble = stringResource(R.string.request_bubble)
    Wall {
        Stage(
            narrow = {
                SpeechBubble(bubble, tail = BubbleTail.BottomEnd, style = AtvType.Headline)
                Pepe(PepeMood.CURIOSO, size = 160.dp)
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Atv.Space6), verticalArrangement = Arrangement.spacedBy(14.dp), content = card)
                }
            },
        ) {
            FloorShadow(Modifier.offset(70.dp, 404.dp).size(300.dp, 52.dp))
            SpeechBubble(bubble, Modifier.offset(92.dp, 70.dp), tail = BubbleTail.BottomEnd, style = AtvType.Headline)
            Pepe(PepeMood.CURIOSO, Modifier.offset(80.dp, 140.dp), size = 280.dp)
            Panel(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = Atv.SafeX)
                    .width(470.dp)
            ) {
                Column(Modifier.padding(Atv.Space8), verticalArrangement = Arrangement.spacedBy(14.dp), content = card)
            }
        }
    }
}

// ---------- 3 · pairing code ----------

@Composable
fun PinScreen(pin: String, clientName: String, onCancel: () -> Unit) {
    val deadline = remember(pin) { SystemClock.elapsedRealtime() + PIN_SCREEN_MS }
    val remaining = rememberCountdown(deadline)
    LaunchedEffect(remaining) { if (remaining <= 0) onCancel() }
    // nothing to press here (as in the prototype): Back closes it, and so does the timer
    BackHandler(onBack = onCancel)
    val sender = clientName.ifBlank { stringResource(R.string.device_generic) }
    val chip = stringResource(R.string.pin_chip, sender)
    val bubble = stringResource(R.string.pin_bubble)
    val code: @Composable ColumnScope.(compact: Boolean) -> Unit = { compact ->
        Text(stringResource(R.string.pin_label), style = PinTitle)
        PinDigits(pin, compact)
        Text(
            stringResource(R.string.pin_desc),
            style = AtvType.Body,
            color = Atv.InkMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier.width(456.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrackBar(remaining / PIN_SCREEN_MS.toFloat(), Modifier.weight(1f))
            Text(
                stringResource(R.string.pin_timer, (remaining / 1000).toInt()),
                style = AtvType.Footnote,
                color = Atv.InkMuted,
            )
        }
    }
    Wall {
        Stage(
            narrow = {
                StatusChip(chip, ChipKind.WAITING, icon = Icons.Outlined.Lock)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) { code(true) }
                SpeechBubble(bubble)
                Pepe(PepeMood.LISTA, size = 160.dp)
            },
        ) {
            StatusChip(
                chip,
                ChipKind.WAITING,
                Modifier.align(Alignment.TopEnd).padding(top = Atv.SafeY, end = Atv.SafeX).widthIn(max = 560.dp),
                icon = Icons.Outlined.Lock,
            )
            Column(
                modifier = Modifier.offset(Atv.SafeX, 92.dp).width(560.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) { code(false) }
            SpeechBubble(bubble, Modifier.offset(650.dp, 120.dp))
            FloorShadow(Modifier.offset(650.dp, 418.dp).size(250.dp, 46.dp))
            Pepe(PepeMood.LISTA, Modifier.offset(660.dp, 190.dp), size = 240.dp)
        }
    }
}

/** One cream tile per digit (104x132, radius 22) standing on a 6dp darker-cream ledge. */
@Composable
private fun PinDigits(pin: String, compact: Boolean) {
    val width = if (compact) 64.dp else 104.dp
    val height = if (compact) 82.dp else 132.dp
    val style = if (compact) AtvType.Pin.copy(fontSize = 46.sp, lineHeight = 52.sp) else AtvType.Pin
    val spoken = stringResource(R.string.cd_pin, pin.toList().joinToString(" "))
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        pin.forEach { digit -> DigitTile(digit, width, height, style) }
    }
}

@Composable
private fun DigitTile(digit: Char, width: Dp, height: Dp, style: TextStyle) {
    Box(
        modifier = Modifier
            .size(width, height)
            .drawBehind {
                val r = CornerRadius(Atv.RadiusCard.toPx())
                drawRoundRect(PinLedge, topLeft = Offset(0f, PinLedgeHeight.toPx()), size = size, cornerRadius = r)
                drawRoundRect(Atv.Crema, size = size, cornerRadius = r)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(digit.toString(), style = style, color = Atv.PepeInk)
    }
}

private val PinTitle = AtvType.Title.copy(fontSize = 38.sp, lineHeight = 44.sp)
private val PinLedge = Color(0xFFD9C9A8)
private val PinLedgeHeight = 6.dp

// ---------- 9 · connection lost (over the last frame) ----------

@Composable
fun ConnectionLostOverlay(senderName: String, onHome: () -> Unit) {
    val deadline = remember { SystemClock.elapsedRealtime() + LOST_WAIT_MS }
    val remaining = rememberCountdown(deadline)
    LaunchedEffect(remaining) { if (remaining <= 0) onHome() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusUntilLanded() }
    BackHandler(onBack = onHome)
    val sender = senderName.ifBlank { stringResource(R.string.device_generic) }
    val chip = stringResource(R.string.chip_lost)
    val copy: @Composable ColumnScope.() -> Unit = {
        Text(stringResource(R.string.lost_kicker), style = AtvType.Headline, color = Atv.Palomita)
        Text(
            stringResource(R.string.lost_title, sender),
            style = AtvType.SectionTitle.copy(fontSize = 34.sp, lineHeight = 40.sp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.lost_desc, ((remaining + 999) / 1000).toInt()),
            style = AtvType.Body,
            color = Atv.InkMuted,
        )
        TrackBar(remaining / LOST_WAIT_MS.toFloat(), Modifier.widthIn(max = 300.dp).fillMaxWidth())
        PillButton(
            stringResource(R.string.btn_home),
            icon = Icons.Rounded.Home,
            onClick = onHome,
            modifier = Modifier.padding(top = Atv.Space2).focusRequester(focus),
        )
    }
    Box(Modifier.fillMaxSize().background(Atv.Scrim)) {
        Stage(
            narrow = {
                StatusChip(chip, ChipKind.ALERT, icon = Icons.Rounded.WifiOff)
                Pepe(PepeMood.MAREADO, size = 160.dp)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = copy)
            },
        ) {
            StatusChip(
                chip,
                ChipKind.ALERT,
                Modifier.align(Alignment.TopEnd).padding(top = Atv.SafeY, end = Atv.SafeX),
                icon = Icons.Rounded.WifiOff,
            )
            FloorShadow(Modifier.offset(70.dp, 420.dp).size(240.dp, 44.dp), color = DimFloor)
            Pepe(PepeMood.MAREADO, Modifier.offset(80.dp, 170.dp), size = 250.dp)
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 400.dp, end = Atv.SafeX, top = 130.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = copy,
            )
        }
    }
}

// the floor under Pepe on a dimmed screen
private val DimFloor = Color(0xFF1A1530)

private const val PIN_SCREEN_MS = 60_000L
private const val LOST_WAIT_MS = 10_000L

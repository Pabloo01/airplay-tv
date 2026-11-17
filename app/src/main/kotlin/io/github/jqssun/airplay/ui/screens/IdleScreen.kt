package io.github.jqssun.airplay.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.design.ChipKind
import io.github.jqssun.airplay.ui.design.Eyebrow
import io.github.jqssun.airplay.ui.design.FloorShadow
import io.github.jqssun.airplay.ui.design.PillButton
import io.github.jqssun.airplay.ui.design.SpeechBubble
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.Zagalo
import io.github.jqssun.airplay.ui.design.ZagaloMood
import io.github.jqssun.airplay.ui.design.Wall
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import kotlinx.coroutines.delay
import java.util.Calendar

/** What the home screen is showing: the receiver waiting (the prototype) or one of its other states. */
private enum class Home(val mood: ZagaloMood, @param:StringRes val title: Int, @param:StringRes val hint: Int) {
    WAITING(ZagaloMood.HOLA, R.string.idle_title, R.string.idle_hint),
    CONNECTING(ZagaloMood.CURIOSO, R.string.idle_title_connecting, R.string.waiting_for_playback),
    STOPPED(ZagaloMood.DORMIDO, R.string.idle_title_stopped, R.string.idle_hint_stopped),
    ERROR(ZagaloMood.MAREADO, R.string.idle_title_error, R.string.idle_hint_error),
    PERMISSION(ZagaloMood.BUSCANDO, R.string.idle_title_permission, R.string.local_network_desc),
}

private val HomeStars = listOf(
    Star(120, 70, 6f, Atv.Palomita, 0.35f),
    Star(520, 46, 4f, Atv.Crema, 0.3f),
    Star(880, 150, 5f, Atv.Palomita, 0.3f),
    Star(430, 470, 4f, Atv.Crema, 0.25f),
)

@Composable
fun IdleScreen(
    state: ServerState,
    localNetwork: LocalNetworkAccess,
    serverName: String,
    clientName: String,
    connections: Int,
    onStart: () -> Unit,
    onLocalNetwork: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val home = when {
        state != ServerState.RUNNING && localNetwork != LocalNetworkAccess.GRANTED -> Home.PERMISSION
        state == ServerState.ERROR -> Home.ERROR
        state == ServerState.STOPPED -> Home.STOPPED
        connections > 0 -> Home.CONNECTING
        else -> Home.WAITING
    }
    val sender = clientName.ifBlank { stringResource(R.string.device_generic) }
    val (chipText, chipKind) = when (home) {
        Home.WAITING -> stringResource(R.string.status_waiting) to ChipKind.WAITING
        Home.CONNECTING -> stringResource(R.string.status_connecting, sender) to ChipKind.WAITING
        Home.STOPPED -> stringResource(R.string.status_stopped) to ChipKind.NEUTRAL
        Home.ERROR -> stringResource(R.string.status_error) to ChipKind.ALERT
        Home.PERMISSION -> stringResource(R.string.status_permission) to ChipKind.ALERT
    }
    val action: Pair<String, () -> Unit>? = when (home) {
        Home.PERMISSION -> stringResource(
            if (localNetwork == LocalNetworkAccess.SETTINGS) R.string.btn_open_settings else R.string.btn_allow
        ) to onLocalNetwork
        Home.ERROR -> stringResource(R.string.btn_retry) to onStart
        Home.STOPPED -> stringResource(R.string.btn_start) to onStart
        Home.WAITING, Home.CONNECTING -> null
    }
    // the remote starts on the primary action when there is one, else on "Ajustes"
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(action != null) { firstFocus.requestFocusUntilLanded() }
    val greeting = stringResource(greetingFor(rememberHour()))

    val copy: @Composable ColumnScope.() -> Unit = {
        Eyebrow(greeting)
        Text(stringResource(home.title), style = AtvType.Display, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(stringResource(home.hint), style = AtvType.BodyLarge, color = Atv.InkMuted)
        if (action != null) {
            PillButton(
                text = action.first,
                onClick = action.second,
                modifier = Modifier.padding(top = 10.dp).focusRequester(firstFocus),
            )
        }
    }
    val settings: @Composable () -> Unit = {
        PillButton(
            text = stringResource(R.string.btn_settings),
            icon = Icons.Outlined.Settings,
            onClick = onOpenSettings,
            modifier = if (action == null) Modifier.focusRequester(firstFocus) else Modifier,
        )
    }
    val waiting = home == Home.WAITING

    Wall {
        Stage(
            narrow = {
                TopBar(chipText, chipKind)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = copy)
                if (waiting) Steps(serverName, Modifier.fillMaxWidth(), stacked = true)
                if (waiting) SpeechBubble(stringResource(R.string.idle_bubble))
                Zagalo(home.mood, size = 180.dp)
                settings()
            },
        ) {
            Starfield(HomeStars)
            FloorShadow(Modifier.offset(600.dp, 404.dp).size(300.dp, 56.dp))
            TopBar(chipText, chipKind, Modifier.padding(horizontal = Atv.SafeX).padding(top = Atv.SafeY))
            Column(
                modifier = Modifier.offset(Atv.SafeX, 112.dp).width(500.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                copy()
                if (waiting) Steps(serverName, Modifier.padding(top = 10.dp))
            }
            if (waiting) SpeechBubble(stringResource(R.string.idle_bubble), Modifier.offset(568.dp, 92.dp))
            Zagalo(home.mood, Modifier.offset(612.dp, 150.dp), size = 280.dp)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = Atv.SafeX, bottom = Atv.SafeY),
            ) { settings() }
        }
    }
}

/** "1 Centro de control · 2 Duplicar pantalla · 3 <this TV>": the last step is the TV, in coral. */
@Composable
private fun Steps(serverName: String, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val first = stringResource(R.string.idle_step_control_center)
    val second = stringResource(R.string.idle_step_mirroring)
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Step(1, first)
            Step(2, second)
            Step(3, serverName, highlight = true)
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Step(1, first)
            Step(2, second)
            Step(3, serverName, highlight = true, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun Step(number: Int, text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val ink = if (highlight) Atv.OnZagalo else Atv.Ink
    Row(
        modifier = modifier
            .clip(Atv.ShapeTile)
            .background(if (highlight) Atv.Zagalo else Atv.Butaca)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Atv.Space2),
    ) {
        Box(
            modifier = Modifier.size(22.dp).clip(Atv.ShapePill).background(if (highlight) Atv.ZagaloScreen else Atv.Pasillo),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = AtvType.Footnote, color = if (highlight) Atv.OnZagalo else Atv.Palomita)
        }
        Text(
            text,
            style = if (highlight) AtvType.CaptionStrong.copy(fontWeight = FontWeight.ExtraBold) else AtvType.CaptionStrong,
            color = ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

@Composable
private fun rememberHour(): Int {
    var hour by remember { mutableIntStateOf(currentHour()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            hour = currentHour()
        }
    }
    return hour
}

private fun currentHour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

// Spanish day parts: "buenos días" until lunch, "buenas tardes" until dark
@StringRes
private fun greetingFor(hour: Int): Int = when (hour) {
    in 6..13 -> R.string.greeting_morning
    in 14..20 -> R.string.greeting_afternoon
    else -> R.string.greeting_night
}

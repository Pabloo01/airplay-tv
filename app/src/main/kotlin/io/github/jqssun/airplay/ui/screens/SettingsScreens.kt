package io.github.jqssun.airplay.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.AllowedDevice
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.LogsScreen
import io.github.jqssun.airplay.ui.SettingsScreen
import io.github.jqssun.airplay.ui.atvFieldColors
import io.github.jqssun.airplay.ui.design.BubbleTail
import io.github.jqssun.airplay.ui.design.Eyebrow
import io.github.jqssun.airplay.ui.design.FloorShadow
import io.github.jqssun.airplay.ui.design.FocusStyle
import io.github.jqssun.airplay.ui.design.FocusSurface
import io.github.jqssun.airplay.ui.design.PillButton
import io.github.jqssun.airplay.ui.design.SafeArea
import io.github.jqssun.airplay.ui.design.SettingRow
import io.github.jqssun.airplay.ui.design.SettingsList
import io.github.jqssun.airplay.ui.design.SpeechBubble
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.Zagalo
import io.github.jqssun.airplay.ui.design.ZagaloMood
import io.github.jqssun.airplay.ui.design.Toggle
import io.github.jqssun.airplay.ui.design.Wall
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import io.github.jqssun.airplay.viewmodel.MainViewModel
import java.util.Calendar

/** "‹ Ajustes": a breadcrumb, not a stop for the remote (Back goes back); tappable on touch screens. */
@Composable
private fun BackLink(onBack: () -> Unit) {
    Text(
        stringResource(R.string.back_to_settings),
        style = AtvType.CaptionStrong,
        color = Atv.InkMuted,
        modifier = Modifier.pointerInput(onBack) { detectTapGestures { onBack() } },
    )
}

// ---------- 7 · settings ----------

/** Row that takes focus when the settings home appears: the one you came back from, else the top. */
enum class SettingsFocus { TOP, DEVICES, ADVANCED }

@Composable
fun SettingsHome(
    viewModel: MainViewModel,
    onOpenDevices: () -> Unit,
    onOpenAdvanced: () -> Unit,
    focusOn: SettingsFocus = SettingsFocus.TOP,
) {
    val serverState by viewModel.serverState.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val requirePin by viewModel.pref(Prefs.REQUIRE_PIN).collectAsState()
    val requestNew by viewModel.pref(Prefs.REQUEST_NEW_DEVICES).collectAsState()
    val autoCrop by viewModel.pref(Prefs.AUTO_CROP).collectAsState()
    val smoothPlayback by viewModel.pref(Prefs.SMOOTH_PLAYBACK).collectAsState()
    val devices by viewModel.allowedDevices.collectAsState()
    var editingName by remember { mutableStateOf(false) }
    val running = serverState == ServerState.RUNNING
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val ip = remember(serverState) { localIpv4() }
    val firstFocus = remember { FocusRequester() }
    val devicesFocus = remember { FocusRequester() }
    val advancedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        when (focusOn) {
            SettingsFocus.TOP -> firstFocus
            SettingsFocus.DEVICES -> devicesFocus
            SettingsFocus.ADVANCED -> advancedFocus
        }.requestFocusUntilLanded()
    }

    val header: @Composable ColumnScope.() -> Unit = {
        Eyebrow(stringResource(R.string.settings_eyebrow))
        Text(stringResource(R.string.settings_title), style = AtvType.Title.copy(fontSize = 40.sp, lineHeight = 46.sp))
        Text(stringResource(R.string.settings_desc), style = AtvType.Callout, color = Atv.InkMuted)
    }
    val footer = if (ip != null) stringResource(R.string.settings_footer, version, ip)
    else stringResource(R.string.settings_footer_offline, version)
    val rows: @Composable ColumnScope.() -> Unit = {
        SettingRow(
            title = stringResource(R.string.row_server),
            subtitle = stringResource(if (running) R.string.row_server_running else R.string.row_server_stopped),
            icon = Icons.Rounded.PowerSettingsNew,
            onClick = { if (running) viewModel.stopServer() else viewModel.startServer() },
            modifier = Modifier.focusRequester(firstFocus),
        ) { Toggle(running) }
        SettingRow(
            title = stringResource(R.string.row_name),
            subtitle = stringResource(R.string.row_name_sub),
            value = serverName,
            icon = Icons.Rounded.Tv,
            onClick = { editingName = true },
        )
        SettingRow(
            title = stringResource(R.string.row_request),
            subtitle = stringResource(R.string.row_request_sub),
            icon = Icons.Rounded.VerifiedUser,
            onClick = { viewModel.set(Prefs.REQUEST_NEW_DEVICES, !requestNew) },
        ) { Toggle(requestNew) }
        SettingRow(
            title = stringResource(R.string.row_pin),
            subtitle = stringResource(R.string.row_pin_sub),
            icon = Icons.Rounded.Lock,
            onClick = { viewModel.set(Prefs.REQUIRE_PIN, !requirePin) },
        ) { Toggle(requirePin) }
        SettingRow(
            title = stringResource(R.string.row_devices),
            subtitle = devices.takeIf { it.isNotEmpty() }?.joinToString { it.name }
                ?: stringResource(R.string.row_devices_none),
            value = devices.size.toString(),
            icon = Icons.Rounded.PhoneIphone,
            onClick = onOpenDevices,
            modifier = Modifier.focusRequester(devicesFocus),
        )
        SettingRow(
            title = stringResource(R.string.setting_auto_crop),
            subtitle = stringResource(R.string.row_crop_sub),
            icon = Icons.Rounded.CropFree,
            onClick = { viewModel.set(Prefs.AUTO_CROP, !autoCrop) },
        ) { Toggle(autoCrop) }
        SettingRow(
            title = stringResource(R.string.setting_smooth_playback),
            subtitle = stringResource(R.string.row_smooth_sub),
            icon = Icons.AutoMirrored.Rounded.ShowChart,
            onClick = { viewModel.set(Prefs.SMOOTH_PLAYBACK, !smoothPlayback) },
        ) { Toggle(smoothPlayback) }
        SettingRow(
            title = stringResource(R.string.row_advanced),
            subtitle = stringResource(R.string.row_advanced_sub),
            icon = Icons.Rounded.Tune,
            chevron = true,
            onClick = onOpenAdvanced,
            modifier = Modifier.focusRequester(advancedFocus),
        )
    }

    Wall {
        Stage(
            narrow = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = header)
                SettingsList(Modifier.fillMaxWidth(), content = rows)
                Zagalo(ZagaloMood.HERRAMIENTAS, size = 140.dp)
                Text(footer, style = AtvType.Footnote, color = Atv.InkMuted)
            },
        ) {
            Column(
                modifier = Modifier.offset(Atv.SafeX, 40.dp).width(300.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = header,
            )
            Zagalo(ZagaloMood.HERRAMIENTAS, Modifier.offset(60.dp, 236.dp), size = 200.dp)
            Text(
                footer,
                style = AtvType.Footnote,
                color = Atv.InkMuted,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = Atv.SafeX, bottom = Atv.SafeY),
            )
            // the list pads itself by the focus ring: shift it out by the ring so rows sit at x=380, y=32
            SettingsList(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 380.dp - Atv.RingRow, end = Atv.SafeX - Atv.RingRow, top = 32.dp - Atv.RingRow)
                    .height(484.dp + Atv.RingRow * 2),
                scroll = rememberScrollState(),
                content = rows,
            )
        }
    }

    if (editingName) {
        NameDialog(
            current = serverName,
            onDismiss = { editingName = false },
            onSave = {
                viewModel.set(Prefs.SERVER_NAME, it)
                editingName = false
            },
        )
    }
}

@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusUntilLanded() }
    val save = { text.trim().takeIf { it.isNotEmpty() }?.let(onSave) ?: onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Atv.Butaca,
        shape = Atv.ShapeLg,
        title = { Text(stringResource(R.string.row_name), style = AtvType.SectionTitle, color = Atv.Ink) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(48) },
                singleLine = true,
                textStyle = AtvType.BodyLarge,
                shape = Atv.ShapeRow,
                colors = atvFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { PillButton(stringResource(R.string.btn_save), onClick = { save() }) },
        dismissButton = { PillButton(stringResource(R.string.btn_cancel), onClick = onDismiss) },
    )
}

// ---------- 8 · allowed devices ----------

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun lastSeenText(ms: Long): String {
    val context = LocalContext.current
    val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(ms)) / 3_600_000.0 / 24).let { Math.round(it).toInt() }
    return when {
        days <= 0 -> stringResource(R.string.last_seen_today, DateFormat.getTimeFormat(context).format(ms))
        days == 1 -> stringResource(R.string.last_seen_yesterday)
        else -> pluralStringResource(R.plurals.last_seen_days, days, days)
    }
}

@Composable
fun DevicesScreen(
    devices: List<AllowedDevice>,
    onRemove: (String) -> Unit,
    onRemoveAll: () -> Unit,
    onBack: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    // back on the first row after every removal, so the remote never loses its place
    LaunchedEffect(devices.size) { firstFocus.requestFocusUntilLanded() }
    val header: @Composable ColumnScope.() -> Unit = {
        BackLink(onBack)
        Eyebrow(stringResource(R.string.devices_eyebrow))
        Text(stringResource(R.string.devices_title), style = AtvType.Title)
        Text(stringResource(R.string.devices_desc), style = AtvType.Callout, color = Atv.InkMuted)
    }
    val list: @Composable ColumnScope.() -> Unit = {
        if (devices.isEmpty()) {
            SettingRow(
                title = stringResource(R.string.devices_empty),
                icon = Icons.Rounded.Devices,
                onClick = onBack,
                modifier = Modifier.focusRequester(firstFocus),
            )
        } else {
            devices.forEachIndexed { i, d ->
                DeviceRow(
                    device = d,
                    onRemove = { onRemove(d.id) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            Text(stringResource(R.string.devices_hint), style = AtvType.Caption, color = Atv.InkMuted)
        }
    }
    val removeAll: @Composable (Modifier) -> Unit = { modifier ->
        if (devices.isNotEmpty()) PillButton(stringResource(R.string.btn_remove_all), onClick = onRemoveAll, modifier = modifier)
    }
    val bubble = stringResource(R.string.devices_bubble)

    Wall {
        Stage(
            narrow = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = header)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Atv.RingRow),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = list,
                )
                removeAll(Modifier)
                Zagalo(ZagaloMood.LISTA, size = 140.dp)
            },
        ) {
            Column(
                modifier = Modifier.offset(Atv.SafeX, 40.dp).width(330.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = header,
            )
            if (devices.isNotEmpty()) {
                SpeechBubble(
                    bubble,
                    Modifier.offset(196.dp, 296.dp),
                    tail = BubbleTail.BottomEnd,
                    style = AtvType.Bubble.copy(fontSize = 16.sp, lineHeight = 22.sp),
                )
            }
            FloorShadow(Modifier.offset(50.dp, 470.dp).size(200.dp, 38.dp))
            Zagalo(ZagaloMood.LISTA, Modifier.offset(56.dp, 316.dp), size = 180.dp)
            // rows at x=420, y=96, scrolling above "Quitar todos"; padded by the focus ring
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 420.dp - Atv.RingRow, end = Atv.SafeX - Atv.RingRow, top = 96.dp - Atv.RingRow)
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(Atv.RingRow),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = list,
            )
            removeAll(Modifier.align(Alignment.BottomStart).padding(start = 420.dp, bottom = 32.dp))
        }
    }
}

/** Device card: icon tile, name, last connection and a red "Quitar" pill. OK removes it. */
@Composable
private fun DeviceRow(device: AllowedDevice, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onRemove,
        radius = Atv.RadiusCard,
        style = FocusStyle.Row,
        focusScale = 1f,
        modifier = modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = Atv.Space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Atv.Space4),
        ) {
            IconTile(
                deviceIcon(device.model),
                background = if (focused) Atv.PalomitaSoft else Atv.Pasillo,
                tint = if (focused) Atv.ZagaloInk else Atv.Palomita,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    device.name.ifBlank { device.model.ifBlank { stringResource(R.string.device_generic) } },
                    style = DeviceName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.last_seen, lastSeenText(device.lastSeenMs)),
                    style = AtvType.Caption,
                    color = if (focused) Atv.OnFocusMuted else Atv.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.devices_remove),
                style = AtvType.CaptionStrong.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .clip(Atv.ShapePill)
                    .background(Atv.Stop)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

private val DeviceName = AtvType.RowTitle.copy(fontSize = 18.sp, lineHeight = 24.sp)

// ---------- advanced settings and logs ----------

@Composable
fun AdvancedSettingsScreen(viewModel: MainViewModel, onBack: () -> Unit, onOpenLogs: () -> Unit) {
    Wall {
        SafeArea {
            Column(verticalArrangement = Arrangement.spacedBy(Atv.Space2)) {
                BackLink(onBack)
                Eyebrow(stringResource(R.string.settings_eyebrow))
                Text(stringResource(R.string.row_advanced), style = AtvType.Title)
                Box(Modifier.weight(1f).padding(top = Atv.Space2)) { SettingsScreen(viewModel, onOpenLogs) }
            }
        }
    }
}

@Composable
fun LogsRoute(viewModel: MainViewModel) {
    Wall {
        SafeArea {
            Box(Modifier.fillMaxSize()) { LogsScreen(viewModel) }
        }
    }
}

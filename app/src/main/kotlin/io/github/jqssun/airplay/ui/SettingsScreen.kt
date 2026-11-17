package io.github.jqssun.airplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.appSettingsIntent
import io.github.jqssun.airplay.ui.design.Eyebrow
import io.github.jqssun.airplay.ui.design.SettingRow
import io.github.jqssun.airplay.ui.design.Toggle
import io.github.jqssun.airplay.ui.design.card
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import io.github.jqssun.airplay.viewmodel.MainViewModel
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

/** Quick picture presets (resolution + frame rate) shown above the detailed controls. */
private enum class Quality(@param:StringRes val label: Int, val resolution: String, val fps: Int) {
    AUTO(R.string.quality_auto, Prefs.AUTO, 60),
    HD(R.string.quality_720, "1280x720", 60),
    FULL_HD(R.string.quality_1080, "1920x1080", 60),
    UHD(R.string.quality_4k, "3840x2160", 60),
}

@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenLogs: () -> Unit) {
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val h265 by viewModel.pref(Prefs.H265).collectAsState()
    val enforceSdr by viewModel.pref(Prefs.ENFORCE_SDR).collectAsState()
    val alacEnabled by viewModel.pref(Prefs.ALAC_ENABLED).collectAsState()
    val aacEnabled by viewModel.pref(Prefs.AAC_ENABLED).collectAsState()
    val resolution by viewModel.pref(Prefs.RESOLUTION).collectAsState()
    val keepScreenOn by viewModel.pref(Prefs.KEEP_SCREEN_ON).collectAsState()
    val advertiseMirroring by viewModel.pref(Prefs.ADVERTISE_MIRRORING).collectAsState()
    val advertiseAudio by viewModel.pref(Prefs.ADVERTISE_AUDIO).collectAsState()
    val launchOnConnect by viewModel.pref(Prefs.LAUNCH_ON_CONNECT).collectAsState()
    val maxFps by viewModel.pref(Prefs.MAX_FPS).collectAsState()
    val overscanned by viewModel.pref(Prefs.OVERSCANNED).collectAsState()
    val autoCrop by viewModel.pref(Prefs.AUTO_CROP).collectAsState()
    val smoothPlayback by viewModel.pref(Prefs.SMOOTH_PLAYBACK).collectAsState()
    val requirePin by viewModel.pref(Prefs.REQUIRE_PIN).collectAsState()
    val allowNewConn by viewModel.pref(Prefs.ALLOW_NEW_CONN).collectAsState()
    val autoStart by viewModel.pref(Prefs.AUTO_START).collectAsState()
    val bootAutoStart by viewModel.pref(Prefs.BOOT_AUTO_START).collectAsState()
    val runInBackground by viewModel.pref(Prefs.RUN_IN_BACKGROUND).collectAsState()
    val serverPort by viewModel.pref(Prefs.SERVER_PORT).collectAsState()
    val audioLatencyMs by viewModel.pref(Prefs.AUDIO_LATENCY_MS).collectAsState()
    val forceSwAlac by viewModel.pref(Prefs.FORCE_SW_ALAC).collectAsState()
    val forceSwAac by viewModel.pref(Prefs.FORCE_SW_AAC).collectAsState()
    val syncClientVolume by viewModel.pref(Prefs.SYNC_CLIENT_VOLUME).collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val developerOptions by viewModel.pref(Prefs.DEVELOPER_OPTIONS).collectAsState()
    val keyAllowFrameDrop by viewModel.pref(Prefs.KEY_ALLOW_FRAME_DROP).collectAsState()
    val realtimeDecoderPriority by viewModel.pref(Prefs.KEY_PRIORITY).collectAsState()
    val lowLatency by viewModel.pref(Prefs.LOW_LATENCY).collectAsState()
    val operatingRate by viewModel.pref(Prefs.OPERATING_RATE).collectAsState()
    val benchmarkLog by viewModel.pref(Prefs.BENCHMARK_LOG).collectAsState()
    val audioAutoBuffer by viewModel.pref(Prefs.AUDIO_AUTO_BUFFER).collectAsState()
    val audioCushionMs by viewModel.pref(Prefs.AUDIO_CUSHION_MS).collectAsState()
    val audioAdaptiveStep by viewModel.pref(Prefs.AUDIO_ADAPTIVE_STEP).collectAsState()
    val oboeBufferFrames by viewModel.pref(Prefs.OBOE_BUFFER_FRAMES).collectAsState()
    val tri = listOf(
        Prefs.AUTO to stringResource(R.string.chip_auto),
        Prefs.ON to stringResource(R.string.chip_on),
        Prefs.OFF to stringResource(R.string.chip_off),
    )

    // padded by the focus ring so the first/last row's ring isn't clipped by the scroll
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Atv.RingRow),
        verticalArrangement = Arrangement.spacedBy(Atv.Space2),
    ) {
        SectionHeader(stringResource(R.string.section_server))

        SettingTextField(
            label = stringResource(R.string.setting_server_name),
            value = serverName,
            onCommit = { viewModel.set(Prefs.SERVER_NAME, it) }
        )

        SettingTextField(
            label = stringResource(R.string.setting_server_port),
            value = serverPort.toString(),
            onCommit = { viewModel.set(Prefs.SERVER_PORT, it.toInt()) },
            range = Prefs.SERVER_PORT.range
        )

        SettingSwitch(
            title = stringResource(R.string.setting_boot_auto_start),
            description = stringResource(R.string.setting_boot_auto_start_desc),
            checked = bootAutoStart,
            onCheckedChange = { viewModel.set(Prefs.BOOT_AUTO_START, it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_run_in_background),
            description = stringResource(R.string.setting_run_in_background_desc),
            checked = runInBackground,
            onCheckedChange = { viewModel.set(Prefs.RUN_IN_BACKGROUND, it) }
        )

        SectionHeader(stringResource(R.string.section_connection))

        SettingSwitch(
            title = stringResource(R.string.setting_require_pin),
            description = stringResource(R.string.setting_require_pin_desc),
            checked = requirePin,
            onCheckedChange = { viewModel.set(Prefs.REQUIRE_PIN, it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_allow_new_conn),
            description = stringResource(R.string.setting_allow_new_conn_desc),
            checked = allowNewConn,
            onCheckedChange = { viewModel.set(Prefs.ALLOW_NEW_CONN, it) }
        )

        val ctx = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var hasOverlayPermission by remember { mutableStateOf(canAutoLaunch(ctx)) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) hasOverlayPermission = canAutoLaunch(ctx)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val needsOverlayPermission = launchOnConnect && !hasOverlayPermission
        val autoLaunch = launchOnConnect && hasOverlayPermission
        SettingSwitch(
            title = stringResource(R.string.setting_launch_on_connect),
            description = stringResource(
                if (needsOverlayPermission) R.string.setting_launch_on_connect_no_permission
                else R.string.setting_launch_on_connect_desc
            ),
            checked = autoLaunch,
            onCheckedChange = {
                viewModel.set(Prefs.LAUNCH_ON_CONNECT, it)
                if (it && !canAutoLaunch(ctx)) _openOverlaySettings(ctx)
            }
        )

        SectionHeader(stringResource(R.string.section_display))

        val quality = Quality.entries.firstOrNull { it.resolution == resolution && it.fps == maxFps }
        SettingRow(
            title = stringResource(R.string.row_quality),
            subtitle = stringResource(R.string.row_quality_sub),
            value = stringResource(quality?.label ?: R.string.quality_custom),
            icon = Icons.Rounded.HighQuality,
            onClick = {
                val next = Quality.entries[((quality?.ordinal ?: -1) + 1) % Quality.entries.size]
                viewModel.set(Prefs.RESOLUTION, next.resolution)
                viewModel.set(Prefs.MAX_FPS, next.fps)
            }
        )

        SettingResolution(
            value = resolution,
            onValueChange = { viewModel.set(Prefs.RESOLUTION, it) }
        )

        SettingChipField(
            title = stringResource(R.string.setting_max_fps),
            description = stringResource(R.string.setting_max_fps_desc),
            value = maxFps.toString(),
            presets = listOf("24" to "24", "30" to "30", "60" to "60", "120" to "120"),
            placeholder = stringResource(R.string.setting_max_fps_placeholder),
            keyboardType = KeyboardType.Number,
            onValueChange = { it.toIntOrNull()?.let { v -> viewModel.set(Prefs.MAX_FPS, v) } }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_auto_crop),
            description = stringResource(R.string.setting_auto_crop_desc),
            checked = autoCrop,
            onCheckedChange = { viewModel.set(Prefs.AUTO_CROP, it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_smooth_playback),
            description = stringResource(R.string.setting_smooth_playback_desc),
            checked = smoothPlayback,
            onCheckedChange = { viewModel.set(Prefs.SMOOTH_PLAYBACK, it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_overscanned),
            description = stringResource(R.string.setting_overscanned_desc),
            checked = overscanned,
            onCheckedChange = { viewModel.set(Prefs.OVERSCANNED, it) }
        )

        SectionHeader(stringResource(R.string.section_diagnostics))

        SettingSwitch(
            title = stringResource(R.string.setting_debug_overlay),
            description = stringResource(R.string.setting_debug_overlay_desc),
            checked = debugEnabled,
            onCheckedChange = { viewModel.set(Prefs.DEBUG_ENABLED, it) }
        )

        SettingRow(
            title = stringResource(R.string.row_logs),
            subtitle = stringResource(R.string.row_logs_sub),
            icon = Icons.AutoMirrored.Rounded.Article,
            chevron = true,
            onClick = onOpenLogs
        )

        SectionHeader(stringResource(R.string.section_decode))

        SettingChips(
            title = stringResource(R.string.setting_h265),
            description = stringResource(R.string.setting_h265_desc),
            value = h265,
            options = tri,
            onValueChange = { viewModel.set(Prefs.H265, it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_sw_alac),
            description = stringResource(R.string.setting_sw_alac_desc),
            checked = forceSwAlac,
            onCheckedChange = { viewModel.set(Prefs.FORCE_SW_ALAC, it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_sw_aac),
            description = stringResource(R.string.setting_sw_aac_desc),
            checked = forceSwAac,
            onCheckedChange = { viewModel.set(Prefs.FORCE_SW_AAC, it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_sync_client_volume),
            description = stringResource(R.string.setting_sync_client_volume_desc),
            checked = syncClientVolume,
            onCheckedChange = { viewModel.set(Prefs.SYNC_CLIENT_VOLUME, it) }
        )

        SectionHeader(stringResource(R.string.section_developer))

        SettingSwitch(
            title = stringResource(R.string.setting_developer_options),
            description = stringResource(R.string.setting_developer_options_desc),
            checked = developerOptions,
            onCheckedChange = { viewModel.set(Prefs.DEVELOPER_OPTIONS, it) }
        )

        if (developerOptions) {
            SettingSwitch(
                title = stringResource(R.string.setting_auto_start),
                description = stringResource(R.string.setting_auto_start_desc),
                checked = autoStart,
                onCheckedChange = { viewModel.set(Prefs.AUTO_START, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_keep_screen_on),
                description = stringResource(R.string.setting_keep_screen_on_desc),
                checked = keepScreenOn,
                onCheckedChange = { viewModel.set(Prefs.KEEP_SCREEN_ON, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_advertise_mirroring),
                description = stringResource(R.string.setting_advertise_mirroring_desc),
                checked = advertiseMirroring,
                onCheckedChange = { viewModel.set(Prefs.ADVERTISE_MIRRORING, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_advertise_audio),
                description = stringResource(R.string.setting_advertise_audio_desc),
                checked = advertiseAudio,
                onCheckedChange = { viewModel.set(Prefs.ADVERTISE_AUDIO, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_alac),
                description = stringResource(R.string.setting_alac_desc),
                checked = alacEnabled,
                onCheckedChange = { viewModel.set(Prefs.ALAC_ENABLED, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_aac),
                description = stringResource(R.string.setting_aac_desc),
                checked = aacEnabled,
                onCheckedChange = { viewModel.set(Prefs.AAC_ENABLED, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_key_allow_frame_drop),
                description = stringResource(R.string.setting_key_allow_frame_drop_desc),
                checked = keyAllowFrameDrop,
                onCheckedChange = { viewModel.set(Prefs.KEY_ALLOW_FRAME_DROP, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_enforce_sdr),
                description = stringResource(R.string.setting_enforce_sdr_desc),
                checked = enforceSdr,
                onCheckedChange = { viewModel.set(Prefs.ENFORCE_SDR, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_realtime_decoder_priority),
                description = stringResource(R.string.setting_realtime_decoder_priority_desc),
                checked = realtimeDecoderPriority,
                onCheckedChange = { viewModel.set(Prefs.KEY_PRIORITY, it) }
            )

            SettingChips(
                title = stringResource(R.string.setting_operating_rate),
                description = stringResource(R.string.setting_operating_rate_desc),
                value = operatingRate,
                options = tri,
                onValueChange = { viewModel.set(Prefs.OPERATING_RATE, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_low_latency),
                description = stringResource(R.string.setting_low_latency_desc),
                checked = lowLatency,
                onCheckedChange = { viewModel.set(Prefs.LOW_LATENCY, it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_audio_delay),
                description = stringResource(R.string.setting_audio_delay_desc),
                checked = audioLatencyMs >= 0,
                onCheckedChange = { viewModel.set(Prefs.AUDIO_LATENCY_MS, if (it) 250 else -1) }
            )

            if (audioLatencyMs >= 0) {
                var sliderVal by remember(audioLatencyMs) { mutableFloatStateOf(audioLatencyMs.toFloat()) }
                SliderCard(value = stringResource(R.string.audio_delay_value, sliderVal.roundToInt())) {
                    Slider(
                        value = sliderVal,
                        onValueChange = { sliderVal = it },
                        onValueChangeFinished = { viewModel.set(Prefs.AUDIO_LATENCY_MS, sliderVal.roundToInt()) },
                        valueRange = 0f..1000f,
                        steps = 19,
                        colors = atvSliderColors(),
                        modifier = Modifier.weight(1f).dpadFocus().dpadAdjust(
                            onLeft = { viewModel.set(Prefs.AUDIO_LATENCY_MS, (audioLatencyMs - 50).coerceIn(0, 1000)) },
                            onRight = { viewModel.set(Prefs.AUDIO_LATENCY_MS, (audioLatencyMs + 50).coerceIn(0, 1000)) }
                        )
                    )
                }
            }

            SettingSwitch(
                title = stringResource(R.string.setting_audio_auto_buffer),
                description = stringResource(R.string.setting_audio_auto_buffer_desc),
                checked = audioAutoBuffer,
                onCheckedChange = { viewModel.set(Prefs.AUDIO_AUTO_BUFFER, it) }
            )

            if (audioAutoBuffer) {
                val maxStep = Prefs.AUDIO_ADAPTIVE_STEP.range.last
                var stepVal by remember(audioAdaptiveStep) { mutableFloatStateOf(audioAdaptiveStep.toFloat()) }
                val step = stepVal.roundToInt().coerceIn(0, maxStep)
                SliderCard(
                    value = stringResource(
                        R.string.audio_adaptive_value,
                        Prefs.ADAPTIVE_PERCENTILES[step],
                        stringArrayResource(R.array.audio_adaptive_step_names)[step]
                    )
                ) {
                    Slider(
                        value = stepVal,
                        onValueChange = { stepVal = it },
                        onValueChangeFinished = { viewModel.set(Prefs.AUDIO_ADAPTIVE_STEP, stepVal.roundToInt()) },
                        valueRange = 0f..maxStep.toFloat(),
                        steps = maxStep - 1,
                        colors = atvSliderColors(),
                        modifier = Modifier.weight(1f).dpadFocus().dpadAdjust(
                            onLeft = { viewModel.set(Prefs.AUDIO_ADAPTIVE_STEP, (audioAdaptiveStep - 1).coerceIn(0, maxStep)) },
                            onRight = { viewModel.set(Prefs.AUDIO_ADAPTIVE_STEP, (audioAdaptiveStep + 1).coerceIn(0, maxStep)) }
                        )
                    )
                }
            } else {
                SettingTextField(
                    label = stringResource(R.string.setting_audio_cushion_ms),
                    value = audioCushionMs.toString(),
                    onCommit = { viewModel.set(Prefs.AUDIO_CUSHION_MS, it.toInt()) },
                    description = stringResource(R.string.setting_audio_cushion_ms_desc),
                    range = Prefs.AUDIO_CUSHION_MS.range
                )
            }

            SettingTextField(
                label = stringResource(R.string.setting_oboe_buffer_frames),
                value = oboeBufferFrames.toString(),
                onCommit = { viewModel.set(Prefs.OBOE_BUFFER_FRAMES, it.toInt()) },
                description = stringResource(R.string.setting_oboe_buffer_frames_desc),
                range = Prefs.OBOE_BUFFER_FRAMES.range
            )


            SettingSwitch(
                title = stringResource(R.string.setting_benchmark_log),
                description = stringResource(R.string.setting_benchmark_log_desc),
                checked = benchmarkLog,
                onCheckedChange = { viewModel.set(Prefs.BENCHMARK_LOG, it) }
            )
        }
    }
}

private fun canAutoLaunch(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

// overlay settings screen may not exist on some builds
private fun _openOverlaySettings(ctx: Context) {
    val overlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    try {
        ctx.startActivity(overlay.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(ctx.appSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Eyebrow(title, Modifier.padding(start = Atv.Space2, top = Atv.Space4, bottom = Atv.Space1))
}

/** Butaca card for settings that hold chips or fields: title, description, then [content]. */
@Composable
private fun SettingCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .card(Atv.ShapeRow)
            .padding(horizontal = Atv.Space4, vertical = Atv.Space3),
        verticalArrangement = Arrangement.spacedBy(Atv.Space1),
    ) {
        Text(title, style = AtvType.RowTitle, color = Atv.Ink)
        Text(description, style = AtvType.RowSubtitle, color = Atv.InkMuted)
        Spacer(Modifier.height(Atv.Space1))
        content()
    }
}

/** A slider and its value in a Butaca card (the slider passes itself in as [slider]). */
@Composable
private fun SliderCard(value: String, slider: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .card(Atv.ShapeRow)
            .padding(horizontal = Atv.Space4, vertical = Atv.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Atv.Space3),
    ) {
        slider()
        Text(value, style = AtvType.RowValue, color = Atv.Palomita)
    }
}

@Composable
private fun atvSliderColors() = SliderDefaults.colors(
    thumbColor = Atv.Crema,
    activeTrackColor = Atv.Zagalo,
    activeTickColor = Atv.OnZagalo,
    inactiveTrackColor = Atv.Pasillo,
    inactiveTickColor = Atv.Lavanda,
)

/** Text field colours of the night room: Butaca fill, Pasillo border, Palomita when focused. */
@Composable
fun atvFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Atv.Crema,
    unfocusedTextColor = Atv.Crema,
    focusedContainerColor = Atv.Butaca,
    unfocusedContainerColor = Atv.Butaca,
    focusedBorderColor = Atv.Palomita,
    unfocusedBorderColor = Atv.Pasillo,
    focusedLabelColor = Atv.Palomita,
    unfocusedLabelColor = Atv.Lavanda,
    cursorColor = Atv.Palomita,
    focusedSupportingTextColor = Atv.Lavanda,
    unfocusedSupportingTextColor = Atv.Lavanda,
)

/** Pill chip: ButacaAlta at rest, coral when selected, Palomita outline under the remote's focus. */
@Composable
private fun AtvChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = AtvType.CaptionStrong) },
        shape = Atv.ShapePill,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Atv.ButacaAlta,
            labelColor = Atv.Crema,
            selectedContainerColor = Atv.Zagalo,
            selectedLabelColor = Atv.OnZagalo,
        ),
        modifier = Modifier.dpadFocus(Atv.ShapePill),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingResolution(
    value: String,
    onValueChange: (String) -> Unit
) {
    val presets = listOf(
        Prefs.AUTO to stringResource(R.string.chip_auto),
        "1280x720" to "1280x720",
        "1920x1080" to "1920x1080",
        "3840x2160" to "3840x2160"
    )
    val devicePresets = listOf(
        Prefs.PORTRAIT to stringResource(R.string.chip_device_portrait),
        Prefs.LANDSCAPE to stringResource(R.string.chip_device_landscape)
    )
    val isPreset = (presets + devicePresets).any { it.first == value }
    var editing by remember { mutableStateOf(false) }
    val parts = if (!isPreset && value.contains("x")) value.split("x", limit = 2) else listOf("", "")
    var width by remember(value) { mutableStateOf(if (isPreset) "" else parts[0]) }
    var height by remember(value) { mutableStateOf(if (isPreset) "" else parts.getOrElse(1) { "" }) }

    SettingCard(stringResource(R.string.setting_resolution), stringResource(R.string.setting_resolution_desc)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun presetChip(key: String, label: String) = AtvChip(
                selected = value == key && !editing,
                label = label,
                onClick = {
                    editing = false
                    width = ""; height = ""
                    onValueChange(key)
                }
            )
            presets.forEach { (key, label) -> presetChip(key, label) }
            AtvChip(
                selected = !isPreset || editing,
                label = stringResource(R.string.chip_custom),
                onClick = { editing = true }
            )
            devicePresets.forEach { (key, label) -> presetChip(key, label) }
        }
        if (editing || !isPreset) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.setting_resolution_width)) },
                    shape = Atv.ShapeRow,
                    colors = atvFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.setting_resolution_height)) },
                    shape = Atv.ShapeRow,
                    colors = atvFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = doneAndHide {
                        val w = width.toIntOrNull()
                        val h = height.toIntOrNull()
                        if (w != null && w > 0 && h != null && h > 0) {
                            onValueChange("${w}x${h}")
                            editing = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun doneAndHide(commit: () -> Unit): KeyboardActions {
    val keyboard = LocalSoftwareKeyboardController.current
    return KeyboardActions(onDone = { commit(); keyboard?.hide() })
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    description: String? = null,
    range: IntRange? = null
) {
    var text by remember(value) { mutableStateOf(value) }
    val valid = range == null || text.toIntOrNull()?.let { it in range } == true
    fun save() {
        if (valid && text != value) onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = if (range == null) it else it.filter { c -> c.isDigit() }.take(6) },
        label = { Text(label) },
        supportingText = description?.let { { Text(it) } },
        singleLine = true,
        isError = !valid,
        textStyle = AtvType.Callout,
        shape = Atv.ShapeRow,
        colors = atvFieldColors(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (range == null) KeyboardType.Text else KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = doneAndHide(::save),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) save() }
    )
}

@Composable
private fun SettingChipField(
    title: String,
    description: String,
    value: String,
    presets: List<Pair<String, String>>,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val isPreset = presets.any { it.first == value }
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(if (isPreset) "" else value) }

    SettingCard(title, description) {
        ChipRow(if (editing) "" else value, presets, { editing = false; text = ""; onValueChange(it) }) {
            AtvChip(
                selected = !isPreset || editing,
                label = stringResource(R.string.chip_custom),
                onClick = { editing = true }
            )
        }
        if (editing || !isPreset) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(placeholder) },
                shape = Atv.ShapeRow,
                colors = atvFieldColors(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = doneAndHide {
                    if (text.isNotBlank()) {
                        onValueChange(text)
                        editing = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            AtvChip(selected = value == key, label = label, onClick = { onSelect(key) })
        }
        trailing()
    }
}

@Composable
private fun SettingChips(
    title: String,
    description: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    SettingCard(title, description) {
        ChipRow(value, options, onValueChange)
    }
}

/** On/off setting: a [SettingRow] with a [Toggle]; the whole description is shown. */
@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingRow(
        title = title,
        subtitle = description,
        subtitleLines = Int.MAX_VALUE,
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
    ) { Toggle(checked) }
}

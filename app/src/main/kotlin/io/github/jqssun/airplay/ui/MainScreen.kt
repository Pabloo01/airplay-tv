package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.screens.AdvancedSettingsScreen
import io.github.jqssun.airplay.ui.screens.AudioScreen
import io.github.jqssun.airplay.ui.screens.ConnectionLostOverlay
import io.github.jqssun.airplay.ui.screens.DevicesScreen
import io.github.jqssun.airplay.ui.screens.IdleScreen
import io.github.jqssun.airplay.ui.screens.LogsRoute
import io.github.jqssun.airplay.ui.screens.MirroringOverlay
import io.github.jqssun.airplay.ui.screens.NoNetworkScreen
import io.github.jqssun.airplay.ui.screens.PinScreen
import io.github.jqssun.airplay.ui.screens.RequestScreen
import io.github.jqssun.airplay.ui.screens.Screensaver
import io.github.jqssun.airplay.ui.screens.SettingsFocus
import io.github.jqssun.airplay.ui.screens.SettingsHome
import io.github.jqssun.airplay.ui.screens.rememberLanAvailable
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay

private enum class Route { HOME, SETTINGS, DEVICES, ADVANCED, LOGS }

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onLocalNetwork: () -> Unit = {}
) {
    val pin by viewModel.pinCode.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val connectionLost by viewModel.connectionLost.collectAsState()
    val clientName by viewModel.clientName.collectAsState()

    val activity = LocalContext.current as? Activity
    val immersive = mirroringActive || connectionLost != null
    LaunchedEffect(immersive) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val state by viewModel.serverState.collectAsState()
    val localNetwork by viewModel.localNetwork.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val request by viewModel.clientRequest.collectAsState()
    val devices by viewModel.allowedDevices.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val lanAvailable = rememberLanAvailable()
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    // where we came from, so a screen we return to can put focus back on the row that was used
    var previous by rememberSaveable { mutableStateOf(Route.HOME) }
    fun go(to: Route) {
        previous = route
        route = to
    }
    var inputTick by remember { mutableIntStateOf(0) }
    var screensaver by remember { mutableStateOf(false) }

    val mirroring = state == ServerState.RUNNING && mirroringActive && !audioOnly
    val lost = connectionLost.takeUnless { mirroring }
    // any connected sender that is not mirroring gets the audio screen: playing, paused, or
    // "waiting for audio" (just connected, sender app closed, or mirroring just ended)
    val audio = connections > 0 && !mirroring
    val home = route == Route.HOME && request == null && pin == null && !mirroring && lost == null && !audio

    LaunchedEffect(home, inputTick) {
        screensaver = false
        if (home) {
            delay(SCREENSAVER_AFTER_MS)
            screensaver = true
        }
    }

    BackHandler(enabled = route != Route.HOME) {
        go(
            when (route) {
                Route.LOGS -> Route.ADVANCED
                Route.DEVICES, Route.ADVANCED -> Route.SETTINGS
                else -> Route.HOME
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Atv.Bg)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                inputTick++
                if (screensaver) {
                    screensaver = false
                    true
                } else false
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        inputTick++
                    }
                }
            }
    ) {
        if (mirroring || lost != null) {
            // full screen; VideoPipeline aspect-fits and crops black bars itself
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                VideoSurfaceView(onSurfaceAvailable = onSurfaceAvailable, onSurfaceDestroyed = onSurfaceDestroyed)
            }
            if (debugEnabled && mirroring) {
                DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(Atv.Space4))
            }
        }
        val pending = request
        when {
            pending != null -> RequestScreen(pending, onAnswer = viewModel::answerClientRequest)
            pin != null -> PinScreen(pin!!, clientName, onCancel = viewModel::dismissPin)
            lost != null -> ConnectionLostOverlay(lost, onHome = viewModel::dismissConnectionLost)
            mirroring -> MirroringOverlay(clientName, onStop = viewModel::disconnectSenders)
            audio -> AudioScreen(viewModel, clientName, inputTick)
            route == Route.SETTINGS -> SettingsHome(
                viewModel,
                onOpenDevices = { go(Route.DEVICES) },
                onOpenAdvanced = { go(Route.ADVANCED) },
                focusOn = when (previous) {
                    Route.DEVICES -> SettingsFocus.DEVICES
                    Route.ADVANCED, Route.LOGS -> SettingsFocus.ADVANCED
                    else -> SettingsFocus.TOP
                },
            )
            route == Route.DEVICES -> DevicesScreen(
                devices,
                onRemove = viewModel::removeAllowedDevice,
                onRemoveAll = viewModel::clearAllowedDevices,
                onBack = { go(Route.SETTINGS) },
            )
            route == Route.ADVANCED -> AdvancedSettingsScreen(
                viewModel,
                onBack = { go(Route.SETTINGS) },
                onOpenLogs = { go(Route.LOGS) },
            )
            route == Route.LOGS -> LogsRoute(viewModel)
            !lanAvailable && connections == 0 -> NoNetworkScreen(onOpenNetworkSettings = { context.openNetworkSettings() })
            screensaver -> Screensaver(serverName)
            else -> IdleScreen(
                state = state,
                localNetwork = localNetwork,
                serverName = serverName,
                onStart = viewModel::startServer,
                onLocalNetwork = onLocalNetwork,
                onOpenSettings = { go(Route.SETTINGS) },
            )
        }
    }
}

private fun Context.openNetworkSettings() {
    val wifi = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(wifi)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val SCREENSAVER_AFTER_MS = 120_000L

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val style = MaterialTheme.typography.labelSmall
        val headingStyle = style.copy(fontWeight = FontWeight.Bold)
        val color = Color.White.copy(alpha = 0.9f)
        val headingColor = Color(0xFF80D8FF) // cyan
        val context = LocalContext.current
        debugOverlaySections(context, info).forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(section.title.uppercase(), style = headingStyle, color = headingColor)
            section.lines.forEach { Text(it, style = style, color = color) }
        }
    }
}

private data class DebugSection(val title: String, val lines: List<String>)

private fun debugOverlaySections(context: Context, info: DebugInfo): List<DebugSection> = buildList {
    buildList {
        if (info.videoCodec.isNotEmpty()) {
            add(context.getString(R.string.debug_video, info.videoCodec, info.videoRes))
            add(context.getString(R.string.debug_fps_bitrate, info.videoFps, info.bitrateStr))
            add(context.getString(R.string.debug_frames_drops, info.videoFrames, info.droppedFrames))
            add(context.getString(R.string.debug_jitter, info.jitterStr))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_video), it)) }

    buildList {
        if (info.audioCodec.isNotEmpty()) add(context.getString(R.string.debug_audio, info.audioCodec, info.serverVolume, info.clientVolume))
        info.audio?.let { a ->
            add(context.getString(R.string.debug_audio_buffer, a.backlogMs, a.tunedCushionMs))
            add(context.getString(R.string.debug_audio_glitch, a.trims, a.drops, a.silences, a.underruns, a.xrun))
            add(context.getString(R.string.debug_audio_decode, formatDecode(a.decodeMeanUs, a.decodeMaxUs, a.decodeHeld, a.decodeErrors)))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_audio), it)) }

    add(DebugSection(context.getString(R.string.debug_section_network), listOf(context.getString(R.string.debug_clients, info.connections))))
}

// "mean/max ms held=N", or "held=N" before first latency window lands
private fun formatDecode(meanUs: Int, maxUs: Int, held: Int, errors: Int): String =
    (if (meanUs == 0) "held=$held"
    else "%.1f/%.1f ms held=%d".format(meanUs / 1000.0, maxUs / 1000.0, held)) + " errs=$errors"

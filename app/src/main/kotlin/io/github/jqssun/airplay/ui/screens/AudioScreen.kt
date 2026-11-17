package io.github.jqssun.airplay.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.audio.VolumeBroadcast
import io.github.jqssun.airplay.ui.design.ChipKind
import io.github.jqssun.airplay.ui.design.FloorShadow
import io.github.jqssun.airplay.ui.design.RoundIconButton
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.StatusChip
import io.github.jqssun.airplay.ui.design.Pepe
import io.github.jqssun.airplay.ui.design.PepeMood
import io.github.jqssun.airplay.ui.design.TrackBar
import io.github.jqssun.airplay.ui.design.Wall
import io.github.jqssun.airplay.ui.design.card
import io.github.jqssun.airplay.ui.dpadAdjust
import io.github.jqssun.airplay.ui.isTv
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun AudioScreen(viewModel: MainViewModel, clientName: String, inputTick: Int) {
    val track by viewModel.trackInfo.collectAsState()
    val streaming by viewModel.audioStreaming.collectAsState()
    // connected but no stream (sender app closed, or mirroring just ended): nothing to control
    val player = viewModel.dacpPlayer?.takeIf { streaming }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(player) {
        while (player != null) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.takeIf { it > 0 } ?: track.durationMs
            playing = player.playWhenReady
            delay(500)
        }
    }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(inputTick) {
        dimmed = false
        delay(DIM_AFTER_MS)
        dimmed = true
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(player != null) { if (player != null) playFocus.requestFocusUntilLanded() }
    val sender = clientName.ifBlank { stringResource(R.string.device_generic) }
    val tv = isTv()
    val chip = stringResource(if (streaming) R.string.chip_audio else R.string.chip_audio_idle, sender)
    val mood = if (streaming) PepeMood.MUSICA else PepeMood.HOLA
    // without a DACP player the length still comes with the track metadata
    val totalMs = durationMs.takeIf { it > 0 } ?: track.durationMs
    val info: @Composable ColumnScope.() -> Unit = if (!streaming) {
        {
            Text(stringResource(R.string.waiting_for_playback), style = AtvType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!tv) VolumeControls(viewModel)
        }
    } else {
        {
            Text(
                track.title.ifEmpty { stringResource(R.string.audio_untitled) },
                style = AtvType.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = AtvType.BodyLarge, color = Atv.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TrackBar(if (totalMs > 0) positionMs / totalMs.toFloat() else 0f, Modifier.fillMaxWidth(), height = 8.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTrackTime(positionMs), style = AtvType.Footnote, color = Atv.InkMuted)
                    if (totalMs > 0) Text(formatTrackTime(totalMs), style = AtvType.Footnote, color = Atv.InkMuted)
                }
            }
            if (player != null) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Atv.Space4),
                ) {
                    RoundIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.cd_previous), { player.seekToPrevious() })
                    RoundIconButton(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(R.string.cd_play_pause),
                        {
                            if (playing) player.pause() else player.play()
                            playing = !playing
                        },
                        modifier = Modifier.focusRequester(playFocus),
                        size = 72.dp,
                    )
                    RoundIconButton(Icons.Rounded.SkipNext, stringResource(R.string.cd_next), { player.seekToNext() })
                }
            }
            if (!tv) VolumeControls(viewModel)
        }
    }
    Wall {
        Stage(
            narrow = {
                StatusChip(chip, ChipKind.WAITING, icon = Icons.Rounded.MusicNote)
                Artwork(track.coverArt, Modifier.widthIn(max = 320.dp).fillMaxWidth())
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Atv.Space2), content = info)
                Pepe(mood, size = 140.dp)
            },
        ) {
            StatusChip(
                chip,
                ChipKind.WAITING,
                Modifier.offset(Atv.SafeX, Atv.SafeY).widthIn(max = 640.dp),
                icon = Icons.Rounded.MusicNote,
            )
            Artwork(track.coverArt, Modifier.offset(Atv.SafeX, 96.dp).size(260.dp))
            Column(
                modifier = Modifier.offset(348.dp, 112.dp).width(400.dp),
                verticalArrangement = Arrangement.spacedBy(Atv.Space2),
                content = info,
            )
            FloorShadow(Modifier.offset(730.dp, 430.dp).size(200.dp, 40.dp))
            Pepe(mood, Modifier.offset(740.dp, 262.dp), size = 190.dp)
        }
        val scrim by animateColorAsState(
            if (dimmed && tv) Color.Black.copy(alpha = 0.7f) else Color.Transparent,
            tween(1_200),
            label = "dim",
        )
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

/** 260dp square, radius 28: the cover art, or a music note on ButacaAlta when there is none. */
@Composable
private fun Artwork(cover: Bitmap?, modifier: Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .card(Atv.ShapeLg, Atv.ButacaAlta, border = Atv.Pasillo),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_cover_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(Atv.ShapeLg),
            )
        } else {
            Icon(Icons.Outlined.MusicNote, null, tint = Atv.InkMuted, modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
private fun VolumeControls(viewModel: MainViewModel) {
    val context = LocalContext.current
    val serverVolume = remember { ServerVolumeState(context) }
    DisposableEffect(serverVolume) { serverVolume.handleLifecycle(this) }
    val clientVolume by viewModel.clientVolume.collectAsState()
    val clientAdjustable by viewModel.clientVolumeAdjustable.collectAsState()
    var clientDrag by remember { mutableStateOf<Float?>(null) }
    val clientShown = clientDrag ?: clientVolume
    Column(Modifier.padding(top = Atv.Space6), verticalArrangement = Arrangement.spacedBy(Atv.Space2)) {
        VolumeRow(
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            label = stringResource(R.string.volume_server),
            value = serverVolume.percentage / 100f,
            onValueChange = { serverVolume.update((it * 100).roundToInt()) },
            onStep = { serverVolume.adjust(it > 0) },
        )
        VolumeRow(
            icon = Icons.Rounded.PhoneIphone,
            label = stringResource(R.string.volume_client),
            value = clientShown,
            enabled = clientAdjustable,
            onValueChange = { clientDrag = it; viewModel.setClientVolume(it) },
            onValueChangeFinished = { clientDrag = null },
            onStep = { viewModel.setClientVolume((clientShown + it * CLIENT_VOLUME_STEP).coerceIn(0f, 1f)) },
        )
    }
}

@Composable
private fun VolumeRow(
    icon: ImageVector,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onStep: (Int) -> Unit,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Atv.Space3)) {
        Icon(icon, label, tint = Atv.InkMuted, modifier = Modifier.size(20.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = Atv.Crema,
                activeTrackColor = Atv.Palomita,
                inactiveTrackColor = Atv.Pasillo,
            ),
            modifier = Modifier.weight(1f).dpadAdjust(onLeft = { onStep(-1) }, onRight = { onStep(1) }),
        )
        Text(stringResource(R.string.volume_value, (value * 100).roundToInt()), style = AtvType.CaptionStrong, color = Atv.InkMuted)
    }
}

private const val DIM_AFTER_MS = 120_000L
private const val CLIENT_VOLUME_STEP = 1f / 16

private fun formatTrackTime(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

// receiver STREAM_MUSIC volume as 0..100, kept in sync with volume-key presses
@Stable
private class ServerVolumeState(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    var percentage by mutableIntStateOf(_current())
        private set

    private fun _current() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume

    fun sync() {
        percentage = _current()
    }

    fun update(newPercentage: Int) {
        percentage = newPercentage.coerceIn(0, 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, percentage * maxVolume / 100, _flags())
    }

    fun adjust(up: Boolean) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, _flags())
        sync()
    }

    // surface the system panel when a headset is connected
    private fun _flags() = if (_isHeadsetOn()) AudioManager.FLAG_SHOW_UI else 0

    fun handleLifecycle(scope: DisposableEffectScope): DisposableEffectResult = with(scope) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == VolumeBroadcast.ACTION) sync()
            }
        }
        context.registerReceiver(receiver, IntentFilter(VolumeBroadcast.ACTION))
        onDispose { context.unregisterReceiver(receiver) }
    }

    private fun _isHeadsetOn(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
}

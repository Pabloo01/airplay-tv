package io.github.jqssun.airplay.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.design.ChipKind
import io.github.jqssun.airplay.ui.design.GlassButton
import io.github.jqssun.airplay.ui.design.GlassPanel
import io.github.jqssun.airplay.ui.design.SafeArea
import io.github.jqssun.airplay.ui.design.StatusChip
import io.github.jqssun.airplay.ui.design.glass
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import kotlinx.coroutines.delay

/*
 * Remote handling while a movie is mirrored. Nothing on the remote can end the session by
 * accident:
 * - hidden (the default, also at session start): Back, OK and the D-pad only reveal the overlay
 *   and are swallowed, so they never reach a button;
 * - visible: focus starts on the harmless "Keep watching"; Back hides the overlay;
 * - "Stop" only opens a confirmation whose focus again starts on "Keep watching";
 * - auto-repeat of a held OK / D-pad key is ignored, so holding OK can't walk into a button that
 *   just took focus (a button also needs its own key-down before a key-up clicks it).
 */
@Composable
fun MirroringOverlay(clientName: String, onStop: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    // bumped by any input while visible: restarts the auto-hide countdown
    var activity by remember { mutableIntStateOf(0) }
    fun reveal() {
        visible = true
        activity++
    }
    fun hide() {
        visible = false
        confirming = false
    }
    LaunchedEffect(visible, confirming, activity) {
        if (!visible) return@LaunchedEffect
        delay(if (confirming) CONFIRM_HIDE_MS else OVERLAY_HIDE_MS)
        hide()
    }

    val rootFocus = remember { FocusRequester() }
    val keepFocus = remember { FocusRequester() }
    val confirmKeepFocus = remember { FocusRequester() }
    LaunchedEffect(visible, confirming) {
        when {
            confirming -> confirmKeepFocus.requestFocusUntilLanded()
            visible -> keepFocus.requestFocusUntilLanded()
            else -> rootFocus.requestFocusUntilLanded()
        }
    }
    BackHandler {
        when {
            confirming -> {
                confirming = false
                activity++
            }
            visible -> hide()
            else -> reveal()
        }
    }
    val sender = clientName.ifBlank { stringResource(R.string.device_generic) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.key !in remoteKeys) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                if (!visible) {
                    if (e.type == KeyEventType.KeyDown) reveal()
                    return@onPreviewKeyEvent true
                }
                if (e.type == KeyEventType.KeyDown) activity++
                false
            }
            .pointerInput(Unit) { detectTapGestures { reveal() } },
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (confirming) {
                StopConfirmation(
                    sender = sender,
                    keepFocus = confirmKeepFocus,
                    onKeepWatching = ::hide,
                    onStop = {
                        hide()
                        onStop()
                    },
                )
            } else {
                SafeArea {
                    StatusChip(
                        stringResource(R.string.chip_mirroring, sender),
                        ChipKind.LIVE,
                        Modifier.align(Alignment.TopEnd),
                    )
                    Box(
                        Modifier.align(Alignment.BottomStart).glass(Atv.ShapePill, blur = false)
                            .padding(horizontal = 20.dp, vertical = Atv.Space3)
                    ) {
                        Text(stringResource(R.string.mirroring_help), style = AtvType.Caption, color = Atv.InkMuted)
                    }
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        horizontalArrangement = Arrangement.spacedBy(Atv.Space4),
                    ) {
                        GlassButton(
                            text = stringResource(R.string.btn_keep_watching),
                            icon = Icons.Rounded.PlayArrow,
                            onClick = ::hide,
                            modifier = Modifier.focusRequester(keepFocus),
                        )
                        GlassButton(
                            text = stringResource(R.string.btn_stop_mirroring),
                            icon = Icons.Rounded.Stop,
                            onClick = { confirming = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StopConfirmation(
    sender: String,
    keepFocus: FocusRequester,
    onKeepWatching: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0A0A0C).copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.widthIn(max = 520.dp), strong = true) {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp, vertical = Atv.Space8),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.stop_confirm_title), style = AtvType.Title, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.stop_confirm_desc, sender),
                    style = AtvType.Body,
                    color = Atv.InkMuted,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.padding(top = Atv.Space2),
                    horizontalArrangement = Arrangement.spacedBy(Atv.Space4),
                ) {
                    GlassButton(
                        text = stringResource(R.string.btn_keep_watching),
                        onClick = onKeepWatching,
                        modifier = Modifier.focusRequester(keepFocus),
                    )
                    GlassButton(text = stringResource(R.string.btn_stop_confirm), onClick = onStop)
                }
            }
        }
    }
}

// keys that reveal the hidden overlay; their auto-repeat is ignored. volume, media and home keys
// pass through untouched
private val remoteKeys = setOf(
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
)

private const val OVERLAY_HIDE_MS = 5_000L
private const val CONFIRM_HIDE_MS = 10_000L

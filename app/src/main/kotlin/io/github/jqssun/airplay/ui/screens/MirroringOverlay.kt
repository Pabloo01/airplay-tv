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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.jqssun.airplay.ui.design.PillButton
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.StatusChip
import io.github.jqssun.airplay.ui.design.Zagalo
import io.github.jqssun.airplay.ui.design.ZagaloMood
import io.github.jqssun.airplay.ui.design.card
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import kotlinx.coroutines.delay

/*
 * Remote handling while a movie is mirrored. Nothing on the remote can end the session by
 * accident:
 * - hidden (the default, also at session start): Back, OK and the D-pad only reveal the overlay
 *   and are swallowed, so they never reach a button;
 * - visible: focus starts on the harmless "Keep sharing"; Back hides the overlay;
 * - "Stop" (red) only opens a confirmation whose focus again starts on "Keep sharing";
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
                OverlayControls(sender = sender, keepFocus = keepFocus, onKeepWatching = ::hide, onStop = { confirming = true })
            }
        }
    }
}

/** Live chip top-right; at the bottom a dark band with Zagalo eating popcorn and the two buttons. */
@Composable
private fun OverlayControls(
    sender: String,
    keepFocus: FocusRequester,
    onKeepWatching: () -> Unit,
    onStop: () -> Unit,
) {
    val chip = stringResource(R.string.chip_mirroring, sender)
    val caption: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.overlay_title), style = AtvType.HeadlineSmall)
            Text(stringResource(R.string.overlay_sub), style = AtvType.Caption, color = Atv.InkMuted)
        }
    }
    val keep: @Composable () -> Unit = {
        PillButton(
            text = stringResource(R.string.btn_keep_sharing),
            icon = Icons.Rounded.PlayArrow,
            onClick = onKeepWatching,
            modifier = Modifier.focusRequester(keepFocus),
        )
    }
    val stop: @Composable () -> Unit = {
        PillButton(
            text = stringResource(R.string.btn_stop_mirroring),
            icon = Icons.Rounded.Stop,
            onClick = onStop,
            danger = true,
        )
    }
    Stage(
        narrow = {
            StatusChip(chip, ChipKind.LIVE)
            Column(
                modifier = Modifier.fillMaxWidth().card(Atv.ShapeLg, Atv.ScrimBand).padding(Atv.Space6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Atv.Space4),
            ) {
                Zagalo(ZagaloMood.PALOMITAS, size = 112.dp)
                caption()
                keep()
                stop()
            }
        },
    ) {
        StatusChip(
            chip,
            ChipKind.LIVE,
            Modifier.align(Alignment.TopEnd).padding(top = Atv.SafeY, end = Atv.SafeX).widthIn(max = 520.dp),
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(150.dp).background(Atv.ScrimBand))
        Zagalo(ZagaloMood.PALOMITAS, Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 10.dp), size = 128.dp)
        Box(Modifier.align(Alignment.BottomStart).padding(start = 176.dp, bottom = 52.dp)) { caption() }
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Atv.SafeX, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            keep()
            stop()
        }
    }
}

/** "¿Se acabó la sesión?": sad Zagalo in a panel over the dimmed video; focus starts on keep sharing. */
@Composable
private fun StopConfirmation(
    sender: String,
    keepFocus: FocusRequester,
    onKeepWatching: () -> Unit,
    onStop: () -> Unit,
) {
    val panel: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier
                .card(ConfirmShape, Atv.Butaca, border = Atv.Pasillo)
                .padding(start = 36.dp, end = 36.dp, top = 26.dp, bottom = Atv.Space8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Atv.Space3),
        ) {
            Zagalo(ZagaloMood.TRISTE, size = 150.dp)
            Text(stringResource(R.string.stop_confirm_title), style = AtvType.SectionTitle, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.stop_confirm_desc, sender),
                style = AtvType.Body,
                color = Atv.InkMuted,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PillButton(
                    text = stringResource(R.string.btn_keep_sharing),
                    icon = Icons.Rounded.PlayArrow,
                    onClick = onKeepWatching,
                    modifier = Modifier.focusRequester(keepFocus),
                )
                PillButton(text = stringResource(R.string.btn_stop_confirm), onClick = onStop, danger = true)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Atv.Scrim)) {
        Stage(narrow = { panel(Modifier.fillMaxWidth()) }) {
            panel(Modifier.align(Alignment.TopCenter).padding(top = 70.dp).width(500.dp))
        }
    }
}

private val ConfirmShape = RoundedCornerShape(30.dp)

// keys that reveal the hidden overlay; their auto-repeat is ignored. volume, media and home keys
// pass through untouched
private val remoteKeys = setOf(
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
)

private const val OVERLAY_HIDE_MS = 5_000L
private const val CONFIRM_HIDE_MS = 10_000L

package io.github.jqssun.airplay.ui.screens

import android.text.format.DateFormat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.design.FloorShadow
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.Pepe
import io.github.jqssun.airplay.ui.design.PepeMood
import io.github.jqssun.airplay.ui.design.Wall
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import kotlinx.coroutines.delay

// Offsets (dp) the clock and Pepe travel through together, about one stop a minute.
// They keep the whole group (x 96..830, y 150..500 on the 960x540 stage) on screen.
private val driftPath = listOf(0f to 0f, 100f to -40f, 60f to -120f, -70f to -100f, -60f to 10f, 0f to 0f)

private fun drift(t: Float): Pair<Float, Float> {
    val segments = driftPath.size - 1
    val segment = (t * segments).toInt().coerceIn(0, segments - 1)
    val local = t * segments - segment
    val (x0, y0) = driftPath[segment]
    val (x1, y1) = driftPath[segment + 1]
    return (x0 + (x1 - x0) * local) to (y0 + (y1 - y0) * local)
}

private val NightStars = listOf(
    Star(140, 80, 3f, Atv.Crema, 0.35f),
    Star(420, 48, 4f, Atv.Palomita, 0.3f),
    Star(860, 120, 3f, Atv.Crema, 0.3f),
    Star(300, 300, 3f, Atv.Crema, 0.25f),
)

@Composable
fun Screensaver(serverName: String) {
    val transition = rememberInfiniteTransition(label = "screensaver")
    val progress = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(DRIFT_LOOP_MS, easing = LinearEasing)), label = "drift")
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    // holds the focus so the first key press reaches MainScreen (which wakes the home screen)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusUntilLanded() }
    val clock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Atv.Space1)) {
            Text(DateFormat.getTimeFormat(context).format(now), style = AtvType.Clock, color = ClockInk)
            Text(stringResource(R.string.screensaver_status, serverName), style = StatusLine, color = Atv.InkFaint)
        }
    }
    // the drift is read at placement time only: no recomposition while it moves
    val shift = Modifier.offset {
        val (dx, dy) = drift(progress.value)
        IntOffset(dx.dp.roundToPx(), dy.dp.roundToPx())
    }

    Wall(color = Atv.Deep) {
        Stage(
            modifier = Modifier.focusRequester(focus).focusable(),
            narrow = {
                Column(
                    modifier = Modifier.offset {
                        val (dx, dy) = drift(progress.value)
                        IntOffset((dx * 0.2f).dp.roundToPx(), (dy * 0.5f).dp.roundToPx())
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Atv.Space6),
                ) {
                    clock()
                    Pepe(PepeMood.DORMIDO, Modifier.alpha(0.85f), size = 160.dp)
                }
            },
        ) {
            Starfield(NightStars)
            Moon(Modifier.offset(820.dp, 40.dp).size(60.dp))
            Box(Modifier.fillMaxSize().then(shift)) {
                Box(Modifier.offset(96.dp, 150.dp)) { clock() }
                FloorShadow(Modifier.offset(600.dp, 440.dp).size(220.dp, 36.dp), color = NightFloor)
                Pepe(PepeMood.DORMIDO, Modifier.offset(610.dp, 250.dp).alpha(0.85f), size = 210.dp)
            }
        }
    }
}

/** Crescent moon from the prototype's 60x60 SVG. */
@Composable
private fun Moon(modifier: Modifier) {
    val path = remember { PathParser().parsePathString(MOON_PATH).toPath() }
    Canvas(modifier) {
        scale(size.width / 60f, pivot = Offset.Zero) { drawPath(path, MoonColor, alpha = 0.6f) }
    }
}

private const val MOON_PATH = "M40 8a24 24 0 1 0 14 34A20 20 0 0 1 40 8z"
private const val DRIFT_LOOP_MS = 300_000

// a touch dimmer than Crema, as in the prototype
private val ClockInk = Color(0xFFE9E1F5)
private val MoonColor = Color(0xFFFFE7A8)
private val NightFloor = Color(0xFF15112A)
private val StatusLine = AtvType.BodyLarge.copy(fontWeight = FontWeight.Bold)

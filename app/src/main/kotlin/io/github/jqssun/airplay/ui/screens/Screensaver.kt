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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.design.FloorShadow
import io.github.jqssun.airplay.ui.design.Stage
import io.github.jqssun.airplay.ui.design.Zagalo
import io.github.jqssun.airplay.ui.design.ZagaloMood
import io.github.jqssun.airplay.ui.design.Wall
import io.github.jqssun.airplay.ui.requestFocusUntilLanded
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import kotlinx.coroutines.delay

// Offsets (dp) the clock and Zagalo travel through together as a closed loop, about one stop a
// minute. They keep the whole group (x 96..830, y 150..500 on the 960x540 stage) on screen.
private val driftPath = listOf(0f to 0f, 100f to -40f, 60f to -120f, -70f to -100f, -60f to 10f)

// closed Catmull-Rom spline through driftPath: passes every stop with no sharp turns, so the
// speed and direction change smoothly instead of snapping at each corner
private fun drift(t: Float): Pair<Float, Float> {
    val n = driftPath.size
    val pos = (t - kotlin.math.floor(t)) * n
    val i = pos.toInt().coerceIn(0, n - 1)
    val u = pos - i
    val (x0, y0) = driftPath[(i - 1 + n) % n]
    val (x1, y1) = driftPath[i]
    val (x2, y2) = driftPath[(i + 1) % n]
    val (x3, y3) = driftPath[(i + 2) % n]
    return catmullRom(x0, x1, x2, x3, u) to catmullRom(y0, y1, y2, y3, u)
}

private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, u: Float): Float {
    val u2 = u * u
    val u3 = u2 * u
    return 0.5f * (2f * p1 + (p2 - p0) * u + (2f * p0 - 5f * p1 + 4f * p2 - p3) * u2 + (3f * p1 - p0 - 3f * p2 + p3) * u3)
}

// moves the content by a fractional pixel amount: rendered offscreen and drawn as a filtered
// texture, so a drift of a few px/s glides instead of stepping one whole pixel at a time
private fun Modifier.glide(offset: () -> Pair<Float, Float>): Modifier = graphicsLayer {
    val (dx, dy) = offset()
    translationX = dx.dp.toPx()
    translationY = dy.dp.toPx()
    compositingStrategy = CompositingStrategy.Offscreen
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
    // the drift is read in the draw layer only: no recomposition or relayout while it moves
    val shift = Modifier.glide { drift(progress.value) }

    Wall(color = Atv.Deep) {
        Stage(
            modifier = Modifier.focusRequester(focus).focusable(),
            narrow = {
                Column(
                    modifier = Modifier.glide {
                        val (dx, dy) = drift(progress.value)
                        (dx * 0.2f) to (dy * 0.5f)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Atv.Space6),
                ) {
                    clock()
                    Zagalo(ZagaloMood.DORMIDO, Modifier.alpha(0.85f), size = 160.dp)
                }
            },
        ) {
            Starfield(NightStars)
            Moon(Modifier.offset(820.dp, 40.dp).size(60.dp))
            Box(Modifier.fillMaxSize().then(shift)) {
                Box(Modifier.offset(96.dp, 150.dp)) { clock() }
                FloorShadow(Modifier.offset(600.dp, 440.dp).size(220.dp, 36.dp), color = NightFloor)
                Zagalo(ZagaloMood.DORMIDO, Modifier.offset(610.dp, 250.dp).alpha(0.85f), size = 210.dp)
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

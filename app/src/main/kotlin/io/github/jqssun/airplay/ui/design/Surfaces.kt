package io.github.jqssun.airplay.ui.design

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.ui.theme.Atv

/** Full-screen background (Noche by default; [Atv.Deep] for the screensaver). Content ink is Crema. */
@Composable
fun Wall(
    modifier: Modifier = Modifier,
    color: Color = Atv.Bg,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(color)) {
        CompositionLocalProvider(LocalContentColor provides Atv.Ink) { content() }
    }
}

fun BoxWithConstraintsScope.isWide(): Boolean = maxWidth >= 720.dp

/** The prototypes' canvas: their px are this stage's dp. */
val StageWidth = 960.dp
val StageHeight = 540.dp

/**
 * The prototypes' 960x540 canvas, so screens place things at the prototypes' coordinates
 * (`Modifier.offset(x, y)` from the top-left, or align + padding for right/bottom anchors).
 *
 * On the 960x540 dp TV nothing is scaled. Other landscape windows scale [LocalDensity] so the
 * canvas fits one side exactly and the other side grows (a 20:9 phone is 1200x540 inside), so
 * whatever is anchored to the right or bottom edge stays there. Portrait windows (phones) get
 * [narrow] instead: the same pieces stacked in a scrolling column.
 */
@Composable
fun Stage(
    narrow: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // overlays over the video have no Wall: the ink is Crema here too
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (maxWidth < maxHeight) {
            CompositionLocalProvider(LocalContentColor provides Atv.Ink) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Atv.Space6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Atv.Space4),
                    content = narrow,
                )
            }
        } else {
            val outer = LocalDensity.current
            val scale = minOf(maxWidth / StageWidth, maxHeight / StageHeight)
            CompositionLocalProvider(
                LocalDensity provides Density(outer.density * scale, outer.fontScale),
                LocalContentColor provides Atv.Ink,
            ) {
                Box(Modifier.fillMaxSize(), content = content)
            }
        }
    }
}

/** TV safe area: 48 x 27 dp on the 960x540 canvas, 16 dp on narrow (phone) screens. */
@Composable
fun SafeArea(modifier: Modifier = Modifier, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = isWide()
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(
                horizontal = if (wide) Atv.SafeX else Atv.Space4,
                vertical = if (wide) Atv.SafeY else Atv.Space4,
            ),
            content = content,
        )
    }
}

/** Solid card: Butaca fill by default, optional 1dp border (Pasillo on panels). */
fun Modifier.card(
    shape: Shape = Atv.ShapeLg,
    color: Color = Atv.Butaca,
    border: Color? = null,
    borderWidth: Dp = 1.dp,
): Modifier {
    val body = clip(shape).background(color)
    return if (border != null) body.border(borderWidth, border, shape) else body
}

/** Big rounded panel (request, stop confirmation, dialogs): Butaca, 1dp Pasillo border, radius 28. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.card(Atv.ShapeLg, Atv.Butaca, border = Atv.Pasillo), content = content)
}

/**
 * How a focusable surface looks at rest and under the remote's focus. The ring is drawn outside
 * the shape, like the prototypes' `box-shadow: 0 0 0 Npx` (no blur).
 */
@Immutable
class FocusStyle(
    val color: Color,
    val contentColor: Color,
    val focusedColor: Color,
    val focusedContentColor: Color,
    val ring: Color,
    val ringWidth: Dp,
) {
    companion object {
        private val glow = Atv.Palomita.copy(alpha = Atv.RingGlowAlpha)

        /** Pill button: ButacaAlta + Crema; focused Palomita + dark ink + soft yellow glow. */
        val Button = FocusStyle(Atv.ButacaAlta, Atv.Crema, Atv.Palomita, Atv.OnPalomita, glow, Atv.RingButton)

        /** Destructive button: Stop red + white; focused Crema + red ink + red glow. */
        val Danger = FocusStyle(
            Atv.Stop, Color.White, Atv.Crema, Atv.StopInk,
            Atv.Stop.copy(alpha = Atv.RingDangerAlpha), Atv.RingButton,
        )

        /** Round icon button (prev / play / next): like [Button] with a wider glow. */
        val Round = FocusStyle(Atv.ButacaAlta, Atv.Crema, Atv.Palomita, Atv.OnPalomita, glow, Atv.RingRound)

        /** Setting / device row: Butaca; focused Crema + Pepe ink + solid 4dp Palomita ring. */
        val Row = FocusStyle(Atv.Butaca, Atv.Crema, Atv.Crema, Atv.PepeInk, Atv.Palomita, Atv.RingRow)
    }
}

/**
 * Anything the remote can focus and click. Provides the right [LocalContentColor] for its state,
 * so `Text`/`Icon` inside pick it up; `content` also gets `focused` for anything else.
 *
 * @param radius corner radius; null = pill (or circle when square).
 * @param focusScale scale while focused (1f for rows).
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp? = null,
    style: FocusStyle = FocusStyle.Button,
    focusScale: Float = Atv.ScaleFocus,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    // read from the focus system, not from the interaction flow: a Focus interaction emitted before
    // this composable starts collecting (focus requested in the frame it appears) would be lost
    var focused by remember { mutableStateOf(false) }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = when {
            pressed -> Atv.ScalePressed
            focused -> focusScale
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "focusScale",
    )
    val ring = animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS), label = "focusRing")
    val fill = animateColorAsState(if (focused) style.focusedColor else style.color, tween(FOCUS_MS), label = "fill")
    val ink by animateColorAsState(
        if (focused) style.focusedContentColor else style.contentColor,
        tween(FOCUS_MS),
        label = "ink",
    )
    val shape = if (radius != null) RoundedCornerShape(radius) else Atv.ShapePill
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .drawBehind {
                val alpha = ring.value
                if (alpha > 0f) {
                    val w = style.ringWidth.toPx()
                    val r = (radius?.toPx() ?: (size.minDimension / 2f)) + w
                    drawRoundRect(
                        color = style.ring,
                        topLeft = Offset(-w, -w),
                        size = Size(size.width + 2 * w, size.height + 2 * w),
                        cornerRadius = CornerRadius(r),
                        alpha = alpha,
                    )
                }
            }
            .clip(shape)
            .drawBehind { drawRect(fill.value) }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) { content(focused) }
    }
}

/** True when the system animator duration scale is 0 ("remove animations"): skip decorative motion. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private const val FOCUS_MS = 150

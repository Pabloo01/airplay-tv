package io.github.jqssun.airplay.ui.design

import androidx.annotation.StringRes
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.theme.Fredoka

/** Pepe, the TV with bunny-ear antennas who lives in the app: one face per moment. */
enum class PepeMood(@param:StringRes val label: Int) {
    /** Waiting on the home screen: smiles and waves. */
    HOLA(R.string.cd_pepe_hola),

    /** "Who's knocking?": a new device asks to connect. */
    CURIOSO(R.string.cd_pepe_curioso),

    /** Watching the movie: 3D glasses and popcorn. */
    PALOMITAS(R.string.cd_pepe_palomitas),

    /** Stop confirmation. */
    TRISTE(R.string.cd_pepe_triste),

    /** Audio playing: headphones and notes. */
    MUSICA(R.string.cd_pepe_musica),

    /** Connection lost. */
    MAREADO(R.string.cd_pepe_mareado),

    /** Screensaver. */
    DORMIDO(R.string.cd_pepe_dormido),

    /** No network: looking for Wi-Fi with a magnifier. */
    BUSCANDO(R.string.cd_pepe_buscando),

    /** Settings. */
    HERRAMIENTAS(R.string.cd_pepe_herramientas),

    /** PIN code and allowed devices: holds his list. */
    LISTA(R.string.cd_pepe_lista),
}

/**
 * Pepe, drawn from the prototype's 200x200 SVG. He bobs (or wobbles, or dances) and waves on
 * [PepeMood.HOLA]; all motion stops when the system's animator duration scale is 0.
 *
 * The art may poke a little outside [size] (antenna tips, the "?", the waving hand): leave a few
 * dp of room around him. His own soft black contact shadow is included; the coloured floor
 * ellipse of each screen is a separate [FloorShadow].
 *
 * @param contentDescription defaults to the mood's label; pass null when the screen already says it.
 */
@Composable
fun Pepe(
    mood: PepeMood,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    contentDescription: String? = stringResource(mood.label),
) {
    val art = remember(mood) { PepeArt.forMood(mood) }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val glyphs = remember(mood, measurer) {
        PepeArt.glyphsFor(mood).map { g ->
            val layout = measurer.measure(
                text = g.text,
                style = TextStyle(
                    color = g.color,
                    fontFamily = Fredoka,
                    fontWeight = FontWeight(g.weight),
                    fontSize = g.size.sp,
                ),
                softWrap = false,
                density = UnitDensity,
            )
            PlacedGlyph(layout, Offset(g.x, g.y - layout.firstBaseline))
        }
    }

    val still = rememberReducedMotion()
    val motion = motionOf(mood)
    val bob: State<Float>
    val wave: State<Float>
    if (still) {
        bob = remember { mutableFloatStateOf(0f) }
        wave = bob
    } else {
        val transition = rememberInfiniteTransition(label = "pepe")
        bob = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(motion.halfMs, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "pepeBob",
        )
        wave = if (mood == PepeMood.HOLA) {
            transition.animateFloat(
                initialValue = 0f,
                targetValue = WAVE_DEGREES,
                animationSpec = infiniteRepeatable(tween(WAVE_HALF_MS, easing = EaseInOutSine), RepeatMode.Reverse),
                label = "pepeWave",
            )
        } else {
            remember { mutableFloatStateOf(0f) }
        }
    }

    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else Modifier
    Box(modifier.size(size).then(described)) {
        Canvas(Modifier.matchParentSize()) {
            inUnits { drawOp(PepeArt.contactShadow) }
        }
        // the bob is a layer translation: nothing is re-recorded while he moves
        Box(
            Modifier.matchParentSize().graphicsLayer {
                if (!still) {
                    val unit = this.size.width / VIEWBOX
                    val t = bob.value
                    translationX = (motion.fromX + (motion.toX - motion.fromX) * t) * unit
                    translationY = (motion.fromY + (motion.toY - motion.fromY) * t) * unit
                }
            }
        ) {
            Canvas(Modifier.matchParentSize()) {
                inUnits {
                    art.forEach { drawOp(it) }
                    glyphs.forEach { drawText(it.layout, topLeft = it.topLeft) }
                }
            }
            if (mood == PepeMood.HOLA) {
                Canvas(
                    Modifier.matchParentSize().graphicsLayer {
                        rotationZ = wave.value
                        transformOrigin = WAVE_PIVOT
                    }
                ) {
                    inUnits { PepeArt.wave.forEach { drawOp(it) } }
                }
            }
        }
    }
}

// ---------- motion ----------

/** Whole-body loop from (from) to (to) and back, [halfMs] each way (the prototype's SMIL values). */
private class Motion(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val halfMs: Int)

private val defaultMotion = Motion(0f, 0f, 0f, -5f, 1_200)
private val motions = mapOf(
    PepeMood.DORMIDO to Motion(0f, 0f, 0f, -3f, 2_000),
    PepeMood.MAREADO to Motion(-3f, 0f, 3f, 0f, 800),
    PepeMood.MUSICA to Motion(0f, 0f, 0f, -9f, 350),
    PepeMood.PALOMITAS to Motion(0f, 0f, 0f, -2f, 1_500),
)

private fun motionOf(mood: PepeMood) = motions[mood] ?: defaultMotion

private const val VIEWBOX = 200f
private const val WAVE_DEGREES = -18f
private const val WAVE_HALF_MS = 550
private val WAVE_PIVOT = TransformOrigin(166f / VIEWBOX, 124f / VIEWBOX)

// measure glyphs in viewBox units: 1sp = 1 unit
private val UnitDensity = Density(1f, 1f)

private inline fun DrawScope.inUnits(block: DrawScope.() -> Unit) =
    scale(size.width / VIEWBOX, pivot = Offset.Zero, block)

// ---------- art ----------

private class Op(val path: Path, val color: Color, val alpha: Float = 1f, val stroke: Stroke? = null)

private fun DrawScope.drawOp(op: Op) {
    if (op.stroke != null) drawPath(op.path, op.color, op.alpha, op.stroke)
    else drawPath(op.path, op.color, op.alpha)
}

private class Glyph(val text: String, val x: Float, val y: Float, val size: Int, val weight: Int, val color: Color)

private class PlacedGlyph(val layout: TextLayoutResult, val topLeft: Offset)

/** The prototype's `<g display=...>` groups, in paint order, with the moods that show them. */
private class Part(val moods: Set<PepeMood>?, val ops: List<Op>)

/**
 * Paths of `Pepe.dc.html` (viewBox 200x200), parsed once. Colours are the prototype's; the face
 * ink is Atv.PepeInk, the body Atv.Pepe.
 */
private object PepeArt {
    private val coral = Color(0xFFFF6B5B)
    private val coralDark = Color(0xFFD9483B)
    private val coralLight = Color(0xFFFF8D80)
    private val blush = Color(0xFFFF9E8F)
    private val screen = Color(0xFFFFF3DE)
    private val ink = Color(0xFF2A1633)
    private val yellow = Color(0xFFFFC94A)
    private val paleYellow = Color(0xFFFFE2A3)
    private val lavender = Color(0xFFC8BFDA)
    private val night = Color(0xFF16122B)
    private val steel = Color(0xFFB9C1D1)

    val contactShadow = Op(oval(100f, 191f, 50f, 7f), Color.Black, alpha = 0.28f)

    private val allButMareado = PepeMood.entries.toSet() - PepeMood.MAREADO

    private val parts: List<Part> by lazy {
        listOf(
            Part(null, listOf(fill(rrect(68f, 166f, 20f, 20f, 10f), coralDark), fill(rrect(112f, 166f, 20f, 20f, 10f), coralDark))),
            Part(
                allButMareado,
                listOf(
                    stroke("M86 54 L66 22", yellow, 5f),
                    stroke("M114 54 L136 18", yellow, 5f),
                    fill(circle(65f, 20f, 7f), yellow),
                    fill(circle(137f, 16f, 7f), yellow),
                ),
            ),
            Part(
                setOf(PepeMood.MAREADO),
                listOf(
                    stroke("M86 54 Q74 36 62 42 Q52 48 48 34", yellow, 5f),
                    stroke("M114 54 Q130 44 140 52 Q150 60 158 46", yellow, 5f),
                    fill(circle(48f, 32f, 7f), yellow),
                    fill(circle(159f, 44f, 7f), yellow),
                ),
            ),
            Part(setOf(PepeMood.MUSICA), listOf(stroke("M40 104 Q40 38 100 38 Q160 38 160 104", ink, 8f))),
            Part(
                null,
                listOf(
                    fill(rrect(30f, 50f, 140f, 124f, 32f), coral),
                    fill(rrect(44f, 56f, 56f, 6f, 3f), coralLight),
                    fill(rrect(46f, 68f, 108f, 82f, 22f), screen),
                    stroke("M54 159 h26 M54 166 h18", coralDark, 3.5f),
                    fill(circle(136f, 162f, 5.5f), yellow),
                    fill(circle(120f, 162f, 5.5f), paleYellow),
                    fill(oval(64f, 122f, 8f, 5f), blush, 0.8f),
                    fill(oval(136f, 122f, 8f, 5f), blush, 0.8f),
                ),
            ),
            Part(
                PepeMood.entries.toSet() - PepeMood.DORMIDO - PepeMood.MAREADO,
                listOf(
                    fill(oval(80f, 103f, 8f, 10f), ink),
                    fill(oval(120f, 103f, 8f, 10f), ink),
                    fill(circle(83f, 99f, 3f), Color.White),
                    fill(circle(123f, 99f, 3f), Color.White),
                ),
            ),
            Part(setOf(PepeMood.DORMIDO), listOf(stroke("M72 104 Q80 110 88 104", ink, 4f), stroke("M112 104 Q120 110 128 104", ink, 4f))),
            Part(
                setOf(PepeMood.MAREADO),
                listOf(stroke("M73 96 L87 110 M87 96 L73 110", ink, 4f), stroke("M113 96 L127 110 M127 96 L113 110", ink, 4f)),
            ),
            Part(setOf(PepeMood.TRISTE), listOf(stroke("M70 88 L88 82", ink, 4f), stroke("M112 82 L130 88", ink, 4f))),
            Part(setOf(PepeMood.CURIOSO, PepeMood.BUSCANDO), listOf(stroke("M110 84 Q120 75 131 81", ink, 4f))),
            Part(setOf(PepeMood.TRISTE), listOf(fill(path("M130 112 Q135 121 130 125 Q125 121 130 112 Z"), Color(0xFF7FC8FF)))),
            Part(
                setOf(PepeMood.HOLA, PepeMood.HERRAMIENTAS, PepeMood.LISTA),
                listOf(stroke("M86 123 Q100 137 114 123", ink, 4.5f)),
            ),
            Part(
                setOf(PepeMood.PALOMITAS, PepeMood.MUSICA),
                listOf(
                    fill(path("M84 120 Q100 144 116 120 Z"), ink),
                    fill(path("M92 131 Q100 139 108 131 Q100 127 92 131 Z"), coral),
                ),
            ),
            Part(setOf(PepeMood.CURIOSO, PepeMood.BUSCANDO, PepeMood.MAREADO), listOf(fill(oval(100f, 128f, 6f, 7f), ink))),
            Part(setOf(PepeMood.TRISTE), listOf(stroke("M88 133 Q100 123 112 133", ink, 4.5f))),
            Part(setOf(PepeMood.DORMIDO), listOf(stroke("M93 128 Q100 132 107 128", ink, 4f))),
            Part(
                setOf(PepeMood.PALOMITAS),
                listOf(
                    fill(rrect(63f, 90f, 33f, 25f, 8f), Color(0xFF4FB3FF), 0.9f),
                    fill(rrect(104f, 90f, 33f, 25f, 8f), Color(0xFFFF5A7A), 0.9f),
                    stroke(rrect(63f, 90f, 33f, 25f, 8f), ink, 4f, StrokeCap.Butt),
                    stroke(rrect(104f, 90f, 33f, 25f, 8f), ink, 4f, StrokeCap.Butt),
                    stroke("M96 100 H104 M63 98 L48 94 M137 98 L152 94", ink, 4f),
                ),
            ),
            Part(
                setOf(PepeMood.MUSICA),
                listOf(
                    fill(rrect(22f, 90f, 20f, 36f, 9f), yellow),
                    stroke(rrect(22f, 90f, 20f, 36f, 9f), ink, 4f, StrokeCap.Butt),
                    fill(rrect(158f, 90f, 20f, 36f, 9f), yellow),
                    stroke(rrect(158f, 90f, 20f, 36f, 9f), ink, 4f, StrokeCap.Butt),
                ),
            ),
            Part(
                setOf(PepeMood.PALOMITAS),
                listOf(
                    fill(circle(12f, 128f, 8f), Color(0xFFFFE7A8)),
                    fill(circle(23f, 121f, 9f), Color(0xFFFFF0C7)),
                    fill(circle(35f, 124f, 9f), Color(0xFFFFE7A8)),
                    fill(circle(45f, 130f, 7f), Color(0xFFFFF0C7)),
                    fill(circle(28f, 112f, 7f), Color(0xFFFFF0C7)),
                    fill(path("M6 132 L50 132 L44 184 L12 184 Z"), screen),
                    stroke("M16 132 L19 184 M28 132 L28 184 M40 132 L37 184", Color(0xFFE5484D), 5f, StrokeCap.Butt),
                    fill(circle(46f, 150f, 9f), coral),
                ),
            ),
            Part(
                setOf(PepeMood.MUSICA),
                listOf(
                    fill(oval(181f, 64f, 7f, 5.5f), yellow),
                    stroke("M187 63 V38 L197 34", yellow, 4f),
                    fill(oval(16f, 56f, 6f, 4.5f), blush),
                    stroke("M21 55 V34", blush, 3.5f),
                ),
            ),
            Part(
                setOf(PepeMood.MAREADO),
                listOf(
                    fill(path("M58 26 l3 7 7 3 -7 3 -3 7 -3 -7 -7 -3 7 -3 Z"), yellow),
                    fill(path("M140 18 l2.5 6 6 2.5 -6 2.5 -2.5 6 -2.5 -6 -6 -2.5 6 -2.5 Z"), yellow),
                    fill(path("M100 6 l2 5 5 2 -5 2 -2 5 -2 -5 -5 -2 5 -2 Z"), yellow),
                ),
            ),
            Part(
                setOf(PepeMood.BUSCANDO),
                listOf(
                    stroke("M164 84 L150 102", coral, 12f),
                    fill(circle(176f, 70f, 15f), screen, 0.3f),
                    stroke(circle(176f, 70f, 15f), ink, 5f, StrokeCap.Butt),
                    stroke("M169 70 Q176 63 183 70 M172.5 74 Q176 71 179.5 74", ink, 2.5f),
                ),
            ),
            Part(
                setOf(PepeMood.HERRAMIENTAS),
                listOf(
                    stroke("M166 132 L182 112", coral, 12f),
                    stroke("M176 116 L190 96", steel, 7f),
                    fill(circle(192f, 91f, 9f), steel),
                    fill(circle(195f, 87f, 4f), night),
                ),
            ),
            Part(
                setOf(PepeMood.LISTA),
                listOf(
                    fill(rrect(158f, 96f, 36f, 46f, 6f), screen),
                    stroke(rrect(158f, 96f, 36f, 46f, 6f), ink, 4f, StrokeCap.Butt),
                    fill(rrect(169f, 90f, 14f, 10f, 3f), yellow),
                    stroke(rrect(169f, 90f, 14f, 10f, 3f), ink, 3f, StrokeCap.Butt),
                    stroke("M166 112 H186 M166 121 H186 M166 130 H178", ink, 3f),
                    fill(circle(158f, 132f, 9f), coral),
                ),
            ),
        )
    }

    /** HOLA's waving arm, drawn in its own layer rotating around (166, 124). */
    val wave: List<Op> by lazy {
        listOf(
            stroke("M166 124 Q182 106 183 84", coral, 12f),
            fill(circle(183f, 79f, 10f), coral),
            stroke("M194 62 Q198 56 195 48 M174 60 Q170 53 173 46", yellow, 3f),
        )
    }

    private val byMood = mutableMapOf<PepeMood, List<Op>>()

    fun forMood(mood: PepeMood): List<Op> = byMood.getOrPut(mood) {
        parts.filter { it.moods == null || mood in it.moods }.flatMap { it.ops }
    }

    fun glyphsFor(mood: PepeMood): List<Glyph> = when (mood) {
        PepeMood.DORMIDO -> listOf(
            Glyph("z", 148f, 46f, 20, 600, lavender),
            Glyph("Z", 164f, 30f, 26, 600, lavender),
            Glyph("z", 184f, 14f, 16, 600, lavender),
        )
        PepeMood.CURIOSO -> listOf(Glyph("?", 152f, 42f, 40, 700, yellow))
        else -> emptyList()
    }

    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

    private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(r))) }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) =
        Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }

    private fun circle(cx: Float, cy: Float, r: Float) = oval(cx, cy, r, r)

    private fun fill(path: Path, color: Color, alpha: Float = 1f) = Op(path, color, alpha)

    private fun stroke(path: Path, color: Color, width: Float, cap: StrokeCap = StrokeCap.Round) =
        Op(path, color, stroke = Stroke(width, cap = cap))

    private fun stroke(d: String, color: Color, width: Float, cap: StrokeCap = StrokeCap.Round) =
        stroke(path(d), color, width, cap)
}

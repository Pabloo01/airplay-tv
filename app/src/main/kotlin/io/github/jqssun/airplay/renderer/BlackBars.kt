package io.github.jqssun.airplay.renderer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// black bars baked into the mirrored frame (phone pillarboxes/letterboxes a movie on its own screen);
// pure kotlin so the analysis runs in jvm unit tests, gl readback lives in VideoPipeline

// normalized region of the stream, origin top-left
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    // display aspect of this region of a streamW x streamH frame
    fun aspect(streamW: Int, streamH: Int): Float = width * streamW / (height * streamH)

    fun near(o: CropRect, tol: Float): Boolean =
        abs(left - o.left) <= tol && abs(top - o.top) <= tol &&
            abs(right - o.right) <= tol && abs(bottom - o.bottom) <= tol

    // shows some area that o hides
    fun reveals(o: CropRect, tol: Float): Boolean =
        left < o.left - tol || top < o.top - tol || right > o.right + tol || bottom > o.bottom + tol

    fun union(o: CropRect) = CropRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    // linear blend toward o, t in 0..1
    fun lerp(o: CropRect, t: Float) = CropRect(
        left + (o.left - left) * t, top + (o.top - top) * t,
        right + (o.right - right) * t, bottom + (o.bottom - bottom) * t,
    )

    // samples centered at (cx, cy) in normalized coordinates fall inside
    fun contains(cx: Float, cy: Float): Boolean = cx >= left && cx < right && cy >= top && cy < bottom

    override fun toString(): String = String.format(Locale.US, "[%.3f,%.3f %.3f,%.3f]", left, top, right, bottom)

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

// reliable = bright enough to tell bars from dark picture; a dark frame can still prove picture exists somewhere
data class BarDetection(val rect: CropRect, val reliable: Boolean)

class BlackBarDetector(
    private val lumaThreshold: Int = LUMA_THRESHOLD,
    private val lineFraction: Float = LINE_FRACTION,
    private val minBar: Float = MIN_BAR,
    private val minArea: Float = MIN_AREA,
    private val minFill: Float = MIN_FILL,
) {
    private var rowHits = IntArray(0)
    private var colHits = IntArray(0)

    // luma: w*h samples (0..255 as unsigned bytes), row-major, top row first; null when nothing is lit
    fun detect(luma: ByteArray, w: Int, h: Int, streamW: Int, streamH: Int): BarDetection? {
        require(w > 0 && h > 0 && luma.size >= w * h && streamW > 0 && streamH > 0)
        if (rowHits.size != h) rowHits = IntArray(h) else rowHits.fill(0)
        if (colHits.size != w) colHits = IntArray(w) else colHits.fill(0)
        for (y in 0 until h) {
            val base = y * w
            var n = 0
            for (x in 0 until w) {
                if ((luma[base + x].toInt() and 0xFF) > lumaThreshold) {
                    n++
                    colHits[x]++
                }
            }
            rowHits[y] = n
        }
        // a line is picture only if enough of it is lit, so stray codec noise in a bar doesn't count
        val rowMin = max(2, ceil(lineFraction * w).toInt())
        val colMin = max(2, ceil(lineFraction * h).toInt())
        val top = rowHits.indexOfFirst { it >= rowMin }
        val left = colHits.indexOfFirst { it >= colMin }
        if (top < 0 || left < 0) return null
        val bottom = rowHits.indexOfLast { it >= rowMin }
        val right = colHits.indexOfLast { it >= colMin }

        var lit = 0
        for (y in top..bottom) lit += rowHits[y]
        val box = (bottom - top + 1) * (right - left + 1)
        val reliable = box >= minArea * w * h && lit >= minFill * box

        val (xSafe, xMax) = _barRange(left, right, w)
        val (ySafe, yMax) = _barRange(top, bottom, h)
        var barX = if (xSafe >= minBar) xSafe else 0f
        var barY = if (ySafe >= minBar) ySafe else 0f
        // movie fills the other axis exactly: snap to a standard aspect inside the measured bounds for exact edges
        val streamAspect = streamW.toFloat() / streamH
        if (barX > 0f && barY == 0f) {
            _snap(1f - 2f * xMax, 1f - 2f * xSafe) { it / streamAspect }?.let { barX = (1f - it) / 2f }
        } else if (barY > 0f && barX == 0f) {
            _snap(1f - 2f * yMax, 1f - 2f * ySafe) { streamAspect / it }?.let { barY = (1f - it) / 2f }
        }
        if (barX < minBar) barX = 0f
        if (barY < minBar) barY = 0f
        return BarDetection(CropRect(barX, barY, 1f - barX, 1f - barY), reliable)
    }

    // per-side bar as (safe, max) fraction of an n-sample axis whose first/last lit samples are given;
    // true edge lies between the last dark sample center (safe) and the first lit sample center (max)
    private fun _barRange(first: Int, last: Int, n: Int): Pair<Float, Float> {
        val safeNear = if (first == 0) 0f else (first - 0.5f) / n
        val maxNear = (first + 0.5f) / n
        val safeFar = if (last == n - 1) 0f else (n - last - 1.5f) / n
        val maxFar = (n - last - 0.5f) / n
        // sender centers video: smaller bar on both sides, so a dark edge on one side never cuts picture
        return min(safeNear, safeFar) to min(maxNear, maxFar)
    }

    // largest picture size (fraction of axis) of a standard aspect that fits [lo, hi]
    private inline fun _snap(lo: Float, hi: Float, sizeFor: (Float) -> Float): Float? {
        var best: Float? = null
        for (k in ASPECTS) {
            val s = sizeFor(k)
            if (s >= lo - EPS && s <= hi + EPS && s < 1f && (best == null || s > best)) best = s
        }
        return best
    }

    companion object {
        // on 0..255 after yuv->rgb; bars decode to ~0 (or ~16 if a driver keeps limited range), noise stays below
        const val LUMA_THRESHOLD = 24
        // fraction of a row/column that must be lit to count as picture
        const val LINE_FRACTION = 0.05f
        // bars thinner than this per side are left alone
        const val MIN_BAR = 0.03f
        // box around lit lines must cover this much of the frame, and be this lit, before bars are trusted
        const val MIN_AREA = 0.25f
        const val MIN_FILL = 0.30f
        private const val EPS = 1e-4f
        private val ASPECTS = floatArrayOf(
            9f / 16f, 0.75f, 0.8f, 1f, 4f / 3f, 1.375f, 1.43f, 1.5f, 1.6f, 5f / 3f,
            16f / 9f, 1.85f, 1.9f, 2f, 2.2f, 2.35f, 2.39f, 2.4f, 2.76f,
        )
    }
}

// turns noisy per-sample detections into a crop that only changes after it has held steady
class CropStabilizer(
    private val revealMs: Long = REVEAL_MS,
    private val firstCropMs: Long = FIRST_CROP_MS,
    private val recropMs: Long = RECROP_MS,
    private val tolerance: Float = TOLERANCE,
    private val minSamples: Int = MIN_SAMPLES,
    private val returnMs: Long = RETURN_MS,
    private val minorRevealMs: Long = MINOR_REVEAL_MS,
    private val minorRevealShare: Float = MINOR_REVEAL_SHARE,
) {
    var current: CropRect = CropRect.FULL
        private set
    private var cropped = false
    // crop that was on screen before the last reveal (player controls, HUD, pause): coming back
    // to it is quick, only a crop never seen before needs the long dark-scene guard
    private var established: CropRect? = null
    // current was reached by a reveal (keeps `established` across a chain of reveals)
    private var revealed = false
    private var candidate: CropRect? = null
    private var candidateMinor = false
    private var since = 0L
    private var seen = 0

    fun reset() {
        current = CropRect.FULL
        cropped = false
        established = null
        revealed = false
        candidate = null
        candidateMinor = false
        seen = 0
    }

    /*
     * feeds one detection (null = nothing lit); true when current changed. litShareOutside: share
     * of the area outside `current` that is lit in this frame (see statsOutside); a reveal whose
     * newly lit area stays small (volume HUD, thin control overlay) must hold minorRevealMs
     */
    fun offer(d: BarDetection?, nowMs: Long, litShareOutside: Float = 1f): Boolean {
        if (d == null) return false
        val sample = when {
            d.reliable -> d.rect
            // too dark to trust bars, but lit picture outside the crop still proves it's being cut
            d.rect.reveals(current, tolerance) -> d.rect.union(current)
            else -> return false
        }
        if (sample.near(current, tolerance)) {
            candidate = null
            return false
        }
        val minor = litShareOutside < minorRevealShare
        val c = candidate
        if (c == null || !sample.near(c, tolerance)) {
            candidate = sample
            candidateMinor = minor
            since = nowMs
            seen = 1
            return false
        }
        // union keeps every bit of picture seen while the candidate held
        val next = c.union(sample)
        candidate = next
        seen++
        if (!minor) candidateMinor = false
        val wait = when {
            next.reveals(current, tolerance) -> if (candidateMinor) minorRevealMs else revealMs
            !cropped -> firstCropMs
            // back to what was on screen before a reveal: no dark-scene risk, it was proven
            established?.near(next, tolerance) == true -> returnMs
            // cropping tighter mid-stream is what a dark scene fakes, so it must hold longer
            else -> recropMs
        }
        if (seen < minSamples || nowMs - since < wait) return false
        if (next.reveals(current, tolerance)) {
            if (!revealed && !current.isFull) established = current
            revealed = true
        } else {
            revealed = false
        }
        current = next
        candidate = null
        if (!next.isFull) cropped = true
        return true
    }

    companion object {
        const val REVEAL_MS = 1000L
        const val FIRST_CROP_MS = 1000L
        // a dark-edged scene faked a tighter crop for 9 s on a real movie; genuine aspect
        // changes (e.g. IMAX sequences) last far longer
        const val RECROP_MS = 15_000L
        // back to the crop held before a reveal once the overlay is gone
        const val RETURN_MS = 2000L
        // reveals lighting less than this share of the bar area (small HUD, thin controls)
        const val MINOR_REVEAL_SHARE = 0.10f
        const val MINOR_REVEAL_MS = 3000L
        // ~1.5 probe samples; true bars repeat exactly
        const val TOLERANCE = 0.012f
        const val MIN_SAMPLES = 3
    }
}

// luma of the probe outside a crop (the bars): pixels counted, mean, max, share above threshold
data class RegionStats(val pixels: Int, val meanLuma: Float, val maxLuma: Int, val litShare: Float)

fun statsOutside(
    luma: ByteArray, w: Int, h: Int, inner: CropRect,
    threshold: Int = BlackBarDetector.LUMA_THRESHOLD,
): RegionStats {
    var n = 0
    var sum = 0L
    var maxL = 0
    var lit = 0
    for (y in 0 until h) {
        val cy = (y + 0.5f) / h
        val base = y * w
        for (x in 0 until w) {
            if (inner.contains((x + 0.5f) / w, cy)) continue
            val v = luma[base + x].toInt() and 0xFF
            n++
            sum += v
            if (v > maxL) maxL = v
            if (v > threshold) lit++
        }
    }
    return if (n == 0) RegionStats(0, 0f, 0, 0f) else RegionStats(n, sum.toFloat() / n, maxL, lit.toFloat() / n)
}

// smoothstep ease for crop transitions, t clamped to 0..1
fun easeInOut(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

// aspect-fits content into a winW x winH window, centered, keeping margin (fraction per side) clear;
// writes x, y, w, h into out; aspect <= 0 fills the available area
fun fitViewport(aspect: Float, winW: Int, winH: Int, margin: Float, out: IntArray) {
    var w = winW * (1f - 2f * margin)
    var h = winH * (1f - 2f * margin)
    if (aspect > 0f && w > 0f && h > 0f) {
        if (w / h > aspect) w = h * aspect else h = w / aspect
    }
    val iw = w.roundToInt().coerceIn(1, max(1, winW))
    val ih = h.roundToInt().coerceIn(1, max(1, winH))
    out[0] = (winW - iw) / 2
    out[1] = (winH - ih) / 2
    out[2] = iw
    out[3] = ih
}

// gl readback (rgba, bottom row first) to luma, top row first
fun rgbaToLuma(rgba: ByteArray, w: Int, h: Int, out: ByteArray) {
    for (y in 0 until h) {
        var src = (h - 1 - y) * w * 4
        val dst = y * w
        for (x in 0 until w) {
            val r = rgba[src].toInt() and 0xFF
            val g = rgba[src + 1].toInt() and 0xFF
            val b = rgba[src + 2].toInt() and 0xFF
            out[dst + x] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
            src += 4
        }
    }
}

package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlackBarsTest {

    // iPhone 16 Pro landscape mirror into a 1920x1080 receiver, probed at 256 px long side
    private val sw = 1920
    private val sh = 884
    private val w = 256
    private val h = 118
    private val streamAspect = sw.toFloat() / sh

    // centered picture of the given display aspect, fit inside the phone frame
    private fun fitted(aspect: Float): CropRect =
        if (aspect < streamAspect) {
            val cw = aspect / streamAspect
            CropRect((1 - cw) / 2, 0f, (1 + cw) / 2, 1f)
        } else {
            val ch = streamAspect / aspect
            CropRect(0f, (1 - ch) / 2, 1f, (1 + ch) / 2)
        }

    // luma probe of a frame: picture inside pic, bars elsewhere
    private fun frame(
        pic: CropRect,
        bars: (x: Int, y: Int) -> Int = { _, _ -> 0 },
        picture: (x: Int, y: Int) -> Int = { _, _ -> 140 },
    ): ByteArray {
        val a = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val cx = (x + 0.5f) / w
            val cy = (y + 0.5f) / h
            val inside = cx >= pic.left && cx < pic.right && cy >= pic.top && cy < pic.bottom
            a[y * w + x] = (if (inside) picture(x, y) else bars(x, y)).coerceIn(0, 255).toByte()
        }
        return a
    }

    private fun detect(luma: ByteArray) = BlackBarDetector().detect(luma, w, h, sw, sh)

    private fun <T : Any> nn(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    // detected crop must contain all picture (never cut it), give or take float noise
    private fun assertContains(crop: CropRect, pic: CropRect) {
        val e = 1e-3f
        assertTrue("$crop cuts $pic", crop.left <= pic.left + e && crop.top <= pic.top + e &&
            crop.right >= pic.right - e && crop.bottom >= pic.bottom - e)
    }

    @Test
    fun pillarbox16x9IsCroppedToExactAspect() {
        val pic = fitted(16f / 9f)
        val d = nn(detect(frame(pic)))
        assertTrue(d.reliable)
        assertContains(d.rect, pic)
        assertEquals(16f / 9f, d.rect.aspect(sw, sh), 0.002f)
        assertEquals(0f, d.rect.top, 0f)
        assertEquals(1f, d.rect.bottom, 0f)
        assertEquals(d.rect.left, 1f - d.rect.right, 1e-5f)
    }

    @Test
    fun letterboxScopeIsCroppedTopAndBottom() {
        val pic = fitted(2.39f)
        val d = nn(detect(frame(pic)))
        assertTrue(d.reliable)
        assertContains(d.rect, pic)
        assertEquals(0f, d.rect.left, 0f)
        assertEquals(1f, d.rect.right, 0f)
        assertTrue(d.rect.top > 0.03f)
        assertEquals(2.39f, d.rect.aspect(sw, sh), 0.05f)
    }

    @Test
    fun fourThreeIsCropped() {
        val pic = fitted(4f / 3f)
        val d = nn(detect(frame(pic)))
        assertContains(d.rect, pic)
        assertEquals(4f / 3f, d.rect.aspect(sw, sh), 0.002f)
    }

    @Test
    fun nonStandardAspectFallsBackToSafeEdges() {
        val pic = fitted(1.7f)
        val d = nn(detect(frame(pic)))
        assertContains(d.rect, pic)
        // at most one probe sample of bar left on each side
        assertEquals(pic.left, d.rect.left, 1.01f / w)
    }

    @Test
    fun noBarsGivesFullFrame() {
        val d = nn(detect(frame(CropRect.FULL)))
        assertTrue(d.reliable)
        assertEquals(CropRect.FULL, d.rect)
    }

    @Test
    fun thinBarsAreIgnored() {
        val pic = CropRect(0.02f, 0f, 0.98f, 1f)
        assertEquals(CropRect.FULL, nn(detect(frame(pic))).rect)
    }

    @Test
    fun allBlackFrameIsNoEvidence() {
        assertNull(detect(frame(CropRect.FULL, picture = { _, _ -> 0 })))
    }

    @Test
    fun darkFrameIsUnreliable() {
        // fade: picture almost black, only a small logo lit in the middle
        val logo = CropRect(0.45f, 0.4f, 0.55f, 0.6f)
        val d = nn(detect(frame(CropRect.FULL, picture = { x, y ->
            val cx = (x + 0.5f) / w
            val cy = (y + 0.5f) / h
            if (cx in logo.left..logo.right && cy in logo.top..logo.bottom) 200 else 8
        })))
        assertFalse(d.reliable)
    }

    @Test
    fun dimScatteredPictureIsUnreliable() {
        // night scene: whole frame has some lit pixels, but most of it is below threshold
        val rnd = Random(7)
        val d = nn(detect(frame(fitted(16f / 9f), picture = { _, _ -> if (rnd.nextFloat() < 0.15f) 90 else 10 })))
        assertFalse(d.reliable)
    }

    @Test
    fun noisyBarsAndGrainStillDetected() {
        val rnd = Random(42)
        val pic = fitted(16f / 9f)
        val d = nn(detect(frame(
            pic,
            // codec noise: mostly near black, a few stray bright pixels
            bars = { _, _ -> if (rnd.nextFloat() < 0.01f) 60 else rnd.nextInt(0, 18) },
            // grainy picture with some dark pixels
            picture = { _, _ -> if (rnd.nextFloat() < 0.2f) rnd.nextInt(0, 20) else rnd.nextInt(30, 255) },
        )))
        assertTrue(d.reliable)
        assertContains(d.rect, pic)
        assertEquals(16f / 9f, d.rect.aspect(sw, sh), 0.002f)
    }

    @Test
    fun limitedRangeBlackBarsStillBars() {
        // a driver that keeps video range decodes bars as 16
        val pic = fitted(16f / 9f)
        val d = nn(detect(frame(pic, bars = { _, _ -> 16 })))
        assertEquals(16f / 9f, d.rect.aspect(sw, sh), 0.002f)
    }

    @Test
    fun darkEdgeOnOneSideUsesSmallerBar() {
        // 16:9 movie, right part of the picture is dark: crop stays symmetric on the real bar
        val pic = fitted(16f / 9f)
        val d = nn(detect(frame(pic, picture = { x, _ -> if ((x + 0.5f) / w > 0.75f) 5 else 150 })))
        assertContains(d.rect, pic)
        assertEquals(d.rect.left, 1f - d.rect.right, 1e-5f)
        assertEquals(16f / 9f, d.rect.aspect(sw, sh), 0.002f)
    }

    @Test
    fun portraitStreamLetterboxed16x9() {
        // phone in portrait (498x1080) playing 16:9 video centered
        val pw = 118
        val ph = 256
        val psw = 498
        val psh = 1080
        val ch = (psw / (16f / 9f)) / psh
        val pic = CropRect(0f, (1 - ch) / 2, 1f, (1 + ch) / 2)
        val a = ByteArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val cy = (y + 0.5f) / ph
            a[y * pw + x] = (if (cy >= pic.top && cy < pic.bottom) 150 else 0).toByte()
        }
        val d = nn(BlackBarDetector().detect(a, pw, ph, psw, psh))
        assertTrue(d.reliable)
        assertContains(d.rect, pic)
        assertEquals(16f / 9f, d.rect.aspect(psw, psh), 0.01f)
    }

    // stabilizer

    private val movie = BarDetection(CropRect(0.1f, 0f, 0.9f, 1f), true)
    private val ui = BarDetection(CropRect.FULL, true)

    @Test
    fun cropAdoptedOnlyAfterHoldingOneSecond() {
        val s = CropStabilizer()
        assertFalse(s.offer(movie, 0))
        assertFalse(s.offer(movie, 300))
        assertFalse(s.offer(movie, 600))
        assertFalse(s.offer(movie, 900))
        assertTrue(s.offer(movie, 1200))
        assertEquals(movie.rect, s.current)
        // steady movie keeps it
        assertFalse(s.offer(movie, 1500))
        assertEquals(movie.rect, s.current)
    }

    @Test
    fun flickeringDetectionsNeverAdopt() {
        val s = CropStabilizer()
        val other = BarDetection(CropRect(0.2f, 0f, 0.8f, 1f), true)
        var t = 0L
        repeat(20) {
            assertFalse(s.offer(if (it % 2 == 0) movie else other, t))
            t += 300
        }
        assertEquals(CropRect.FULL, s.current)
    }

    @Test
    fun darkFramesKeepCurrentCrop() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        val darkTight = BarDetection(CropRect(0.3f, 0.2f, 0.7f, 0.8f), false)
        var t = 10_000L
        repeat(30) { assertFalse(s.offer(darkTight, t)); assertFalse(s.offer(null, t + 100)); t += 300 }
        assertEquals(movie.rect, s.current)
    }

    @Test
    fun darkFramesCanStillReveal() {
        // movie ends into a dim UI whose content reaches the edges
        val s = CropStabilizer()
        adopt(s, movie, 0)
        val dimUi = BarDetection(CropRect.FULL, false)
        var t = 10_000L
        var changed = false
        repeat(5) { changed = changed || s.offer(dimUi, t); t += 300 }
        assertTrue(changed)
        assertEquals(CropRect.FULL, s.current)
    }

    @Test
    fun tighterCropMidStreamNeedsLongerHold() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        val tighter = BarDetection(CropRect(0.2f, 0f, 0.8f, 1f), true)
        var t = 10_000L
        // a 9 s dark-edged scene (seen on a real movie) does not move the picture
        repeat(30) { assertFalse(s.offer(tighter, t)); t += 300 }
        assertEquals(movie.rect, s.current)
        // held 15 s it does
        repeat(21) { s.offer(tighter, t); t += 300 }
        assertEquals(tighter.rect, s.current)
    }

    @Test
    fun revealIsFast() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        var t = 10_000L
        repeat(4) { assertFalse(s.offer(ui, t)); t += 300 }
        assertEquals(movie.rect, s.current)
        assertTrue(s.offer(ui, t))
        assertEquals(CropRect.FULL, s.current)
    }

    @Test
    fun resetGoesBackToFullAndFirstCropIsFastAgain() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        s.reset()
        assertEquals(CropRect.FULL, s.current)
        val tighter = BarDetection(CropRect(0.2f, 0f, 0.8f, 1f), true)
        adopt(s, tighter, 10_000)
    }

    @Test
    fun smallJitterIsNotAChange() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        val jitter = BarDetection(CropRect(0.104f, 0f, 0.896f, 1f), true)
        var t = 10_000L
        repeat(20) { assertFalse(s.offer(jitter, t)); t += 300 }
        assertEquals(movie.rect, s.current)
    }

    // offers d every 300 ms from t0 until current becomes d (or `limit` ms pass); returns ms taken
    private fun holdUntil(s: CropStabilizer, d: BarDetection, t0: Long, limit: Long = 20_000, share: Float = 1f): Long {
        var t = t0
        while (s.current != d.rect && t - t0 <= limit) {
            s.offer(d, t, share)
            if (s.current == d.rect) break
            t += 300
        }
        return t - t0
    }

    @Test
    fun returnsToEstablishedCropQuicklyAfterReveal() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        // player controls / pause screen over the bars: uncropped after ~1 s
        assertTrue(holdUntil(s, ui, 10_000) <= 1_500)
        assertEquals(CropRect.FULL, s.current)
        // overlay gone: back to the movie's crop after ~2 s, not the 15 s new-crop guard
        val took = holdUntil(s, movie, 20_000)
        assertEquals(movie.rect, s.current)
        assertTrue("took $took ms", took in CropStabilizer.RETURN_MS..CropStabilizer.RETURN_MS + 600)
    }

    @Test
    fun establishedCropSurvivesChainedReveals() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        // partial reveal first (overlay in part of the bars), then the full UI
        val partial = BarDetection(CropRect(0.05f, 0f, 0.95f, 1f), true)
        assertTrue(holdUntil(s, partial, 10_000) <= 1_500)
        assertTrue(holdUntil(s, ui, 20_000) <= 1_500)
        assertTrue(holdUntil(s, movie, 30_000) <= CropStabilizer.RETURN_MS + 600)
        assertEquals(movie.rect, s.current)
    }

    @Test
    fun newTighterCropAfterRevealStillNeedsLongHold() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        assertTrue(holdUntil(s, ui, 10_000) <= 1_500)
        // a dark-edged scene faking a tighter crop than anything shown before
        val tighter = BarDetection(CropRect(0.2f, 0f, 0.8f, 1f), true)
        var t = 20_000L
        repeat(30) { assertFalse(s.offer(tighter, t)); t += 300 }
        assertEquals(CropRect.FULL, s.current)
        val took = holdUntil(s, tighter, t)
        assertTrue("took $took ms", took + 9_000 >= CropStabilizer.RECROP_MS)
        assertEquals(tighter.rect, s.current)
    }

    @Test
    fun smallHudRevealIsIgnoredUnlessItStays() {
        val s = CropStabilizer()
        adopt(s, movie, 0)
        // volume HUD in the left bar: the symmetric detection reveals, but few bar pixels are lit
        val hud = BarDetection(CropRect(0.03f, 0f, 0.97f, 1f), true)
        var t = 10_000L
        repeat(8) { assertFalse(s.offer(hud, t, 0.04f)); t += 300 }  // 2.4 s on screen
        repeat(3) { assertFalse(s.offer(movie, t)); t += 300 }
        assertEquals(movie.rect, s.current)
        // left up: shown after ~3 s
        val took = holdUntil(s, hud, t, share = 0.04f)
        assertEquals(hud.rect, s.current)
        assertTrue("took $took ms", took in CropStabilizer.MINOR_REVEAL_MS..CropStabilizer.MINOR_REVEAL_MS + 600)
    }

    @Test
    fun statsOutsideMeasuresTheBars() {
        val pic = fitted(16f / 9f)
        // limited-range blacks shown unexpanded: bars at 16, picture bright
        val luma = frame(pic, bars = { _, _ -> 16 })
        val st = statsOutside(luma, w, h, pic)
        assertTrue(st.pixels > 0)
        assertEquals(16f, st.meanLuma, 0.01f)
        assertEquals(16, st.maxLuma)
        assertEquals(0f, st.litShare, 0f)
        // a small lit patch in the left bar
        val hud = CropRect(0.02f, 0.1f, 0.06f, 0.3f)
        val withHud = frame(pic, bars = { x, y -> if (hud.contains((x + 0.5f) / w, (y + 0.5f) / h)) 200 else 0 })
        val sh = statsOutside(withHud, w, h, pic)
        assertTrue("share ${sh.litShare}", sh.litShare > 0.01f && sh.litShare < CropStabilizer.MINOR_REVEAL_SHARE)
        assertEquals(0, statsOutside(withHud, w, h, CropRect.FULL).pixels)
    }

    @Test
    fun cropTransitionHelpers() {
        val a = CropRect.FULL
        val b = CropRect(0.1f, 0.2f, 0.9f, 0.8f)
        assertEquals(a, a.lerp(b, 0f))
        assertEquals(b, a.lerp(b, 1f))
        val mid = a.lerp(b, 0.5f)
        assertEquals(0.05f, mid.left, 1e-6f)
        assertEquals(0.9f, mid.bottom, 1e-6f)
        assertEquals(0f, easeInOut(-1f), 0f)
        assertEquals(0.5f, easeInOut(0.5f), 1e-6f)
        assertEquals(1f, easeInOut(2f), 0f)
        assertTrue(easeInOut(0.1f) < 0.1f && easeInOut(0.9f) > 0.9f)
    }

    // offers d every 300 ms starting at t0 until adopted within ~1.2 s
    private fun adopt(s: CropStabilizer, d: BarDetection, t0: Long) {
        var t = t0
        var adopted = false
        repeat(5) { if (!adopted) adopted = s.offer(d, t); t += 300 }
        assertTrue(adopted)
        assertEquals(d.rect, s.current)
    }

    // viewport / readback helpers

    @Test
    fun fitViewportCentersAndKeepsAspect() {
        val out = IntArray(4)
        fitViewport(16f / 9f, 1920, 1080, 0f, out)
        assertEquals(listOf(0, 0, 1920, 1080), out.toList())
        fitViewport(1920f / 884f, 1920, 1080, 0f, out)
        assertEquals(listOf(0, 98, 1920, 884), out.toList())
        fitViewport(4f / 3f, 1920, 1080, 0f, out)
        assertEquals(listOf(240, 0, 1440, 1080), out.toList())
        fitViewport(0f, 1920, 1080, 0f, out)
        assertEquals(listOf(0, 0, 1920, 1080), out.toList())
        fitViewport(16f / 9f, 1920, 1080, 0.05f, out)
        assertEquals(listOf(96, 54, 1728, 972), out.toList())
    }

    @Test
    fun rgbaToLumaFlipsRows() {
        // 1x2: gl row 0 (bottom) white, row 1 (top) black
        val rgba = byteArrayOf(-1, -1, -1, -1, 0, 0, 0, -1)
        val out = ByteArray(2)
        rgbaToLuma(rgba, 1, 2, out)
        assertEquals(0, out[0].toInt() and 0xFF)
        assertEquals(255, out[1].toInt() and 0xFF)
    }

    // whole chain: phone UI, then a 16:9 movie, then a long dark-edged scene, then back to UI
    @Test
    fun sessionNeverCutsPictureOrFlaps() {
        val det = BlackBarDetector()
        val s = CropStabilizer()
        val pic = fitted(16f / 9f)
        val uiFrame = frame(CropRect.FULL, picture = { x, y -> 60 + (x + y) % 120 })
        val movieFrame = frame(pic)
        val darkEdges = frame(pic, picture = { x, _ -> val cx = (x + 0.5f) / w; if (cx < 0.25f || cx > 0.75f) 6 else 160 })
        val changes = mutableListOf<CropRect>()
        var t = 0L
        // mustShow: picture the crop may never cut while this content is up
        fun run(f: ByteArray, ms: Long, mustShow: CropRect?) {
            val end = t + ms
            while (t < end) {
                if (s.offer(det.detect(f, w, h, sw, sh), t)) changes += s.current
                mustShow?.let { assertContains(s.current, it) }
                t += 300
            }
        }
        run(uiFrame, 3000, CropRect.FULL)
        assertEquals(CropRect.FULL, s.current)
        run(movieFrame, 2000, pic)
        assertEquals(16f / 9f, s.current.aspect(sw, sh), 0.002f)
        // dark-edged scene shorter than the re-crop hold: picture must not move
        val before = changes.size
        run(darkEdges, 2400, pic)
        run(movieFrame, 1500, pic)
        assertEquals(before, changes.size)
        // ui shows cropped for the ~1 s hold, then full
        run(uiFrame, 1500, null)
        assertEquals(CropRect.FULL, s.current)
        assertEquals(2, changes.size)
    }
}

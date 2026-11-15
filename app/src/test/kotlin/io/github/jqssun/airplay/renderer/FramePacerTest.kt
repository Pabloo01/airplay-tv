package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.renderer.FramePacer.Companion.MS
import io.github.jqssun.airplay.renderer.FramePacer.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

class FramePacerTest {

    // a 24 fps mirror stream: pts on the sender clock (as realtime), arrival on the monotonic clock
    private class Sim(val p: FramePacer = FramePacer()) {
        var now = 10_000 * MS
        var ptsMono = now
        var audioNs: Long? = null
        var ptsShiftNs = 0L
        val events = ArrayList<String>()

        init {
            p.setClockOffset(OFFSET)
        }

        // one frame arriving latenessNs after its timestamp (tcp: never before the previous one)
        fun frame(latenessNs: Long): Fed {
            now = max(now, ptsMono + latenessNs)
            p.onAudioDelay(audioNs, now)
            val feedAt = p.schedule(ptsMono + OFFSET + ptsShiftNs, now)
            events += p.takeEvents()
            val fed = Fed(ptsMono, now, feedAt, p.delayNs)
            ptsMono += FRAME_NS
            return fed
        }

        fun run(seconds: Double, lateness: (Int) -> Long): List<Fed> =
            (0 until (seconds * 24).toInt()).map { frame(lateness(it)) }

        // scheduler housekeeping while no frame arrives: audio polled every 250 ms
        fun idle(ns: Long) {
            val end = now + ns
            while (now + 250 * MS <= end) {
                now += 250 * MS
                p.onAudioDelay(audioNs, now)
                p.update(now)
                events += p.takeEvents()
            }
            now = end
        }

        // wi-fi stall: nothing arrives for ns, then every frame captured meanwhile at once
        fun stall(ns: Long, lateness: Long = 40 * MS): List<Fed> {
            idle(ns)
            val end = now
            val out = ArrayList<Fed>()
            while (ptsMono + lateness < end) out += frame(end - ptsMono)
            return out
        }

        // sender paused / screen static: nothing captured, nothing sent
        fun pause(ns: Long) {
            idle(ns)
            while (ptsMono + FRAME_NS <= now) ptsMono += FRAME_NS
        }
    }

    // when the scheduler actually feeds it: at its slot, or on arrival if that's already past
    private data class Fed(val ptsMono: Long, val arrival: Long, val feedAt: Long, val delay: Long) {
        val fedAt get() = max(feedAt, arrival)
    }

    private fun intervals(xs: List<Long>) = xs.zipWithNext { a, b -> b - a }

    private fun stdev(xs: List<Long>): Double {
        val mean = xs.average()
        return sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
    }

    @Test
    fun jitteryArrivalIsFedAtSteadyCadence() {
        val rnd = Random(7)
        val sim = Sim()
        // 20..150 ms network delay, 1% of frames stalled 200 ms more
        val fed = sim.run(40.0) { 20 * MS + rnd.nextLong(130 * MS) + if (rnd.nextInt(100) == 0) 200 * MS else 0 }
        val settled = fed.drop(24 * 8)
        fun steadyShare(xs: List<Long>) = xs.count { abs(it - FRAME_NS) <= 2 * MS }.toDouble() / xs.size
        val arrivalIntervals = intervals(settled.map { it.arrival })
        val feedIntervals = intervals(settled.map { it.fedAt })
        // decoding on arrival (smooth off) is almost never on cadence
        assertTrue(steadyShare(arrivalIntervals) < 0.1)
        assertTrue("only ${steadyShare(feedIntervals) * 100}% of intervals steady", steadyShare(feedIntervals) >= 0.85)
        assertTrue("paced jitter ${stdev(feedIntervals)} vs arrival ${stdev(arrivalIntervals)}",
            stdev(feedIntervals) < 0.5 * stdev(arrivalIntervals))
        assertEquals(Mode.VIDEO, sim.p.mode)
        // p95 of ~uniform 20..150 ms (stalls push it up a little) plus lead and margin
        assertTrue("delay ${sim.p.delayNs / MS}", sim.p.delayNs in 150 * MS..200 * MS)
    }

    @Test
    fun videoOnlyDelayIsP95PlusLeadAndMargin() {
        val rnd = Random(3)
        val sim = Sim()
        sim.run(15.0) { rnd.nextLong(100 * MS) }
        val p95 = assertNotNullLong(sim.p.latenessP95Ns)
        assertTrue("p95 ${p95 / MS}", p95 in 85 * MS..100 * MS)
        assertEquals(p95 + FramePacer.LEAD_NS + FramePacer.VIDEO_MARGIN_NS, sim.p.targetDelayNs)
        assertTrue(abs(sim.p.delayNs - sim.p.targetDelayNs) <= 3 * MS)
    }

    @Test
    fun audioDelayDrivesDelayWithDisplayCompensationAndUserOffset() {
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(10.0) { 40 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)
        assertEquals(250 * MS - FramePacer.DISPLAY_COMP_NS, sim.p.delayNs)
        // frames fed at pts + D - lead: picture lands with the sound
        val f = sim.frame(40 * MS)
        assertEquals(f.ptsMono + 234 * MS - FramePacer.LEAD_NS, f.feedAt)

        sim.p.userOffsetNs = 50 * MS
        sim.run(5.0) { 40 * MS }
        assertEquals(284 * MS, sim.p.delayNs)
        sim.p.userOffsetNs = -100 * MS
        sim.run(5.0) { 40 * MS }
        assertEquals(134 * MS, sim.p.delayNs)
        assertTrue(sim.events.any { it.startsWith("lip-sync: pacing to audio delay 250 ms") })
    }

    @Test
    fun delayChangesAtMostSlewStepPerFrame() {
        val sim = Sim()
        sim.audioNs = 200 * MS
        sim.run(5.0) { 40 * MS }
        assertEquals(184 * MS, sim.p.delayNs)
        sim.audioNs = 400 * MS
        val fed = sim.run(10.0) { 40 * MS }
        val maxStep = (FRAME_NS * FramePacer.SLEW_RATE).toLong()
        val steps = intervals(fed.map { it.delay })
        assertTrue("step ${steps.maxOf { abs(it) }}", steps.all { abs(it) <= maxStep })
        assertTrue(steps.any { it > 0 })
        assertEquals(384 * MS, sim.p.delayNs)
        // and so frame spacing on screen changes by at most that much (<= 4% speed change)
        assertTrue(intervals(fed.map { it.fedAt }).all { abs(it - FRAME_NS) <= maxStep })
    }

    @Test
    fun delayIsClamped() {
        val p = FramePacer()
        assertEquals(FramePacer.MIN_DELAY_NS, p.audioTargetNs(30 * MS))
        assertEquals(FramePacer.MAX_DELAY_NS, p.audioTargetNs(1_200 * MS))
        p.userOffsetNs = 50 * MS
        assertEquals(FramePacer.MAX_DELAY_NS + 50 * MS, p.audioTargetNs(1_200 * MS))
        p.userOffsetNs = 500 * MS
        assertEquals(FramePacer.MAX_TOTAL_DELAY_NS, p.audioTargetNs(1_200 * MS))
        p.userOffsetNs = -500 * MS
        assertEquals(0L, p.audioTargetNs(100 * MS))

        val sim = Sim()
        sim.audioNs = 2_000 * MS
        sim.run(5.0) { 40 * MS }
        assertEquals(FramePacer.MAX_DELAY_NS, sim.p.delayNs)
    }

    @Test
    fun lateFrameIsFedImmediatelyAndCounted() {
        val sim = Sim()
        sim.audioNs = 200 * MS
        sim.run(5.0) { 40 * MS }
        val lateBefore = sim.p.totalLate
        val f = sim.frame(400 * MS)
        assertTrue(f.feedAt <= f.arrival)
        assertEquals(lateBefore + 1, sim.p.totalLate)
        // tcp: the next few queued up behind it and are late too, then back on their slots
        val next = sim.run(1.0) { 40 * MS }
        assertTrue(next.count { it.feedAt < it.arrival } <= 6)
        assertTrue(next.takeLast(10).all { it.feedAt > it.arrival })
        assertEquals(Mode.AUDIO, sim.p.mode)
    }

    @Test
    fun missingTimestampFeedsAsap() {
        val p = FramePacer()
        p.setClockOffset(OFFSET)
        val now = 5_000 * MS
        assertEquals(now, p.schedule(0L, now))
        // before the first clock offset, too
        assertEquals(now, FramePacer().schedule(now + OFFSET, now))
    }

    @Test
    fun feedTimesNeverGoBackwards() {
        val rnd = Random(11)
        val sim = Sim()
        sim.audioNs = 400 * MS
        val a = sim.run(5.0) { 30 * MS + rnd.nextLong(40 * MS) }
        sim.audioNs = 80 * MS
        val b = sim.run(10.0) { 30 * MS + rnd.nextLong(40 * MS) }
        val feeds = (a + b).map { it.feedAt }
        assertTrue(intervals(feeds).all { it >= 0 })
    }

    @Test
    fun pastClockJumpReanchorsWithoutFreezingThenRecovers() {
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(10.0) { 40 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)

        // sender timestamps suddenly 5 s in the past
        sim.ptsShiftNs = -5_000 * MS
        val jumped = sim.run(10.0) { 40 * MS }
        assertEquals(1, sim.p.jumps)
        assertEquals(5_000 * MS, sim.p.correctionNs)
        assertTrue(sim.events.any { it.startsWith("timestamp jump") })
        // keeps pacing: every frame is held a sane time, never frozen, never all dumped
        assertTrue(jumped.all { it.feedAt >= it.arrival - 1 && it.feedAt - it.arrival <= FramePacer.MAX_HOLD_NS })
        assertTrue(jumped.drop(1).count { it.feedAt < it.arrival } <= 1)
        assertTrue(intervals(jumped.drop(1).map { it.fedAt }).all { abs(it - FRAME_NS) <= 2 * MS })
        // a/v relation unknown while re-anchored: no lip-syncing to a foreign clock
        assertEquals(Mode.VIDEO, sim.p.mode)

        // clocks agree again
        sim.ptsShiftNs = 0
        sim.run(3.0) { 40 * MS }
        assertEquals(2, sim.p.jumps)
        assertEquals(0L, sim.p.correctionNs)
        assertEquals(Mode.AUDIO, sim.p.mode)
    }

    @Test
    fun futureClockJumpIsReanchoredAndHoldIsBounded() {
        val sim = Sim()
        sim.run(5.0) { 40 * MS }
        sim.ptsShiftNs = 3_000 * MS
        val f = sim.frame(40 * MS)
        assertEquals(1, sim.p.jumps)
        assertTrue(f.feedAt - f.arrival in 0..FramePacer.MAX_HOLD_NS)
    }

    @Test
    fun smallCorrectionIsDroppedOnceClocksAgree() {
        val sim = Sim()
        sim.run(5.0) { 300 * MS }
        // +1.05 s jump on a 300 ms-late stream
        sim.ptsShiftNs = -1_050 * MS
        sim.run(2.0) { 300 * MS }
        assertEquals(1_050 * MS, sim.p.correctionNs)
        // back to normal: lateness reads -750 ms, not a jump, but raw clocks are sane again
        sim.ptsShiftNs = 0
        sim.run(1.0) { 300 * MS }
        assertEquals(0L, sim.p.correctionNs)
        assertEquals(2, sim.p.jumps)
    }

    @Test
    fun holdIsCappedAtMaxHold() {
        val sim = Sim()
        sim.audioNs = 450 * MS
        sim.p.userOffsetNs = 400 * MS
        sim.run(3.0) { 40 * MS }
        assertEquals(834 * MS, sim.p.delayNs)
        // timestamp ~860 ms in the future (just under a jump): would be held 1.7 s
        sim.ptsShiftNs = 900 * MS
        val f = sim.frame(40 * MS)
        assertEquals(0, sim.p.jumps)
        assertEquals(f.arrival + FramePacer.MAX_HOLD_NS, f.feedAt)
    }

    @Test
    fun videoLaterThanAudioFavoursSmoothnessThenRestoresLipSync() {
        val rnd = Random(5)
        val sim = Sim()
        sim.audioNs = 120 * MS  // lip-sync target 104 ms
        sim.run(8.0) { 140 * MS + rnd.nextLong(20 * MS) }
        assertEquals(Mode.SMOOTH_OVER_SYNC, sim.p.mode)
        assertTrue(sim.events.any { it.contains("behind sound") })
        assertTrue("delay ${sim.p.delayNs / MS}", sim.p.delayNs >= 170 * MS)
        val late = sim.p.totalLate

        // once settled, frames are on time again
        sim.run(3.0) { 140 * MS + rnd.nextLong(20 * MS) }
        assertTrue(sim.p.totalLate - late <= 2)

        // network recovers: back to lip-sync
        sim.run(15.0) { 30 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)
        assertTrue(sim.events.any { it.contains("lip-sync restored") })
        assertEquals(104 * MS, sim.p.delayNs)
    }

    @Test
    fun staleAudioFallsBackToVideoPacing() {
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(5.0) { 40 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)
        sim.audioNs = null
        sim.run(1.5) { 40 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)
        sim.run(2.0) { 40 * MS }
        assertEquals(Mode.VIDEO, sim.p.mode)
        assertNull(sim.p.audioDelayNs(sim.now))
        assertTrue(sim.events.any { it.contains("pacing by video arrival") })
    }

    @Test
    fun audioDelayIsMedianFiltered() {
        val p = FramePacer()
        var t = 0L
        for (d in longArrayOf(200, 210, 900, 205, 1)) p.onAudioDelay(d * MS, (++t) * 100 * MS)
        assertEquals(205 * MS, p.audioDelayNs(t * 100 * MS))
        // implausible values are ignored
        p.onAudioDelay(10_000 * MS, (++t) * 100 * MS)
        p.onAudioDelay(null, (++t) * 100 * MS)
        assertEquals(205 * MS, p.audioDelayNs(t * 100 * MS))
    }

    @Test
    fun clockOffsetStepIsReported() {
        val p = FramePacer()
        assertNull(p.setClockOffset(OFFSET))
        assertNull(p.setClockOffset(OFFSET + 5 * MS))
        assertEquals(100 * MS, p.setClockOffset(OFFSET + 105 * MS))
        assertTrue(p.takeEvents().single().contains("stepped +100 ms"))
    }

    @Test
    fun resetStartsNewSessionFromInitialDelay() {
        val sim = Sim()
        sim.audioNs = 350 * MS
        sim.run(5.0) { 40 * MS }
        sim.p.reset()
        assertEquals(FramePacer.INITIAL_DELAY_NS, sim.p.delayNs)
        assertEquals(Mode.VIDEO, sim.p.mode)
        assertTrue(sim.p.hasClockOffset)
        assertNull(sim.p.audioDelayNs(sim.now))
    }

    @Test
    fun reportLine() {
        val sim = Sim()
        sim.run(3.0) { 40 * MS }
        val line = sim.p.report(sim.now)
        assertTrue(line, line.startsWith("D="))
        assertTrue(line, line.contains("audio=n/a"))
        assertTrue(line, line.contains("late=0/72 (0.0%)"))
        assertTrue(sim.p.report(sim.now).contains("late=0/0"))
    }

    // a stall's backlog is fed as soon as its slots allow, lip-sync and the delay stay put, and
    // nothing is taken for a timestamp jump or chronic lateness
    private fun assertStallKeepsLipSync(stallMs: Long) {
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(10.0) { 40 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)
        val d = sim.p.delayNs
        assertEquals(234 * MS, d)

        val burst = sim.stall(stallMs * MS)
        assertTrue(burst.size >= stallMs * 24 / 1000)
        // never held past its normal slot (no stale replay), most fed on arrival
        assertTrue(burst.all { it.feedAt <= max(it.arrival, it.ptsMono + d - FramePacer.LEAD_NS) })
        assertTrue(burst.count { it.feedAt <= it.arrival } >= burst.size / 2)
        val after = sim.run(8.0) { 40 * MS }

        assertEquals(0, sim.p.jumps)
        assertEquals(0L, sim.p.correctionNs)
        assertTrue(sim.events.joinToString("\n"), sim.events.none { it.startsWith("timestamp jump") })
        assertTrue(sim.events.joinToString("\n"), sim.events.none { it.contains("behind sound") })
        assertEquals(Mode.AUDIO, sim.p.mode)
        assertEquals(1, sim.p.stalls)
        // lip-sync holds right through: picture delay within a few ms of the audio target
        assertTrue(after.maxOf { abs(it.delay - d) } <= 5 * MS)
        assertTrue((burst + after).all { abs(it.delay - d) <= 5 * MS })
        // and every frame is back on its slot within a second
        assertTrue(after.drop(24).all { it.feedAt > it.arrival })
    }

    @Test
    fun shortStallBurstKeepsLipSync() = assertStallKeepsLipSync(700)

    @Test
    fun stallLongerThanJumpThresholdIsNotAClockJump() = assertStallKeepsLipSync(1_500)

    @Test
    fun longStallIsNotAClockJump() = assertStallKeepsLipSync(3_000)

    @Test
    fun pastJumpWithJitteryArrivalIsStillDetected() {
        val rnd = Random(9)
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(5.0) { 30 * MS + rnd.nextLong(40 * MS) }
        sim.ptsShiftNs = -5_000 * MS
        val jumped = sim.run(5.0) { 30 * MS + rnd.nextLong(40 * MS) }
        assertEquals(1, sim.p.jumps)
        assertTrue(abs(sim.p.correctionNs - 5_000 * MS) <= 50 * MS)
        // paced provisionally until confirmed: nothing dumped, nothing frozen
        assertTrue(jumped.all { it.feedAt - it.arrival in -1..FramePacer.MAX_HOLD_NS })
        assertTrue(jumped.count { it.feedAt < it.arrival } <= 2)
    }

    @Test
    fun audioGapKeepsLastLipSyncDelayAsFloor() {
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(5.0) { 40 * MS }
        assertEquals(234 * MS, sim.p.delayNs)
        // audio stops (pause, underrun) while video keeps coming
        sim.audioNs = null
        val gap = sim.run(6.0) { 40 * MS }
        assertEquals(Mode.VIDEO, sim.p.mode)
        // video alone would need ~63 ms; the picture stays where lip-sync had it
        assertTrue(gap.all { it.delay == 234 * MS })
        assertTrue(sim.events.any { it.contains("pacing by video arrival, holding >= 234 ms") })
        // sound back: already in sync, no slewing
        sim.audioNs = 250 * MS
        val back = sim.run(3.0) { 40 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)
        assertTrue(back.all { it.delay == 234 * MS })
    }

    @Test
    fun videoNeedAboveFloorStillWinsWithoutAudio() {
        val rnd = Random(4)
        val sim = Sim()
        sim.audioNs = 120 * MS
        sim.run(5.0) { 40 * MS }
        sim.audioNs = null
        sim.run(15.0) { 150 * MS + rnd.nextLong(100 * MS) }
        assertEquals(Mode.VIDEO, sim.p.mode)
        assertTrue("delay ${sim.p.delayNs / MS}", sim.p.delayNs > 250 * MS)
    }

    @Test
    fun delayJumpsToNewAudioDelayWhileScreenIsStill() {
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(5.0) { 40 * MS }
        // paused movie: no frames for 3 s, sound resumes with a longer delay (re-primed cushion)
        sim.audioNs = 400 * MS
        sim.pause(3_000 * MS)
        val f = sim.frame(40 * MS)
        assertEquals(384 * MS, f.delay)
        assertEquals(Mode.AUDIO, sim.p.mode)
        // while frames flow, a change only slews
        sim.audioNs = 300 * MS
        val g = sim.run(1.0) { 40 * MS }
        val maxStep = (FRAME_NS * FramePacer.SLEW_RATE).toLong()
        assertTrue(intervals(g.map { it.delay }).all { abs(it) <= maxStep })
        assertTrue(g.last().delay > 300 * MS)
    }

    @Test
    fun firstLipSyncLockSnapsInsteadOfSlewing() {
        val sim = Sim()
        sim.run(6.0) { 40 * MS }
        assertEquals(Mode.VIDEO, sim.p.mode)
        assertTrue(sim.p.delayNs < 100 * MS)
        // the movie starts playing sound: one jump to lip-sync, not seconds of slow motion
        sim.audioNs = 300 * MS
        val f = sim.run(1.0) { 40 * MS }
        assertEquals(Mode.AUDIO, sim.p.mode)
        val first = f.first().delay
        assertTrue(f.all { it.delay == first || it.delay == 284 * MS })
        assertEquals(284 * MS, f.last().delay)
    }

    @Test
    fun smoothnessOverrideNeedsPersistentLateness() {
        val sim = Sim()
        sim.audioNs = 120 * MS  // lip-sync target 104 ms
        sim.run(5.0) { 40 * MS }
        // one second of frames just past the slot, then fine again: not chronic
        sim.run(1.0) { 120 * MS }
        sim.run(5.0) { 40 * MS }
        assertTrue(sim.events.none { it.contains("behind sound") })
        assertEquals(Mode.AUDIO, sim.p.mode)
        assertEquals(104 * MS, sim.p.delayNs)
    }

    @Test
    fun reportCountsStalls() {
        val sim = Sim()
        sim.audioNs = 250 * MS
        sim.run(5.0) { 40 * MS }
        sim.stall(800 * MS)
        sim.run(1.0) { 40 * MS }
        assertTrue(sim.p.report(sim.now).endsWith("stalls=1"))
        sim.p.resetInterval()
        assertTrue(sim.p.report(sim.now).contains("late=0/0"))
    }

    private fun assertNotNullLong(v: Long?): Long {
        assertNotNull(v)
        return v!!
    }

    companion object {
        // CLOCK_REALTIME - CLOCK_MONOTONIC
        private const val OFFSET = 1_700_000_000_000_000_000L
        private const val FRAME_NS = 1_000_000_000L / 24
    }
}

package io.github.jqssun.airplay.renderer

import java.util.Arrays
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/*
 * timing policy of the mirrored-video de-jitter buffer. pure kotlin (unit tested), not thread
 * safe: VideoRenderer calls it under its queue lock. times are ns; "mono" = System.nanoTime()
 * (CLOCK_MONOTONIC), sender timestamps arrive as CLOCK_REALTIME.
 *
 * a frame stamped pts is fed to the decoder at pts + delay - LEAD, so frames reach the screen
 * at the cadence they were captured instead of whenever wi-fi delivered them. delay tracks the
 * audio presentation delay (lip-sync) while audio plays, else a high percentile of how late
 * video arrives (never below the last lip-sync delay of the session); it moves only a little
 * per frame so motion never visibly speeds up or stalls, except where nothing is moving anyway
 * (first lip-sync lock, or no frame fed for a while: paused / static screen, stall)
 *
 * wi-fi stalls: frames stop, then the queued ones arrive at once. that backlog is fed as soon as
 * possible and kept out of the statistics (one stall must not look like chronic lateness and
 * pull the picture behind the sound), and a long stall is never mistaken for a clock jump: a
 * jump keeps a steady offset frame after frame, a stall's backlog does not
 */
class FramePacer {

    enum class Mode {
        AUDIO,            // delay follows the audio delay: lip-synced
        SMOOTH_OVER_SYNC, // video arrives later than the audio delay: picture held behind sound
        VIDEO,            // no audio delay: delay covers video arrival jitter only
    }

    // extra picture delay vs sound, positive = picture later; only applies while lip-syncing
    var userOffsetNs = 0L

    var delayNs = INITIAL_DELAY_NS; private set
    var targetDelayNs = INITIAL_DELAY_NS; private set
    var mode = Mode.VIDEO; private set
    var hasClockOffset = false; private set
    var clockOffsetNs = 0L; private set
    // re-anchor shift applied to video timestamps after a clock jump, 0 = none
    var correctionNs = 0L; private set
    var latenessP50Ns: Long? = null; private set
    var latenessP95Ns: Long? = null; private set
    var jumps = 0; private set
    var totalLate = 0L; private set
    // arrival gaps whose first frame came too late for its slot (wi-fi stalls)
    var stalls = 0; private set

    private var sessionStartNs = NONE
    private var lastPtsMonoNs = NONE
    private var lastFeedAtNs = NONE
    private var nextPolicyNs = NONE
    private var saneRawFrames = 0

    // stall backlog: after an arrival gap, frames captured before (gap end - usual lateness)
    private var lastArrivalNs = NONE
    private var backlogEndPtsNs = NONE
    private var backlogUntilNs = NONE

    // past-jump candidate: frames over JUMP_NS late at the normal cadence, paced provisionally
    private var jumpCandFrames = 0
    private var jumpCandRawNs = 0L
    private var jumpCandPtsNs = 0L
    private var jumpCandCorrNs = 0L

    // last audio delay this session (lip-sync floor while audio is missing), null = none yet
    private var lastAudioDelayNs: Long? = null
    private var snapPending = false
    private var overrideStreak = 0

    // arrival lateness of recent frames (mono now - pts), oldest at winHead
    private val winTime = LongArray(WINDOW_CAP)
    private val winLateness = LongArray(WINDOW_CAP)
    private val winLate = BooleanArray(WINDOW_CAP)
    private var winHead = 0
    private var winSize = 0
    private val scratch = LongArray(WINDOW_CAP)

    // recent audio delay samples, median filtered against one-off glitches
    private val audioSamples = LongArray(AUDIO_MEDIAN)
    private val audioScratch = LongArray(AUDIO_MEDIAN)
    private var audioCount = 0
    private var audioNext = 0
    private var audioLastNs = NONE

    private var intervalFrames = 0
    private var intervalLate = 0
    private val events = ArrayList<String>()

    // new session: start over from the initial delay; keeps clock offset and user offset
    fun reset() {
        delayNs = INITIAL_DELAY_NS
        targetDelayNs = INITIAL_DELAY_NS
        mode = Mode.VIDEO
        correctionNs = 0L
        latenessP50Ns = null
        latenessP95Ns = null
        jumps = 0
        totalLate = 0
        sessionStartNs = NONE
        lastPtsMonoNs = NONE
        lastFeedAtNs = NONE
        nextPolicyNs = NONE
        saneRawFrames = 0
        stalls = 0
        lastArrivalNs = NONE
        backlogEndPtsNs = NONE
        backlogUntilNs = NONE
        jumpCandFrames = 0
        lastAudioDelayNs = null
        snapPending = false
        overrideStreak = 0
        _clearWindow()
        audioCount = 0
        audioNext = 0
        audioLastNs = NONE
        intervalFrames = 0
        intervalLate = 0
        events.clear()
    }

    // CLOCK_REALTIME - CLOCK_MONOTONIC; returns the step when it moved more than CLOCK_STEP_NS
    // (realtime was set), else null
    fun setClockOffset(offsetNs: Long): Long? {
        if (!hasClockOffset) {
            hasClockOffset = true
            clockOffsetNs = offsetNs
            return null
        }
        val step = offsetNs - clockOffsetNs
        clockOffsetNs = offsetNs
        if (abs(step) <= CLOCK_STEP_NS) return null
        events += String.format(Locale.US, "realtime clock stepped %+d ms", step / MS)
        return step
    }

    // latest audio presentation delay (sample heard minus its timestamp), null = unknown
    fun onAudioDelay(audioDelayNs: Long?, nowNs: Long) {
        if (audioDelayNs == null || audioDelayNs < AUDIO_VALID_MIN_NS || audioDelayNs > AUDIO_VALID_MAX_NS) return
        // after a gap the old samples describe another stream state
        if (audioLastNs != NONE && nowNs - audioLastNs > AUDIO_STALE_NS) audioCount = 0
        audioSamples[audioNext] = audioDelayNs
        audioNext = (audioNext + 1) % AUDIO_MEDIAN
        if (audioCount < AUDIO_MEDIAN) audioCount++
        audioLastNs = nowNs
    }

    // median of recent audio delay samples, null when none arrived for AUDIO_STALE_NS
    fun audioDelayNs(nowNs: Long): Long? {
        if (audioCount == 0 || nowNs - audioLastNs > AUDIO_STALE_NS) return null
        val n = audioCount
        val start = if (n < AUDIO_MEDIAN) 0 else audioNext
        for (i in 0 until n) audioScratch[i] = audioSamples[(start + i) % AUDIO_MEDIAN]
        Arrays.sort(audioScratch, 0, n)
        return audioScratch[n / 2]
    }

    /*
     * frame arrived now: returns when to feed it (mono ns). never later than now + MAX_HOLD_NS
     * and never earlier than the previous frame's feed time (decode order is queue order)
     */
    fun schedule(ptsRealtimeNs: Long, nowNs: Long): Long {
        if (sessionStartNs == NONE) sessionStartNs = nowNs
        update(nowNs)
        // sender not NTP synced yet: nothing to pace by, keep order and feed asap
        if (ptsRealtimeNs == 0L || !hasClockOffset) return _ordered(nowNs, nowNs)

        val rawPtsMonoNs = ptsRealtimeNs - clockOffsetNs
        val raw = nowNs - rawPtsMonoNs
        if (correctionNs != 0L) {
            // clocks agree again (e.g. sender time mapping re-synced): drop the correction
            saneRawFrames = if (raw > -SNAP_NS && raw < JUMP_NS / 2) saneRawFrames + 1 else 0
            if (saneRawFrames >= SANE_FRAMES_TO_DROP) _setCorrection(0L, raw)
        }
        if (raw - correctionNs < -JUMP_NS) {
            // far in the future: no network delay makes a frame early, so the timestamps jumped:
            // re-anchor so this frame is as late as usual. no change means the stream itself is
            // that early: just held (bounded by MAX_HOLD_NS)
            val corr = _anchorFor(raw)
            if (corr != correctionNs) _setCorrection(corr, raw)
        }

        // a gap in arrivals ended with this frame: a static screen, or a wi-fi stall whose
        // queued frames now arrive at once. frames captured before this arrival (minus the usual
        // lateness) are that stale backlog: fed as soon as their slot allows, never counted as
        // lateness, never taken for a clock jump. bounded, so a jump after a pause still shows
        val gap = if (lastArrivalNs == NONE) 0L else nowNs - lastArrivalNs
        lastArrivalNs = nowNs
        val head = gap > STALL_GAP_NS
        if (head) {
            backlogEndPtsNs = nowNs - (latenessP50Ns ?: 0L)
            backlogUntilNs = nowNs + MAX_BACKLOG_NS
        }
        var ptsMonoNs = rawPtsMonoNs + correctionNs
        val backlog = !head && backlogUntilNs != NONE && nowNs < backlogUntilNs && ptsMonoNs < backlogEndPtsNs
        if (!head && !backlog) backlogUntilNs = NONE

        var lateness = raw - correctionNs
        var provisional = false
        if (lateness > JUMP_NS && !head && !backlog) {
            // over a second late at the normal arrival cadence: timestamps jumped into the past
            // (or the network is that slow). a jump keeps raw steady frame after frame (a stall's
            // backlog has raw falling one frame interval per frame); until confirmed, pace these
            // frames as if re-anchored, which is seamless when it is a jump
            if (jumpCandFrames > 0 && abs(raw - jumpCandRawNs) <= JUMP_TOL_NS) {
                jumpCandFrames++
            } else {
                val corr = _anchorFor(raw)
                jumpCandFrames = if (corr != correctionNs) 1 else 0
                jumpCandRawNs = raw
                jumpCandPtsNs = rawPtsMonoNs
                jumpCandCorrNs = corr
            }
            if (jumpCandFrames >= JUMP_CONFIRM_FRAMES && rawPtsMonoNs - jumpCandPtsNs >= JUMP_CONFIRM_SPAN_NS) {
                _setCorrection(jumpCandCorrNs, raw)
                lateness = raw - correctionNs
                ptsMonoNs = rawPtsMonoNs + correctionNs
            } else if (jumpCandFrames > 0) {
                provisional = true
                lateness = raw - jumpCandCorrNs
                ptsMonoNs = rawPtsMonoNs + jumpCandCorrNs
            }
        } else {
            jumpCandFrames = 0
        }

        _slew(ptsMonoNs, nowNs)
        val feedAtNs = ptsMonoNs + delayNs - LEAD_NS
        val late = nowNs > feedAtNs
        if (head && late) stalls++
        _record(nowNs, lateness, late, inWindow = !backlog && !provisional && abs(lateness) <= JUMP_NS)
        lastPtsMonoNs = ptsMonoNs
        return _ordered(feedAtNs, nowNs)
    }

    // recompute the delay target from the latest audio delay and lateness stats, at most every
    // POLICY_INTERVAL_NS; schedule() calls it, call it also when idle so mode changes surface
    fun update(nowNs: Long) {
        if (nextPolicyNs != NONE && nowNs < nextPolicyNs) return
        nextPolicyNs = nowNs + POLICY_INTERVAL_NS
        _evict(nowNs)
        _computePercentiles()
        val videoNeed = videoNeedNs()
        val audio = if (correctionNs == 0L) audioDelayNs(nowNs) else null
        val prev = mode
        if (audio == null) {
            mode = Mode.VIDEO
            overrideStreak = 0
            if (videoNeed != null) targetDelayNs = videoNeed
            // keep the session's last lip-sync delay as a floor: when sound returns after a
            // pause or a stall the picture is already there instead of slewing for seconds
            lastAudioDelayNs?.let { targetDelayNs = max(targetDelayNs, audioTargetNs(it)) }
        } else {
            // first lip-sync lock of the session: jump there rather than slew for seconds
            if (lastAudioDelayNs == null) snapPending = true
            lastAudioDelayNs = audio
            val audioTarget = audioTargetNs(audio)
            if (mode != Mode.SMOOTH_OVER_SYNC) {
                // only chronic lateness trades lip-sync for smoothness: late frames spread over
                // the window (not one congested second) for several policy intervals in a row.
                // stall backlogs are not in the window at all
                val lateFrac = _lateFraction(OVERRIDE_MIN_SAMPLES)
                val over = lateFrac != null && lateFrac > LATE_LIMIT && videoNeed != null && videoNeed > audioTarget &&
                    _lateTicks(nowNs) >= LATE_SPREAD_TICKS
                overrideStreak = if (over) overrideStreak + 1 else 0
                mode = if (over && overrideStreak >= OVERRIDE_PERSIST_INTERVALS) {
                    events += String.format(Locale.US,
                        "video arrives later than the lip-sync delay: %.1f%% of frames late at D=%d ms (audio %d ms); " +
                            "delaying picture to %d ms for smoothness, picture ~%d ms behind sound",
                        lateFrac!! * 100, delayNs / MS, audio / MS, videoNeed!! / MS, (videoNeed - audioTarget) / MS)
                    Mode.SMOOTH_OVER_SYNC
                } else Mode.AUDIO
            } else if (videoNeed == null || videoNeed + SMOOTH_HYSTERESIS_NS <= audioTarget) {
                events += String.format(Locale.US,
                    "video back within the lip-sync delay (needs %s ms, audio target %d ms): lip-sync restored",
                    videoNeed?.let { (it / MS).toString() } ?: "n/a", audioTarget / MS)
                mode = Mode.AUDIO
                overrideStreak = 0
            }
            targetDelayNs = if (mode == Mode.SMOOTH_OVER_SYNC && videoNeed != null) max(audioTarget, videoNeed) else audioTarget
        }
        if (mode != prev) {
            when {
                mode == Mode.VIDEO -> events += "no audio delay${if (correctionNs != 0L) " (timestamps re-anchored)" else ""}: " +
                    "pacing by video arrival" +
                    (lastAudioDelayNs?.let { ", holding >= ${audioTargetNs(it) / MS} ms for lip-sync" } ?: "")
                prev == Mode.VIDEO -> events += String.format(Locale.US,
                    "lip-sync: pacing to audio delay %d ms (target %d ms)", audio!! / MS, targetDelayNs / MS)
            }
        }
    }

    // delay that keeps picture with sound: audio delay minus display latency, plus user trim
    fun audioTargetNs(audioDelayNs: Long): Long =
        ((audioDelayNs - DISPLAY_COMP_NS).coerceIn(MIN_DELAY_NS, MAX_DELAY_NS) + userOffsetNs)
            .coerceIn(0L, MAX_TOTAL_DELAY_NS)

    // delay that lets ~95% of frames arrive before their slot, null until enough samples
    fun videoNeedNs(): Long? =
        latenessP95Ns?.let { (it + LEAD_NS + VIDEO_MARGIN_NS).coerceIn(MIN_DELAY_NS, MAX_DELAY_NS) }

    // diagnostics since the last call; caller logs them
    fun takeEvents(): List<String> {
        if (events.isEmpty()) return emptyList()
        val out = ArrayList(events)
        events.clear()
        return out
    }

    // one diagnostics line covering frames since the previous report
    fun report(nowNs: Long): String {
        _evict(nowNs)
        _computePercentiles()
        val audio = audioDelayNs(nowNs)
        val pct = if (intervalFrames > 0) 100.0 * intervalLate / intervalFrames else 0.0
        val line = String.format(Locale.US,
            "D=%dms target=%dms mode=%s audio=%s lateness p50=%s p95=%s late=%d/%d (%.1f%%) offset=%dms%s",
            delayNs / MS, targetDelayNs / MS, mode.name.lowercase(Locale.US),
            audio?.let { "${it / MS}ms" } ?: "n/a",
            _ms(latenessP50Ns), _ms(latenessP95Ns),
            intervalLate, intervalFrames, pct, userOffsetNs / MS,
            (if (correctionNs != 0L || jumps > 0) " corr=${correctionNs / MS}ms jumps=$jumps" else "") +
                if (stalls > 0) " stalls=$stalls" else "")
        resetInterval()
        return line
    }

    // start a new report interval without building the line (reporting off)
    fun resetInterval() {
        intervalFrames = 0
        intervalLate = 0
    }

    private fun _ms(ns: Long?) = ns?.let { "${it / MS}ms" } ?: "n/a"

    // correction that makes a frame that is raw late (uncorrected) as late as usual
    private fun _anchorFor(raw: Long): Long {
        val corr = raw - (latenessP50Ns ?: 0L)
        return if (abs(corr) < SNAP_NS) 0L else corr
    }

    private fun _setCorrection(corr: Long, raw: Long) {
        events += String.format(Locale.US,
            "timestamp jump: frame %+d ms vs now; re-anchored, correction %d -> %d ms",
            -raw / MS, correctionNs / MS, corr / MS)
        correctionNs = corr
        saneRawFrames = 0
        jumps++
        // lateness from the old mapping means nothing now
        _clearWindow()
        latenessP50Ns = null
        latenessP95Ns = null
        lastPtsMonoNs = NONE
        lastFeedAtNs = NONE
        nextPolicyNs = NONE
        jumpCandFrames = 0
        backlogUntilNs = NONE
    }

    // move delay toward target: free during the first SNAP_WINDOW_NS of a session, on the first
    // lip-sync lock, and when no frame was fed for FREE_SLEW_IDLE_NS (the picture is still, so
    // the change can't show); otherwise at most SLEW_RATE of the frame interval (speed change
    // <= 4%) but at least SLEW_MIN_STEP_NS
    private fun _slew(ptsMonoNs: Long, nowNs: Long) {
        val target = targetDelayNs
        val idle = lastFeedAtNs != NONE && nowNs - lastFeedAtNs > FREE_SLEW_IDLE_NS
        if (nowNs - sessionStartNs < SNAP_WINDOW_NS || snapPending || idle) {
            snapPending = false
            delayNs = target
            return
        }
        val dPts = if (lastPtsMonoNs == NONE) 0L else (ptsMonoNs - lastPtsMonoNs).coerceIn(0L, MAX_SLEW_INTERVAL_NS)
        val step = max(SLEW_MIN_STEP_NS, (dPts * SLEW_RATE).toLong())
        delayNs += (target - delayNs).coerceIn(-step, step)
    }

    private fun _ordered(feedAtNs: Long, nowNs: Long): Long {
        var f = feedAtNs
        if (lastFeedAtNs != NONE && f < lastFeedAtNs) f = lastFeedAtNs
        if (f > nowNs + MAX_HOLD_NS) f = nowNs + MAX_HOLD_NS
        lastFeedAtNs = f
        return f
    }

    // totals always; the policy window only for frames that say something about the network
    // (not stall backlog, not unconfirmed jumps). lateness beyond MAX_DELAY_NS is capped for the
    // percentiles and not counted late: no delay we would use could have saved that frame
    private fun _record(nowNs: Long, latenessNs: Long, late: Boolean, inWindow: Boolean) {
        intervalFrames++
        if (late) {
            totalLate++
            intervalLate++
        }
        if (!inWindow) return
        _evict(nowNs)
        if (winSize == WINDOW_CAP) {
            winHead = (winHead + 1) % WINDOW_CAP
            winSize--
        }
        val i = (winHead + winSize) % WINDOW_CAP
        winTime[i] = nowNs
        winLateness[i] = minOf(latenessNs, MAX_DELAY_NS)
        winLate[i] = late && latenessNs <= MAX_DELAY_NS
        winSize++
    }

    private fun _evict(nowNs: Long) {
        while (winSize > 0 && nowNs - winTime[winHead] > WINDOW_NS) {
            winHead = (winHead + 1) % WINDOW_CAP
            winSize--
        }
    }

    private fun _clearWindow() {
        winHead = 0
        winSize = 0
    }

    private fun _computePercentiles() {
        val n = winSize
        if (n < MIN_SAMPLES) {
            latenessP50Ns = null
            latenessP95Ns = null
            return
        }
        for (i in 0 until n) scratch[i] = winLateness[(winHead + i) % WINDOW_CAP]
        Arrays.sort(scratch, 0, n)
        latenessP50Ns = scratch[_rank(50, n)]
        latenessP95Ns = scratch[_rank(95, n)]
    }

    // nearest-rank percentile index
    private fun _rank(pct: Int, n: Int) = (ceil(pct * n / 100.0).toInt().coerceIn(1, n)) - 1

    private fun _lateFraction(minSamples: Int): Double? {
        if (winSize < minSamples) return null
        var late = 0
        for (i in 0 until winSize) if (winLate[(winHead + i) % WINDOW_CAP]) late++
        return late.toDouble() / winSize
    }

    // policy intervals of the window holding at least one late frame
    private fun _lateTicks(nowNs: Long): Int {
        var mask = 0L
        for (i in 0 until winSize) {
            val j = (winHead + i) % WINDOW_CAP
            if (!winLate[j]) continue
            val tick = ((nowNs - winTime[j]) / POLICY_INTERVAL_NS).coerceIn(0L, 63L)
            mask = mask or (1L shl tick.toInt())
        }
        return java.lang.Long.bitCount(mask)
    }

    companion object {
        const val MS = 1_000_000L
        private const val NONE = Long.MIN_VALUE

        const val INITIAL_DELAY_NS = 200 * MS
        const val MIN_DELAY_NS = 60 * MS
        // "movie mode" headroom: lip-sync up to this audio delay (audio cushion floor is 250 ms)
        const val MAX_DELAY_NS = 800 * MS
        // with the user offset on top; the queue holds a frame at most MAX_HOLD_NS
        const val MAX_TOTAL_DELAY_NS = 900 * MS
        // feed this long before the frame's slot: decode takes ~3 ms
        const val LEAD_NS = 8 * MS
        // decoder output -> light on screen, ~1 vsync at 60 Hz
        const val DISPLAY_COMP_NS = 16 * MS
        // on top of the p95 arrival lateness when pacing by video
        const val VIDEO_MARGIN_NS = 15 * MS
        const val SMOOTH_HYSTERESIS_NS = 15 * MS
        // more than this share of frames missing their slot while lip-syncing: favour smoothness
        const val LATE_LIMIT = 0.05

        const val WINDOW_NS = 5_000 * MS
        const val WINDOW_CAP = 512
        const val MIN_SAMPLES = 24
        const val OVERRIDE_MIN_SAMPLES = 48
        // consecutive policy intervals the late share must exceed LATE_LIMIT before lip-sync is
        // traded for smoothness
        const val OVERRIDE_PERSIST_INTERVALS = 4
        // ... and late frames must show up in at least this many policy intervals of the window
        const val LATE_SPREAD_TICKS = 6
        const val POLICY_INTERVAL_NS = 250 * MS

        // |lateness| beyond this is a clock/timestamp jump, not network delay
        const val JUMP_NS = 1_000 * MS
        // re-anchor corrections smaller than this are dropped: the raw clocks are fine
        const val SNAP_NS = 250 * MS
        const val SANE_FRAMES_TO_DROP = 10
        // a past jump is confirmed after this many frames (spanning at least this much capture
        // time) whose raw lateness stays within JUMP_TOL_NS of the first one
        const val JUMP_CONFIRM_FRAMES = 6
        const val JUMP_CONFIRM_SPAN_NS = 200 * MS
        const val JUMP_TOL_NS = 100 * MS
        const val MAX_HOLD_NS = 1_000 * MS

        // arrival gap that marks a stall (or a static screen); its backlog must drain within
        // MAX_BACKLOG_NS of the gap ending
        const val STALL_GAP_NS = 250 * MS
        const val MAX_BACKLOG_NS = 2_000 * MS

        const val SLEW_MIN_STEP_NS = 1 * MS
        const val SLEW_RATE = 0.04
        const val MAX_SLEW_INTERVAL_NS = 1_000 * MS
        const val SNAP_WINDOW_NS = 2_000 * MS
        // nothing fed for this long: the picture is still, delay may jump to its target
        const val FREE_SLEW_IDLE_NS = 500 * MS

        const val AUDIO_STALE_NS = 2_000 * MS
        const val AUDIO_MEDIAN = 5
        const val AUDIO_VALID_MIN_NS = -200 * MS
        const val AUDIO_VALID_MAX_NS = 3_000 * MS
        const val CLOCK_STEP_NS = 20 * MS
    }
}

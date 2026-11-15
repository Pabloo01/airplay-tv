package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/*
 * mirrored video -> MediaCodec -> VideoPipeline.
 *
 * smooth playback (default): feedFrame() only queues the compressed frame; a scheduler thread
 * feeds each one at its timestamp + delay (FramePacer) and renders its output right away, so
 * wi-fi burstiness no longer reaches the screen. off, or outside a mirroring session, frames
 * are decoded on the caller's thread as they arrive.
 *
 * locks: `lock` guards codec/pipeline/stats, `qLock` the frame queue + pacer; order is
 * lock -> qLock, never the reverse
 */
class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile private var codecDrops = 0L
    private val queueDrops = AtomicLong()
    val droppedFrames: Long get() = codecDrops + queueDrops.get()
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    // periodic diagnostics (BENCHMARK lines, VideoPacing reports); read on the scheduler thread
    @Volatile var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    // crop black bars the sender bakes into the frame (pillar/letterbox around a movie)
    var autoCrop: Boolean
        get() = pipeline.autoCrop
        set(v) { pipeline.autoCrop = v }
    var overscan: Boolean
        get() = pipeline.overscan
        set(v) { pipeline.overscan = v }
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    private val _outInfo = MediaCodec.BufferInfo()

    // ---- smooth playback (compressed-frame de-jitter buffer) ----

    // requested mode, latched at session start so a live toggle never reorders queued frames
    @Volatile var smoothPlayback = true
    // picture delay vs sound in ms, positive = picture later; applies live
    @Volatile var avOffsetMs = 0
    // audio presentation delay in ns (sample heard minus its timestamp), null = unknown; must
    // not block, polled from the scheduler thread
    @Volatile var audioDelayProvider: (() -> Long?)? = null

    private class QueuedFrame(
        val data: ByteArray, val ptsNs: Long, val isH265: Boolean,
        val enqueuedNs: Long, val feedAtNs: Long,
    )

    private val qLock = ReentrantLock()
    private val qCond = qLock.newCondition()
    // FIFO = decode order; FramePacer keeps feed times non-decreasing, so it is also target order
    private val queue = ArrayDeque<QueuedFrame>()   // qLock
    private var queueBytes = 0L                     // qLock
    private val pacer = FramePacer()                // qLock
    private var schedulerToken: Any? = null         // qLock; running scheduler's token, null = immediate mode
    private var schedulerThread: Thread? = null     // qLock
    // bumped (under qLock) whenever the queue is flushed: a frame popped before the flush is
    // discarded instead of (re)starting a codec that is being stopped
    @Volatile private var queueGen = 0
    @Volatile private var headFeedAtNs = Long.MAX_VALUE
    // interval diagnostics, qLock
    private var qDepthSum = 0L
    private var qDepthN = 0
    private var qDepthMax = 0
    private var fedLate = 0
    private var fedLateMaxNs = 0L

    // paced decode state, lock
    private var pacedSession = false
    @Volatile private var lastFeedNs = 0L
    private var lastFedPtsUs = 0L
    @Volatile private var lastFedOutput = true
    private var outLatSumNs = 0L
    private var outLatN = 0
    private var outLatMaxNs = 0L
    // keyframes decoded this session (decoder resets wait for one), lock
    private var keyframes = 0L
    private val codecDropLog = DropLog(TAG, "decoder input queue full")  // lock
    private val queueDropLog = DropLog(PACING_TAG, "frame queue full")    // qLock

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
        // queued frames belong to the old stream; the sender restarts with a keyframe
        qLock.withLock { _flushLocked() }
        stopCodec()
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean?): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265 != false) selector.hevc(avcDecoder, w, h, fps, force = h265 == true) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() {
        val paced = smoothPlayback
        synchronized(lock) {
            _resetStats()
            pipeline.resetCrop()
            pacedSession = paced
        }
        if (paced) {
            _startScheduler()
        } else {
            _stopScheduler()
            Log.i(PACING_TAG, "smooth playback off: frames decoded on arrival")
        }
    }

    fun stopSession() {
        _stopScheduler()
        synchronized(lock) {
            stopCodec()
            pacedSession = false
        }
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        codecDrops = 0; queueDrops.set(0); framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        keyframes = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        try {
            benchmarkLogCallback?.invoke(msg)
        } catch (e: Exception) {
            Log.w(BENCH_TAG, "benchmark log callback failed", e)
        }
    }

    // native callback thread; smooth playback only queues the frame and returns
    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        if (_enqueue(data, ntpTimeNs, isH265)) return
        synchronized(lock) { _decode(data, ntpTimeNs, isH265, paced = false) }
    }

    // keyframe gating, codec (re)start, feed + render; caller holds lock
    private fun _decode(data: ByteArray, ntpTimeNs: Long, isH265: Boolean, paced: Boolean) {
        _updateStats(data.size)
        if (videoWidth == 0 || videoHeight == 0) return
        // header-only scan: keyframes carry parameter sets / IDR up front
        val key = _isKeyframe(data, isH265, KEYFRAME_SCAN_BYTES)
        if (key) keyframes++

        if (codec == null || isH265 != currentH265) {
            // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
            if (!key && !_isKeyframe(data, isH265)) {
                if (codec != null) stopCodec()
                return
            }
            stopCodec()
        }

        try {
            if (codec == null) startCodec(isH265)
            _feedToCodec(data, ntpTimeNs)
            if (paced) _awaitOutput() else drainOutput()
        } catch (e: Exception) {
            Log.w(TAG, "Codec error, resetting", e)
            stopCodec()
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                val ptsUs = ntpTimeNs / 1000
                c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                firstFrameQueued = true
                lastFeedNs = System.nanoTime()
                lastFedPtsUs = ptsUs
                lastFedOutput = false
                return
            }
            drainOutput()
        }
        codecDrops++
        codecDropLog.hit(droppedFrames)
    }

    // scans for a keyframe NAL (IDR / parameter sets) in the first `limit` bytes
    private fun _isKeyframe(data: ByteArray, isH265: Boolean, limit: Int = Int.MAX_VALUE): Boolean {
        if (data.size < 5) return false
        val end = minOf(data.size, limit) - 5
        var i = 0
        while (i <= end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        lastFedOutput = true
        codec?.let {
            // a reclaimed / errored codec can throw from stop(); release it regardless
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
    }

    // renders every decoded frame that is ready, waiting up to timeoutUs for the first; returns count
    private fun drainOutput(timeoutUs: Long = 0L): Int {
        val c = codec ?: return 0
        val info = _outInfo
        var rendered = 0
        var wait = timeoutUs
        while (true) {
            val idx = c.dequeueOutputBuffer(info, wait)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx < 0) break
            wait = 0L
            _recordOutputFrameTime()
            if (!lastFedOutput && info.presentationTimeUs == lastFedPtsUs) {
                lastFedOutput = true
                _recordOutputLatency(System.nanoTime() - lastFeedNs)
            }
            // show it now: when paced the frame is already at its slot, and the SurfaceTexture
            // consumer would ignore a release timestamp anyway
            c.releaseOutputBuffer(idx, true)
            rendered++
        }
        return rendered
    }

    // paced: render the frame just fed as soon as it decodes (~3 ms), not at the next feed;
    // gives up when the next frame is due or after OUTPUT_WAIT_NS (decoder holding frames)
    private fun _awaitOutput() {
        val deadline = System.nanoTime() + OUTPUT_WAIT_NS
        while (codec != null) {
            drainOutput(OUTPUT_POLL_US)
            if (lastFedOutput) return
            val now = System.nanoTime()
            if (now >= deadline || now >= headFeedAtNs) return
        }
    }

    private fun _recordOutputLatency(ns: Long) {
        outLatSumNs += ns
        outLatN++
        if (ns > outLatMaxNs) outLatMaxNs = ns
    }

    fun release() {
        _stopScheduler()
        synchronized(lock) {
            stopCodec()
            pacedSession = false
            pipeline.release()
            _resetStats()
        }
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    // ---- scheduler ----

    // true if the running scheduler took the frame; false = decode it now (smooth off / no session)
    private fun _enqueue(data: ByteArray, ptsNs: Long, isH265: Boolean): Boolean {
        val events: List<String>
        qLock.withLock {
            if (schedulerToken == null) return false
            if (queue.size >= HARD_MAX_FRAMES || queueBytes + data.size > HARD_MAX_BYTES) {
                // scheduler stalled (codec hang): bound memory, at the cost of a corrupt
                // picture until the next keyframe, like a full decoder input queue
                val n = queueDrops.incrementAndGet()
                queueDropLog.hit(n, "${queue.size} frames, ${queueBytes / 1024} KiB queued")
                return true
            }
            if (!pacer.hasClockOffset) pacer.setClockOffset(_clockOffsetNs())
            pacer.userOffsetNs = avOffsetMs * FramePacer.MS
            val now = System.nanoTime()
            val feedAt = pacer.schedule(ptsNs, now)
            queue.addLast(QueuedFrame(data, ptsNs, isH265, now, feedAt))
            queueBytes += data.size
            if (queue.size == 1) headFeedAtNs = feedAt
            qDepthSum += queue.size
            qDepthN++
            if (queue.size > qDepthMax) qDepthMax = queue.size
            qCond.signal()
            events = pacer.takeEvents()
        }
        for (e in events) Log.i(PACING_TAG, e)
        return true
    }

    private fun _startScheduler() {
        val offsetNs = _clockOffsetNs()
        qLock.withLock {
            _flushLocked()
            pacer.reset()
            pacer.setClockOffset(offsetNs)
            pacer.userOffsetNs = avOffsetMs * FramePacer.MS
            _resetIntervalLocked()
            pacer.takeEvents().forEach { Log.i(PACING_TAG, it) }
            if (schedulerToken != null) return
            val token = Any()
            schedulerToken = token
            schedulerThread = Thread({ _schedulerLoop(token) }, "VideoScheduler").also { it.start() }
        }
        Log.i(PACING_TAG, "smooth playback on: initial delay ${FramePacer.INITIAL_DELAY_NS / FramePacer.MS}ms, offset ${avOffsetMs}ms")
    }

    // stops the scheduler thread and drops queued frames; never called with lock held
    private fun _stopScheduler() {
        val t: Thread?
        qLock.withLock {
            schedulerToken = null
            _flushLocked()
            t = schedulerThread
            schedulerThread = null
            qCond.signalAll()
        }
        if (t == null || t === Thread.currentThread()) return
        // longest step it can be in is a codec (re)start or input retry, well under this
        t.join(JOIN_TIMEOUT_MS)
        if (t.isAlive) Log.w(PACING_TAG, "scheduler thread still busy after ${JOIN_TIMEOUT_MS}ms; it exits on its own")
    }

    // qLock held
    private fun _flushLocked() {
        queue.clear()
        queueBytes = 0
        headFeedAtNs = Long.MAX_VALUE
        queueGen++
        qCond.signalAll()
    }

    // qLock held
    private fun _resetIntervalLocked() {
        qDepthSum = 0
        qDepthN = 0
        qDepthMax = 0
        fedLate = 0
        fedLateMaxNs = 0
    }

    private fun _schedulerLoop(token: Any) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Exception) {
            Log.w(PACING_TAG, "can't raise scheduler priority", e)
        }
        val start = System.nanoTime()
        var nextPollNs = start
        var nextClockNs = start + CLOCK_REFRESH_NS
        var nextReportNs = start + REPORT_INTERVAL_NS
        var nextIdleDrainNs = Long.MAX_VALUE
        var errors = 0
        var errorWindowStartNs = start
        try {
            while (true) {
                try {
                    var frame: QueuedFrame? = null
                    var gen = 0
                    qLock.withLock {
                        while (true) {
                            if (schedulerToken !== token) return
                            val now = System.nanoTime()
                            val head = queue.peekFirst()
                            if (head != null && (head.feedAtNs <= now || queue.size > SOFT_MAX_FRAMES ||
                                    now - head.enqueuedNs >= MAX_QUEUE_SPAN_NS)) {
                                queue.pollFirst()
                                queueBytes -= head.data.size
                                headFeedAtNs = queue.peekFirst()?.feedAtNs ?: Long.MAX_VALUE
                                val lateBy = now - head.feedAtNs
                                if (lateBy > FED_LATE_TOLERANCE_NS) {
                                    fedLate++
                                    if (lateBy > fedLateMaxNs) fedLateMaxNs = lateBy
                                }
                                frame = head
                                gen = queueGen
                                break
                            }
                            val wakeAt = minOf(head?.feedAtNs ?: Long.MAX_VALUE, nextPollNs, nextIdleDrainNs)
                            if (now >= wakeAt) break
                            qCond.awaitNanos(wakeAt - now)
                        }
                    }

                    val f = frame
                    if (f != null) {
                        synchronized(lock) {
                            // flushed after the pop (stop / size change): never restart a codec for it
                            if (gen == queueGen) _decode(f.data, f.ptsNs, f.isH265, paced = true)
                        }
                        nextIdleDrainNs = if (lastFedOutput) Long.MAX_VALUE else System.nanoTime() + IDLE_DRAIN_POLL_NS
                    }
                    val now = System.nanoTime()
                    if (f == null && now >= nextIdleDrainNs) {
                        // last frame's output wasn't ready yet and nothing else is due: fetch it
                        _idleDrain()
                        nextIdleDrainNs = if (!lastFedOutput && now - lastFeedNs < IDLE_DRAIN_WINDOW_NS) {
                            now + IDLE_DRAIN_POLL_NS
                        } else Long.MAX_VALUE
                    }
                    if (now >= nextPollNs) {
                        nextPollNs = now + AUDIO_POLL_NS
                        _housekeeping(token, now, now >= nextClockNs, now >= nextReportNs)
                        if (now >= nextClockNs) nextClockNs = now + CLOCK_REFRESH_NS
                        if (now >= nextReportNs) nextReportNs = now + REPORT_INTERVAL_NS
                    }
                } catch (t: Throwable) {
                    // never let a decoder failure kill the process (nothing catches this thread):
                    // e.g. the codec reclaimed while backgrounded. reset it and carry on; the
                    // next keyframe restarts decoding. give up only if it keeps failing
                    Log.e(PACING_TAG, "scheduler error; resetting decoder", t)
                    _stopCodecSafely()
                    nextIdleDrainNs = Long.MAX_VALUE
                    val now = System.nanoTime()
                    if (now - errorWindowStartNs > ERROR_WINDOW_NS) {
                        errorWindowStartNs = now
                        errors = 0
                    }
                    if (++errors >= MAX_ERRORS_PER_WINDOW) {
                        Log.e(PACING_TAG, "scheduler keeps failing ($errors errors); smooth playback off for this session")
                        return
                    }
                }
            }
        } finally {
            _retireScheduler(token)
        }
    }

    // scheduler thread, nothing due: render the last frame's output if it is ready now
    private fun _idleDrain() = synchronized(lock) {
        try {
            drainOutput()
        } catch (e: Exception) {
            Log.w(TAG, "Codec error while draining, resetting", e)
            stopCodec()
        }
    }

    private fun _stopCodecSafely() {
        try {
            synchronized(lock) { stopCodec() }
        } catch (t: Throwable) {
            Log.w(TAG, "decoder reset failed", t)
        }
    }

    // scheduler thread exiting. if it still owns the session (gave up after errors rather than
    // being stopped), hand frames back to immediate decode so _enqueue never queues for a dead
    // thread
    private fun _retireScheduler(token: Any) {
        val retired = qLock.withLock {
            if (schedulerToken !== token) return@withLock false
            schedulerToken = null
            if (schedulerThread === Thread.currentThread()) schedulerThread = null
            _flushLocked()
            true
        }
        if (!retired) return
        try {
            synchronized(lock) {
                pacedSession = false
                stopCodec()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decoder reset failed", t)
        }
        Log.w(PACING_TAG, "scheduler retired: frames decoded on arrival until the next session")
    }

    // scheduler thread: feed the pacer the audio delay (and clock offset), log diagnostics
    private fun _housekeeping(token: Any, now: Long, refreshClock: Boolean, report: Boolean) {
        val audio = audioDelayProvider?.invoke()
        val offsetNs = if (refreshClock) _clockOffsetNs() else null
        var line: String? = null
        val events: List<String>
        qLock.withLock {
            if (schedulerToken !== token) return
            if (offsetNs != null) pacer.setClockOffset(offsetNs)
            pacer.onAudioDelay(audio, now)
            pacer.userOffsetNs = avOffsetMs * FramePacer.MS
            pacer.update(now)
            if (report) {
                // periodic line only with benchmark logging (a 2 h film is ~1400 of them);
                // mode changes / clock steps (events) are always logged
                if (benchmarkLog) {
                    line = pacer.report(now) + String.format(Locale.US,
                        " queue=%d (avg %.1f max %d) fedLate=%d (max +%.1fms)",
                        queue.size, if (qDepthN > 0) qDepthSum.toDouble() / qDepthN else 0.0, qDepthMax,
                        fedLate, fedLateMaxNs / 1e6)
                } else {
                    pacer.resetInterval()
                }
                _resetIntervalLocked()
            }
            events = pacer.takeEvents()
        }
        for (e in events) Log.i(PACING_TAG, e)
        if (!report) return
        val out = synchronized(lock) {
            val s = if (outLatN > 0) String.format(Locale.US, " decode=%.1fms (max %.1fms)",
                outLatSumNs / 1e6 / outLatN, outLatMaxNs / 1e6) else " decode=n/a"
            outLatSumNs = 0
            outLatN = 0
            outLatMaxNs = 0
            "$s keyframes=$keyframes"
        }
        val l = line ?: return
        Log.i(PACING_TAG, l + out)
    }

    // CLOCK_REALTIME - CLOCK_MONOTONIC: maps sender timestamps onto System.nanoTime()
    private fun _clockOffsetNs(): Long = try {
        NativeBridge.nativeRealtimeOffsetNs()
    } catch (e: UnsatisfiedLinkError) {
        // ms precision only; native lib is always loaded once a server runs
        System.currentTimeMillis() * FramePacer.MS - System.nanoTime()
    }

    // at most one line per DROP_LOG_INTERVAL_NS for a recurring drop; counts what it held back.
    // not thread safe: each instance is used under one lock
    private class DropLog(private val tag: String, private val what: String) {
        private var lastNs = 0L
        private var logged = false
        private var pending = 0

        fun hit(total: Long, detail: String? = null) {
            pending++
            val now = System.nanoTime()
            if (logged && now - lastNs < DROP_LOG_INTERVAL_NS) return
            val span = if (logged) " in the last ${(now - lastNs) / FramePacer.MS} ms" else ""
            Log.w(tag, "$what${detail?.let { " ($it)" } ?: ""}: dropped $pending frame(s)$span, total $total")
            lastNs = now
            logged = true
            pending = 0
        }
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val PACING_TAG = "VideoPacing"
        private const val FEED_WAIT_US = 20_000L
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50

        // queue bounds: past the soft ones the head is fed early (never dropped: that would
        // corrupt the picture until the next keyframe); past the hard ones new frames drop
        private const val SOFT_MAX_FRAMES = 120
        private const val MAX_QUEUE_SPAN_NS = FramePacer.MAX_HOLD_NS
        private const val HARD_MAX_FRAMES = 360
        private const val HARD_MAX_BYTES = 32L * 1024 * 1024

        private const val OUTPUT_WAIT_NS = 12 * FramePacer.MS
        private const val OUTPUT_POLL_US = 2_000L
        private const val IDLE_DRAIN_POLL_NS = 4 * FramePacer.MS
        private const val IDLE_DRAIN_WINDOW_NS = 250 * FramePacer.MS
        private const val FED_LATE_TOLERANCE_NS = 2 * FramePacer.MS

        private const val AUDIO_POLL_NS = 250 * FramePacer.MS
        private const val CLOCK_REFRESH_NS = 2_000 * FramePacer.MS
        private const val REPORT_INTERVAL_NS = 5_000 * FramePacer.MS
        private const val JOIN_TIMEOUT_MS = 3_000L

        // scheduler errors tolerated per window before falling back to immediate decode
        private const val MAX_ERRORS_PER_WINDOW = 5
        private const val ERROR_WINDOW_NS = 10_000 * FramePacer.MS
        private const val DROP_LOG_INTERVAL_NS = 1_000 * FramePacer.MS
        // parameter sets + IDR slice header sit at the start of a keyframe access unit
        private const val KEYFRAME_SCAN_BYTES = 1024
    }
}

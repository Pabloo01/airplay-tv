package io.github.jqssun.airplay.renderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

// decodes into an app-owned SurfaceTexture that outlives display surface
// gl thread blits it to any surface attached, so fullscreen toggles re-point display without restarting codec
// display surface fills the screen; the frame (or its black-bar crop) is aspect-fit with glViewport
// frame-available callbacks arrive on a dedicated handler thread (never the main looper) and are
// counted, so every queued decoder buffer is latched and the newest one shown
class VideoPipeline {

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var egl: EglCore? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uCrop = 0
    private val texMatrix = FloatArray(16)
    private val viewport = IntArray(4)
    private var hasFrame = false

    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null; private set
    private var frameThread: HandlerThread? = null

    // decoder buffers queued to the SurfaceTexture and not latched yet, lock
    private var pendingFrames = 0
    private var pendingDisplay: Surface? = null
    private var displayDirty = false
    private var redrawPending = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    // crop black bars baked into the stream; off shows the whole frame aspect-fit
    @Volatile var autoCrop = true
        set(v) {
            if (field == v) return
            field = v
            resetCrop()
        }
    // keep a safe margin around a cropped picture, since the crop also drops the sender's overscan border
    @Volatile var overscan = false
        set(v) {
            if (field == v) return
            field = v
            _requestRedraw()
        }

    // crop state below is gl thread only; other threads just request a reset
    private val cropReset = AtomicBoolean(false)
    private val detector = BlackBarDetector()
    private val stabilizer = CropStabilizer()
    private var crop = CropRect.FULL
    // what is on screen: eases from animFrom to crop over CROP_ANIM_MS after a detected change
    private var animFrom = CropRect.FULL
    private var animStartMs = 0L
    private var animating = false
    private var lastRenderMs = 0L
    private var lastDetectMs = 0L
    private var probeFbo = 0
    private var probeTex = 0
    private var probeW = 0
    private var probeH = 0
    private var probeFailed = false
    private var probePixels: ByteBuffer? = null
    private var probeRgba = ByteArray(0)
    private var probeLuma = ByteArray(0)

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        thread = Thread({ _loop() }, "VideoPipeline").also { it.start() }
        while (inputSurface == null && running) lock.wait()
    }

    fun setDisplaySurface(surface: Surface?) = synchronized(lock) {
        pendingDisplay = surface
        displayDirty = true
        lock.notifyAll()
    }

    fun setVideoSize(w: Int, h: Int) {
        val changed = w != videoW || h != videoH
        videoW = w
        videoH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
        // no repaint: last frame is still the old size, the new stream's first frame applies it
        if (changed) cropReset.set(true)
    }

    // back to full frame and re-detect (new session, rotation)
    fun resetCrop() {
        cropReset.set(true)
        _requestRedraw()
    }

    fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        thread?.join()
        thread = null
    }

    private fun _requestRedraw() = synchronized(lock) {
        redrawPending = true
        lock.notifyAll()
    }

    private fun _loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Exception) {
            Log.w(TAG, "can't raise gl thread priority", e)
        }
        try {
            egl = EglCore()
            _initGl()
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
            frameThread?.quitSafely()
            frameThread = null
            synchronized(lock) { running = false; lock.notifyAll() }
            return
        }
        Matrix.setIdentityM(texMatrix, 0)
        synchronized(lock) {
            inputSurface = Surface(surfaceTexture)
            lock.notifyAll()
        }
        while (true) {
            var newDisplay: Surface? = null
            var displayChanged = false
            var frames = 0
            var redraw = false
            synchronized(lock) {
                while (running && pendingFrames == 0 && !displayDirty && !redrawPending) {
                    if (!animating) {
                        lock.wait()
                        continue
                    }
                    // crop easing on a still picture: repaint at ~display rate until it settles
                    val waitMs = ANIM_FRAME_MS - (SystemClock.uptimeMillis() - lastRenderMs)
                    if (waitMs <= 0) {
                        redrawPending = true
                        break
                    }
                    lock.wait(waitMs)
                }
                if (running && displayDirty) {
                    newDisplay = pendingDisplay
                    displayChanged = true
                    displayDirty = false
                }
                if (running && pendingFrames > 0) {
                    frames = pendingFrames
                    pendingFrames = 0
                }
                if (running && redrawPending) {
                    redrawPending = false
                    redraw = true
                }
            }
            if (!running) break
            if (displayChanged) _bindDisplay(newDisplay)
            if (frames > 0) _consumeAndDraw(frames)
            else if (redraw && !displayChanged) _redraw()
        }
        _releaseGl()
    }

    private fun _bindDisplay(surface: Surface?) {
        val egl = egl ?: return
        if (window != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent()
            egl.destroySurface(window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface == null || !surface.isValid) return
        window = egl.windowSurface(surface)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return
        }
        egl.makeCurrent(window)
        winW = egl.query(window, EGL14.EGL_WIDTH)
        winH = egl.query(window, EGL14.EGL_HEIGHT)
        // an idle source sends no new frames, so repaint last one or new surface stays black
        if (hasFrame) _render()
    }

    // frames: buffers signalled since the last wakeup; each updateTexImage latches the oldest
    // queued one, so latch them all (freeing the decoder's buffers) and draw only the newest
    private fun _consumeAndDraw(frames: Int) {
        val st = surfaceTexture ?: return
        val egl = egl ?: return
        val shown = window != EGL14.EGL_NO_SURFACE
        // no display: keep consuming so decoder doesn't stall
        if (shown) egl.makeCurrent(window) else egl.makeCurrent()
        repeat(frames) { st.updateTexImage() }
        st.getTransformMatrix(texMatrix)
        hasFrame = true
        if (shown) _render()
        // after swap so the shown frame never waits on the readback
        _detectBars()
    }

    // repaint last frame after a crop/setting change; idle source may send nothing new
    private fun _redraw() {
        val egl = egl ?: return
        if (window == EGL14.EGL_NO_SURFACE || !hasFrame) {
            // nothing on screen to ease: the next bind / frame shows the final crop
            animating = false
            return
        }
        egl.makeCurrent(window)
        _render()
    }

    private fun _render() {
        _syncCrop()
        val now = SystemClock.uptimeMillis()
        lastRenderMs = now
        val target = if (autoCrop) crop else CropRect.FULL
        // eased crop change: aspect, viewport and margin move together over CROP_ANIM_MS
        var t = 1f
        if (animating && autoCrop) {
            val raw = (now - animStartMs).toFloat() / CROP_ANIM_MS
            if (raw >= 1f) animating = false else t = easeInOut(raw)
        } else {
            animating = false
        }
        val c = if (t >= 1f) target else animFrom.lerp(target, t)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val vw = videoW
        val vh = videoH
        val aspect = if (vw > 0 && vh > 0) c.aspect(vw, vh) else 0f
        fun marginOf(r: CropRect) = if (overscan && !r.isFull) OVERSCAN_MARGIN else 0f
        val margin = if (t >= 1f) marginOf(target) else marginOf(animFrom) + (marginOf(target) - marginOf(animFrom)) * t
        fitViewport(aspect, winW, winH, margin, viewport)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        _drawQuad(c)
        egl?.swap(window)
    }

    // crop currently on screen (mid-transition included), gl thread
    private fun _shownCrop(): CropRect {
        if (!animating) return crop
        val raw = (SystemClock.uptimeMillis() - animStartMs).toFloat() / CROP_ANIM_MS
        return if (raw >= 1f) crop else animFrom.lerp(crop, easeInOut(raw))
    }

    private fun _drawQuad(c: CropRect) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        // crop in pre-transform texture space, where t runs bottom to top
        GLES20.glUniform4f(uCrop, c.width, c.height, c.left, 1f - c.bottom)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun _syncCrop() {
        if (!cropReset.get() || !cropReset.getAndSet(false)) return
        stabilizer.reset()
        lastDetectMs = 0L
        animating = false
        _setCrop(CropRect.FULL, "reset")
    }

    // every DETECT_INTERVAL_MS: draw whole frame into a tiny fbo, read it back, look for bars
    private fun _detectBars() {
        _syncCrop()
        if (!autoCrop || probeFailed) return
        val now = SystemClock.uptimeMillis()
        if (lastDetectMs != 0L && now - lastDetectMs < DETECT_INTERVAL_MS) return
        lastDetectMs = now
        val vw = videoW
        val vh = videoH
        if (vw <= 0 || vh <= 0 || !_ensureProbe(vw, vh)) return
        val pixels = probePixels ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo)
        GLES20.glViewport(0, 0, probeW, probeH)
        // tilers skip reloading old contents after a clear
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        _drawQuad(CropRect.FULL)
        pixels.position(0)
        GLES20.glReadPixels(0, 0, probeW, probeH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        pixels.position(0)
        pixels.get(probeRgba)
        rgbaToLuma(probeRgba, probeW, probeH, probeLuma)
        val detection = detector.detect(probeLuma, probeW, probeH, vw, vh)
        // how much of the bar area lights up: a small HUD only reveals if it stays up
        val share = if (detection != null && detection.rect.reveals(stabilizer.current, CropStabilizer.TOLERANCE)) {
            statsOutside(probeLuma, probeW, probeH, stabilizer.current).litShare
        } else 1f
        if (stabilizer.offer(detection, now, share)) {
            val next = stabilizer.current
            if (!next.isFull) _logBarLuma(next)
            animFrom = _shownCrop()
            animStartMs = SystemClock.uptimeMillis()
            animating = true
            _setCrop(next, "detected")
            _requestRedraw()
        }
    }

    // colour-range check: blacks should read ~0 after yuv->rgb; ~16 means limited-range video
    // shown unexpanded (washed-out blacks)
    private fun _logBarLuma(c: CropRect) {
        val st = statsOutside(probeLuma, probeW, probeH, c)
        if (st.pixels == 0) return
        Log.i(TAG, String.format(Locale.US,
            "bar luma: mean %.1f max %d over %d probe px (%s)", st.meanLuma, st.maxLuma, st.pixels,
            when {
                st.meanLuma < 6f -> "full-range black, ok"
                st.meanLuma in 12f..22f -> "~16: limited range not expanded, blacks washed out"
                else -> "bars not pure black"
            }))
    }

    // probe keeps stream aspect with long side PROBE_SIZE; reallocated only when stream size changes
    private fun _ensureProbe(vw: Int, vh: Int): Boolean {
        val pw: Int
        val ph: Int
        if (vw >= vh) {
            pw = PROBE_SIZE
            ph = (PROBE_SIZE.toFloat() * vh / vw).roundToInt().coerceIn(PROBE_MIN, PROBE_SIZE)
        } else {
            ph = PROBE_SIZE
            pw = (PROBE_SIZE.toFloat() * vw / vh).roundToInt().coerceIn(PROBE_MIN, PROBE_SIZE)
        }
        if (probeFbo != 0 && pw == probeW && ph == probeH) return true
        val ids = IntArray(1)
        if (probeTex == 0) {
            GLES20.glGenTextures(1, ids, 0)
            probeTex = ids[0]
        }
        if (probeFbo == 0) {
            GLES20.glGenFramebuffers(1, ids, 0)
            probeFbo = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, probeTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, pw, ph, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, probeTex, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "crop probe framebuffer incomplete ($status), black bar crop disabled")
            _releaseProbe()
            probeFailed = true
            return false
        }
        probeW = pw
        probeH = ph
        probePixels = ByteBuffer.allocateDirect(pw * ph * 4).order(ByteOrder.nativeOrder())
        probeRgba = ByteArray(pw * ph * 4)
        probeLuma = ByteArray(pw * ph)
        return true
    }

    private fun _releaseProbe() {
        val ids = IntArray(1)
        if (probeFbo != 0) {
            ids[0] = probeFbo
            GLES20.glDeleteFramebuffers(1, ids, 0)
        }
        if (probeTex != 0) {
            ids[0] = probeTex
            GLES20.glDeleteTextures(1, ids, 0)
        }
        probeFbo = 0
        probeTex = 0
        probeW = 0
        probeH = 0
        probePixels = null
        probeRgba = ByteArray(0)
        probeLuma = ByteArray(0)
    }

    private fun _setCrop(next: CropRect, why: String) {
        val prev = crop
        crop = next
        if (prev == next) return
        val vw = videoW
        val vh = videoH
        Log.i(TAG, String.format(
            Locale.US, "crop %s -> %s aspect %.3f (%dx%d of %dx%d, %s)",
            prev, next, if (vw > 0 && vh > 0) next.aspect(vw, vh) else 0f,
            (next.width * vw).roundToInt(), (next.height * vh).roundToInt(), vw, vh, why,
        ))
    }

    private fun _initGl() {
        program = _buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")
        // opaque black around the fitted picture
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // own looper for frame callbacks: without a handler they hop through the main looper,
        // where ui work delays every frame
        val ft = HandlerThread("VideoFrames", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        frameThread = ft
        surfaceTexture = SurfaceTexture(oesTex).also {
            if (videoW > 0 && videoH > 0) it.setDefaultBufferSize(videoW, videoH)
            it.setOnFrameAvailableListener({
                synchronized(lock) { pendingFrames++; lock.notifyAll() }
            }, Handler(ft.looper))
        }
    }

    private fun _buildProgram(): Int {
        val vs = _shader(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = _shader(GLES20.GL_FRAGMENT_SHADER, FRAG)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun _shader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(it)}")
        }
    }

    private fun _releaseGl() {
        egl?.let {
            it.makeCurrent()
            _releaseProbe()
        }
        probeFailed = false
        stabilizer.reset()
        crop = CropRect.FULL
        animating = false
        lastDetectMs = 0L
        surfaceTexture?.release()
        surfaceTexture = null
        frameThread?.quitSafely()
        frameThread = null
        synchronized(lock) { pendingFrames = 0 }
        inputSurface?.release()
        inputSurface = null
        egl?.let {
            if (window != EGL14.EGL_NO_SURFACE) it.destroySurface(window)
            it.close()
        }
        egl = null
        window = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val TAG = "VideoPipeline"
        // probe cadence and size: one tiny draw + readback, never per frame
        private const val DETECT_INTERVAL_MS = 300L
        // crop changes ease in over this long instead of cutting
        private const val CROP_ANIM_MS = 300L
        private const val ANIM_FRAME_MS = 16L
        private const val PROBE_SIZE = 256
        private const val PROBE_MIN = 16
        // per side; EBU/SMPTE title-safe area
        private const val OVERSCAN_MARGIN = 0.05f

        private val POS = _fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEX = _fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun _fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }

        // uCrop = (scale.st, offset.st) picks the crop out of the frame before the SurfaceTexture transform
        private const val VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform vec4 uCrop;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex * uCrop.xy + uCrop.zw, 0.0, 1.0)).xy;\n" +
            "}\n"

        // highp texture coordinates where available: 16-bit varyings (mali) quantize sampling
        // positions across a 1920-wide frame
        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "varying highp vec2 vTex;\n" +
            "#else\n" +
            "varying mediump vec2 vTex;\n" +
            "#endif\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n"
    }
}

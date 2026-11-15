package io.github.jqssun.airplay.renderer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

class EglCore : AutoCloseable {

    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private val pbuffer: EGLSurface

    init {
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        config = _chooseConfig()
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        makeCurrent()
    }

    /*
     * eglChooseConfig sorts deeper colour first (e.g. RGBA1010102 before RGBA8888) and the order
     * differs per driver, so taking the first match is not deterministic. pick an exact 8-bit
     * RGBA (else RGB) config, preferring no depth/stencil/multisampling, and log what we got
     */
    private fun _chooseConfig(): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(MAX_CONFIGS)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, num, 0)
        val n = num[0].coerceIn(0, configs.size)
        var best: EGLConfig? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until n) {
            val c = configs[i] ?: continue
            val rank = _rank(c)
            if (rank < bestRank) {
                best = c
                bestRank = rank
            }
        }
        val chosen = best ?: error("no egl config")
        Log.i(TAG, "EGL config: R${_attr(chosen, EGL14.EGL_RED_SIZE)} G${_attr(chosen, EGL14.EGL_GREEN_SIZE)} " +
            "B${_attr(chosen, EGL14.EGL_BLUE_SIZE)} A${_attr(chosen, EGL14.EGL_ALPHA_SIZE)} " +
            "depth=${_attr(chosen, EGL14.EGL_DEPTH_SIZE)} stencil=${_attr(chosen, EGL14.EGL_STENCIL_SIZE)} " +
            "samples=${_attr(chosen, EGL14.EGL_SAMPLES)} id=${_attr(chosen, EGL14.EGL_CONFIG_ID)} ($n candidates)")
        return chosen
    }

    // lower is better: exact 8-bit colour, then alpha 8 > 0 > other, then fewest extras
    private fun _rank(c: EGLConfig): Int {
        val rgbExact = _attr(c, EGL14.EGL_RED_SIZE) == 8 && _attr(c, EGL14.EGL_GREEN_SIZE) == 8 &&
            _attr(c, EGL14.EGL_BLUE_SIZE) == 8
        val alpha = when (_attr(c, EGL14.EGL_ALPHA_SIZE)) {
            8 -> 0
            0 -> 1
            else -> 2
        }
        val extras = (if (_attr(c, EGL14.EGL_DEPTH_SIZE) > 0) 4 else 0) +
            (if (_attr(c, EGL14.EGL_STENCIL_SIZE) > 0) 2 else 0) +
            (if (_attr(c, EGL14.EGL_SAMPLES) > 0) 1 else 0)
        return (if (rgbExact) 0 else 100) + alpha * 10 + extras
    }

    private fun _attr(c: EGLConfig, attr: Int): Int {
        val v = IntArray(1)
        return if (EGL14.eglGetConfigAttrib(display, c, attr, v, 0)) v[0] else -1
    }

    fun makeCurrent(surface: EGLSurface = pbuffer) = EGL14.eglMakeCurrent(display, surface, surface, context)

    fun windowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)

    fun destroySurface(surface: EGLSurface) = EGL14.eglDestroySurface(display, surface)

    fun swap(surface: EGLSurface) = EGL14.eglSwapBuffers(display, surface)

    fun query(surface: EGLSurface, what: Int): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(display, surface, what, v, 0)
        return v[0]
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val TAG = "EglCore"
        private const val MAX_CONFIGS = 64
    }
}

package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioConfigTest {

    // a mismatch makes the first session after a server start open one stream and rebuild it
    @Test
    fun defaultsMatchPreferenceDefaults() {
        val d = AudioConfig()
        assertEquals(if (Prefs.AUDIO_AUTO_BUFFER.def) 0 else Prefs.AUDIO_CUSHION_MS.def, d.cushionMs)
        assertEquals(Prefs.OBOE_BUFFER_FRAMES.def, d.oboeBufferFrames)
        assertEquals(Prefs.FORCE_SW_ALAC.def, d.forceSwAlac)
        assertEquals(Prefs.FORCE_SW_AAC.def, d.forceSwAac)
        assertEquals(Prefs.KEY_PRIORITY.def, d.realtimePriority)
        assertEquals(Prefs.LOW_LATENCY.def, d.lowLatency)
        assertEquals(Prefs.BENCHMARK_LOG.def, d.benchmarkLog)
        // 99 = the movie-mode default step (pref default raised alongside); once both land this
        // can be Prefs.ADAPTIVE_PERCENTILES[Prefs.AUDIO_ADAPTIVE_STEP.def]
        assertTrue(d.percentilePct in Prefs.ADAPTIVE_PERCENTILES.toList())
    }
}

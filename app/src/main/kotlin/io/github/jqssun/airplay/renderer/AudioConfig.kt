package io.github.jqssun.airplay.renderer

/**
 * mirrors native AudioConfig struct; defaults must match preference defaults (Prefs:
 * audio_auto_buffer, audio_adaptive_step, oboe_buffer_frames, force_sw_alac, force_sw_aac,
 * KEY_PRIORITY, low_latency, benchmark_log) so a freshly-constructed AudioRenderer opens the
 * same stream the prefs will ask for, instead of opening one and rebuilding it
 */
data class AudioConfig(
    val cushionMs: Int = 0,           // 0 = adaptive tuner, otherwise fixed cushion ms
    val percentilePct: Int = 99,      // Prefs.ADAPTIVE_PERCENTILES[default audio_adaptive_step]
    val oboeBufferFrames: Int = 0,    // 0 = auto (two bursts)
    val forceSwAlac: Boolean = true,
    val forceSwAac: Boolean = false,
    val realtimePriority: Boolean = true,
    val lowLatency: Boolean = false,
    val benchmarkLog: Boolean = false,
)

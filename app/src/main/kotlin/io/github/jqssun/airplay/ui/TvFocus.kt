package io.github.jqssun.airplay.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

// focus can only land once the target is composed; retry across frames
suspend fun FocusRequester.requestFocusUntilLanded(): Boolean {
    repeat(FOCUS_ATTEMPTS) {
        if (runCatching { requestFocus() }.isSuccess) return true
        withFrameNanos { }
    }
    return false
}

private const val FOCUS_ATTEMPTS = 10

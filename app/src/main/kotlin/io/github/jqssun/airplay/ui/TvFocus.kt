package io.github.jqssun.airplay.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester

// focus can only land once the target is composed: a target inside a Stage or SafeArea is
// composed during layout, after the effect that asks for it, and a request made before then is
// silently dropped (it no longer throws). Retry across frames until focus really lands.
suspend fun FocusRequester.requestFocusUntilLanded(): Boolean {
    repeat(FOCUS_ATTEMPTS) {
        if (runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return true
        withFrameNanos { }
    }
    return false
}

private const val FOCUS_ATTEMPTS = 10

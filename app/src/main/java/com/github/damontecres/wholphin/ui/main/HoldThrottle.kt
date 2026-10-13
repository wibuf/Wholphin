package com.github.damontecres.wholphin.ui.main

import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

/**
 * Fork-only: paces up/down while the button is held on the home page (#2034 upstream).
 *
 * A held D-pad repeats about every 50 ms. Moving focus a whole row per repeat outruns the list:
 * the next rows aren't laid out yet and the focused row can be scrolled away and disposed, leaving
 * focus on nothing, so up/down stop working until left or right puts it back in a row. Letting a
 * held move through only every [intervalMs] keeps focus and scrolling together. A single press
 * always goes straight through.
 */
class HoldThrottle(
    private val intervalMs: Long = HOLD_ROW_INTERVAL_MS,
) {
    private var lastMoveAt = Long.MIN_VALUE / 2

    /** Whether a key down with [repeatCount] at [uptimeMs] should move focus */
    fun allow(
        repeatCount: Int,
        uptimeMs: Long,
    ): Boolean {
        if (repeatCount > 0 && uptimeMs - lastMoveAt < intervalMs) return false
        lastMoveAt = uptimeMs
        return true
    }

    /** True if [event] is a held up/down repeat that should be dropped */
    fun consumes(
        event: KeyEvent,
        log: ((String) -> Unit)? = null,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val native = event.nativeKeyEvent
        if (native.keyCode != KEYCODE_DPAD_UP && native.keyCode != KEYCODE_DPAD_DOWN) return false
        val allowed = allow(native.repeatCount, native.eventTime)
        log?.invoke(
            "key ${if (native.keyCode == KEYCODE_DPAD_DOWN) "DOWN" else "UP"} repeat=${native.repeatCount} " +
                if (allowed) "moved" else "dropped",
        )
        return !allowed
    }

    companion object {
        /**
         * The fastest the Shield held without focus getting stuck: every repeat (no limit) got
         * stuck, 75 ms didn't. Was 150 ms, which held too but felt slow next to left/right.
         */
        const val HOLD_ROW_INTERVAL_MS = 75L


    }
}

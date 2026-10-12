package com.github.damontecres.wholphin.ui.main

import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

/**
 * Fork-only: paces up/down while the button is held on the home page (#2034 upstream).
 *
 * A held D-pad repeats about every 50 ms. Moving focus a whole row per repeat could outrun the
 * list: the next move was sent before the last one had landed, focus could end up on a row that
 * had been scrolled away and disposed, and up/down stopped working until left or right put it
 * back in a row. So a held move only goes through once the previous one has landed on its row
 * (and at least [minIntervalMs] after it), which is as fast as the list can follow. If focus
 * hasn't moved after [maxWaitMs] (the last row, or the pills), the next repeat goes anyway. A
 * single press always goes straight through.
 */
class HoldThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {
    private var lastMoveAt = Long.MIN_VALUE / 2
    private var rowAtLastMove: Int? = null

    /**
     * Whether a key down with [repeatCount] at [uptimeMs] should move focus, with focus now on
     * [currentRow]
     */
    fun allow(
        repeatCount: Int,
        uptimeMs: Long,
        currentRow: Int,
    ): Boolean {
        if (repeatCount > 0) {
            val waited = uptimeMs - lastMoveAt
            val landed = currentRow != rowAtLastMove
            if (waited < maxWaitMs && !(landed && waited >= minIntervalMs)) return false
        }
        lastMoveAt = uptimeMs
        rowAtLastMove = currentRow
        return true
    }

    /** True if [event] is a held up/down repeat that should be dropped */
    fun consumes(
        event: KeyEvent,
        currentRow: Int,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val native = event.nativeKeyEvent
        if (native.keyCode != KEYCODE_DPAD_UP && native.keyCode != KEYCODE_DPAD_DOWN) return false
        return !allow(native.repeatCount, native.eventTime, currentRow)
    }

    companion object {
        /** Just under the remote's repeat rate, so a list that keeps up moves on every repeat */
        const val MIN_INTERVAL_MS = 40L
        const val MAX_WAIT_MS = 250L
    }
}

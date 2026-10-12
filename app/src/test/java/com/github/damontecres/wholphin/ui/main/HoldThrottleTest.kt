package com.github.damontecres.wholphin.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldThrottleTest {
    @Test
    fun singlePressesAlwaysMove() {
        val t = HoldThrottle()
        assertTrue(t.allow(0, 1_000, 0))
        assertTrue(t.allow(0, 1_010, 0))
        assertTrue(t.allow(0, 1_020, 0))
    }

    /** Holds down for [repeats] repeats every 50 ms; focus lands [landAfterMs] after each move */
    private fun hold(
        repeats: Int,
        landAfterMs: Long,
    ): Int {
        val t = HoldThrottle()
        var row = 0
        var pendingLandAt: Long? = null
        var moves = 0
        val presses = listOf(0 to 0L) + (1..repeats).map { it to 400L + (it - 1) * 50 }
        for ((repeat, at) in presses) {
            pendingLandAt?.let { if (at >= it) row++.also { pendingLandAt = null } }
            if (t.allow(repeat, at, row)) {
                moves++
                pendingLandAt = at + landAfterMs
            }
        }
        return moves
    }

    @Test
    fun aListThatKeepsUpMovesOnEveryRepeat() {
        assertEquals(1 + 32, hold(32, landAfterMs = 20))
    }

    @Test
    fun aSlowListIsWaitedFor() {
        // Each row takes 120 ms to land, so every third repeat (150 ms) goes through
        assertEquals(1 + 11, hold(32, landAfterMs = 120))
    }

    @Test
    fun focusThatNeverMovesStillGetsARepeatEvery250ms() {
        val t = HoldThrottle()
        assertTrue(t.allow(0, 0, 5))
        assertFalse(t.allow(1, 100, 5))
        assertFalse(t.allow(2, 200, 5))
        assertTrue(t.allow(3, 260, 5))
    }
}

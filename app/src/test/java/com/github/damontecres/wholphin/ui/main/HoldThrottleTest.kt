package com.github.damontecres.wholphin.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldThrottleTest {
    @Test
    fun singlePressesAlwaysMove() {
        val t = HoldThrottle(150)
        assertTrue(t.allow(0, 1_000))
        assertTrue(t.allow(0, 1_010))
        assertTrue(t.allow(0, 1_020))
    }

    @Test
    fun aHeldButtonMovesAtASteadyPace() {
        val t = HoldThrottle(150)
        // Press at 0, Android starts repeating at 400 ms, then every 50 ms for 1.6 s
        val moves = (listOf(0 to 0L) + (1..32).map { it to 400L + (it - 1) * 50 })
            .count { (repeat, at) -> t.allow(repeat, at) }
        // 1 press + repeats from 400 to 1950 ms: one per 150 ms, not 32
        assertEquals(12, moves)
    }
}

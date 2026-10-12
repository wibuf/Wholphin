package com.github.damontecres.wholphin.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeRowNavTest {
    // Rows 1 and 3 are still loading or empty
    private val rows = listOf(true, false, true, false, true)

    @Test
    fun downSkipsRowsWithNothingToFocus() {
        assertEquals(2, HomeRowNav.next(rows, 0, 1))
        assertEquals(4, HomeRowNav.next(rows, 2, 1))
    }

    @Test
    fun upSkipsThemToo() {
        assertEquals(2, HomeRowNav.next(rows, 4, -1))
        assertEquals(0, HomeRowNav.next(rows, 2, -1))
    }

    @Test
    fun theEndsAreLeftToTheDefault() {
        assertNull(HomeRowNav.next(rows, 4, 1))
        // Up from the first row goes to the pills the default way
        assertNull(HomeRowNav.next(rows, 0, -1))
    }

    @Test
    fun aLostFocusRecoversFromTheLastRow() {
        // Wherever focus ended up, the move starts from the row last focused
        assertEquals(4, HomeRowNav.next(rows, 3, 1))
    }
}

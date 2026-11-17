package com.github.damontecres.wholphin.ui.main

import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFirstLoadTest {
    private val title = com.github.damontecres.wholphin.ui.util.EmptyStringProvider
    private val pending = HomeRowLoadingState.Pending(title)
    private val loaded = HomeRowLoadingState.Success(title, emptyList())

    @Test
    fun waitsForTheTopRow() {
        assertFalse(HomeFirstLoad.showPage(LoadingState.Loading, listOf(pending, loaded, loaded)))
    }

    @Test
    fun showsOnceTheTopRowIsDoneEvenIfOthersAreNot() {
        assertTrue(HomeFirstLoad.showPage(LoadingState.Loading, listOf(loaded, pending, pending)))
    }

    @Test
    fun aFailedTopRowStillShowsThePage() {
        val failed = HomeRowLoadingState.Error(title, exception = RuntimeException("x"))
        assertTrue(HomeFirstLoad.showPage(LoadingState.Loading, listOf(failed, pending)))
    }

    @Test
    fun aShownPageStaysShown() {
        assertTrue(HomeFirstLoad.showPage(LoadingState.Success, listOf(pending)))
    }

    @Test
    fun `the spinner goes once the top three rows are in, whatever is still loading below`() {
        assertFalse(HomeFirstLoad.spinnerDone(setOf(0, 1), 20))
        assertFalse(HomeFirstLoad.spinnerDone(setOf(0, 2, 5, 6), 20))
        assertTrue(HomeFirstLoad.spinnerDone(setOf(2, 0, 1), 20))
        assertTrue(HomeFirstLoad.spinnerDone(setOf(0, 1), 2))
        assertTrue(HomeFirstLoad.spinnerDone(emptySet(), 0))
    }
}

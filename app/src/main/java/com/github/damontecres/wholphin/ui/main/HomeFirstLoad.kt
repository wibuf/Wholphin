package com.github.damontecres.wholphin.ui.main

import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState

/**
 * Fork-only: when the home page appears on first load.
 *
 * Upstream waits for every row before showing the page, so with many libraries the spinner stays
 * up until the slowest row answers. GooseFlix shows the page once the top row has finished
 * (loaded, empty or failed); the rows below show as loading and fill in as they finish. Rows are
 * fetched in order from the top, so the ones on screen first tend to arrive first.
 */
object HomeFirstLoad {
    fun showPage(
        current: LoadingState,
        rows: List<HomeRowLoadingState>,
    ): Boolean {
        if (current == LoadingState.Success) return true
        val top = rows.firstOrNull() ?: return false
        return top !is HomeRowLoadingState.Pending && top !is HomeRowLoadingState.Loading
    }

    /** Rows at the top of the page that the corner spinner waits for */
    const val SPINNER_ROWS = 3

    /** Rows fetched at once; the personal rows are built ahead on the server, so six is light */
    const val CONCURRENT_ROWS = 6

    /**
     * Whether the loading spinner in the corner can go: the rows at the top of the page (in
     * practice what's on screen) have finished in this load, whatever happens further down. Rows
     * still loading below show their own placeholder if the user scrolls to them first.
     */
    fun spinnerDone(
        done: Set<Int>,
        rowCount: Int,
        top: Int = SPINNER_ROWS,
    ): Boolean = (0 until minOf(top, rowCount)).all { it in done }
}

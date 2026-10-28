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
}

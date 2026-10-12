package com.github.damontecres.wholphin.ui.main

import com.github.damontecres.wholphin.util.HomeRowLoadingState

/**
 * Fork-only: up/down on the home page, moved by hand (#2034 upstream).
 *
 * Holding up or down repeats about every 50 ms, and leaving each move to Android's focus search
 * could outrun the list: the next row wasn't laid out yet or the focused one was scrolled away,
 * focus ended up on nothing, and up/down stopped working until left or right put it back. Pacing
 * the repeats only made that rarer. Instead the home page picks the next row with something in it
 * itself, scrolls it into place if it isn't laid out, and focuses it, dropping repeats while a move
 * is still landing. Focus can't be lost to a race, and a lost focus is put back by the next press,
 * since the move starts from the last row focused rather than from wherever focus ended up.
 */
object HomeRowNav {
    /** Rows that can take focus: those showing items or games */
    fun focusable(rows: List<HomeRowLoadingState>): List<Boolean> =
        rows.map {
            when (it) {
                is HomeRowLoadingState.Success -> it.items.isNotEmpty()
                is HomeRowLoadingState.Games -> it.games.isNotEmpty()
                else -> false
            }
        }

    /**
     * The next focusable row from [from] in direction [step] (+1 down, -1 up), or null at either
     * end, where the default handling takes over (up from the first row goes to the pills).
     */
    fun next(
        focusable: List<Boolean>,
        from: Int,
        step: Int,
    ): Int? {
        var i = from + step
        while (i in focusable.indices) {
            if (focusable[i]) return i
            i += step
        }
        return null
    }
}

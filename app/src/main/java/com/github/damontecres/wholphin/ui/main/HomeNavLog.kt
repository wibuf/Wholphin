package com.github.damontecres.wholphin.ui.main

import android.os.SystemClock
import timber.log.Timber

/**
 * Fork-only: a log of moving around the home page, for the stuck-focus bug (#2034 upstream).
 *
 * Each up/down key and whether it moved, every row and card that gains or loses focus, and where
 * the list is scrolled, all under the logcat tag [TAG] with milliseconds since the first line. On
 * when "Home hold speed" is set faster than Steady, or verbose logging is on. Capture with
 * `adb logcat -s GooseFlixNav`.
 */
class HomeNavLog(
    val enabled: Boolean,
) {
    private val startedAt = SystemClock.elapsedRealtime()

    fun log(message: String) {
        if (enabled) {
            Timber.tag(TAG).i("+%dms %s", SystemClock.elapsedRealtime() - startedAt, message)
        }
    }

    companion object {
        const val TAG = "GooseFlixNav"
    }
}

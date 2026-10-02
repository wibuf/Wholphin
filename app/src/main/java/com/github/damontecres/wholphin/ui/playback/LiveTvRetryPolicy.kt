package com.github.damontecres.wholphin.ui.playback

import kotlin.time.Duration

/**
 * What to do about a live TV stream that failed
 */
sealed interface LiveTvRecovery {
    /**
     * Restart straight away on a server transcode
     *
     * The stream failed before it ever played while playing directly. That is almost always a
     * format or URL problem rather than a dropout, so asking for the same thing again would only
     * fail the same way, more slowly. This is also what happens for anything that is not live TV.
     */
    data object FallBackToTranscode : LiveTvRecovery

    /** Reconnect the same way after [delay], as reconnect number [attempt] */
    data class Reconnect(
        val attempt: Int,
        val delay: Duration,
    ) : LiveTvRecovery

    /** Out of reconnects, so show the error */
    data object GiveUp : LiveTvRecovery
}

/**
 * Decides how to recover a failed live TV stream
 *
 * Live streams come from a tuner and drop out for reasons that usually clear on their own, so a
 * stream that was playing is worth reconnecting a few times before the error is shown. A stream
 * that never started is a different problem and gets [LiveTvRecovery.FallBackToTranscode] instead.
 * This is kept out of the playback view model so the decision can be tested without a player or a
 * real clock.
 */
class LiveTvRetryPolicy(
    private val maxRetries: Int = LIVE_TV_MAX_RETRIES,
    private val resetAfterMs: Long = LIVE_TV_RETRY_RESET_AFTER.inWholeMilliseconds,
) {
    private var attempt = 0
    private var lastFailureMs: Long? = null

    /**
     * Decide what to do about a failure
     *
     * Falling back to a transcode does not use up a reconnect, so the transcoded stream still has
     * its full set if it later drops.
     *
     * @param nowMs monotonic time of the failure, eg [android.os.SystemClock.elapsedRealtime]
     * @param started whether the stream got as far as playing before it failed
     * @param transcoding whether the server was already transcoding, or the method is unknown
     */
    fun decide(
        nowMs: Long,
        started: Boolean,
        transcoding: Boolean,
    ): LiveTvRecovery {
        if (!started && !transcoding) {
            return LiveTvRecovery.FallBackToTranscode
        }
        val attempt = onFailure(nowMs) ?: return LiveTvRecovery.GiveUp
        return LiveTvRecovery.Reconnect(attempt, delayFor(attempt))
    }

    /**
     * Record that the stream failed
     *
     * @param nowMs monotonic time of the failure, eg [android.os.SystemClock.elapsedRealtime]
     * @return the attempt to make, counting from 1, or null if the stream has failed too often
     */
    fun onFailure(nowMs: Long): Int? {
        // A stream that ran happily for a while before failing gets a fresh set of retries, so a
        // single hiccup hours into a channel is not charged against a failure from much earlier
        val last = lastFailureMs
        if (last == null || nowMs - last > resetAfterMs) {
            attempt = 0
        }
        lastFailureMs = nowMs
        if (attempt >= maxRetries) {
            return null
        }
        return ++attempt
    }

    /** Forget previous failures, eg because a different channel was selected */
    fun reset() {
        attempt = 0
        lastFailureMs = null
    }

    companion object {
        /** How long to wait before reconnect number [attempt]; the first one is immediate */
        fun delayFor(attempt: Int): Duration = LIVE_TV_RETRY_DELAY * (attempt - 1).coerceAtLeast(0)
    }
}

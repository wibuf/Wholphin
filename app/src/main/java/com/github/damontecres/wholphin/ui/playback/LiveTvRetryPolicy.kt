package com.github.damontecres.wholphin.ui.playback

/**
 * Decides whether a failed live TV stream should be restarted
 *
 * Live streams come from a tuner and drop out for reasons that usually clear on their own, so a
 * failure is worth retrying a few times before the error is shown. This is kept out of the
 * playback view model so the decision can be tested without a player or a real clock.
 */
class LiveTvRetryPolicy(
    private val maxRetries: Int = LIVE_TV_MAX_RETRIES,
    private val resetAfterMs: Long = LIVE_TV_RETRY_RESET_AFTER.inWholeMilliseconds,
) {
    private var attempt = 0
    private var lastFailureMs: Long? = null

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
}

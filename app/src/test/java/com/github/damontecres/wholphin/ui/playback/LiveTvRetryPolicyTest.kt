package com.github.damontecres.wholphin.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvRetryPolicyTest {
    private val resetAfterMs = 60_000L

    private fun policy(maxRetries: Int = 3) = LiveTvRetryPolicy(maxRetries, resetAfterMs)

    @Test
    fun `retries are numbered from one`() {
        val policy = policy()
        assertEquals(1, policy.onFailure(0L))
        assertEquals(2, policy.onFailure(100L))
        assertEquals(3, policy.onFailure(200L))
    }

    @Test
    fun `gives up after the maximum number of retries`() {
        val policy = policy()
        policy.onFailure(0L)
        policy.onFailure(100L)
        policy.onFailure(200L)
        assertNull("Should give up after 3 retries", policy.onFailure(300L))
    }

    @Test
    fun `stays given up on repeated failures`() {
        val policy = policy()
        repeat(3) { policy.onFailure(it * 100L) }
        assertNull(policy.onFailure(300L))
        assertNull(policy.onFailure(400L))
        assertNull(policy.onFailure(500L))
    }

    @Test
    fun `a single retry is allowed when the maximum is one`() {
        val policy = policy(maxRetries = 1)
        assertEquals(1, policy.onFailure(0L))
        assertNull(policy.onFailure(100L))
    }

    @Test
    fun `never retries when retries are disabled`() {
        val policy = policy(maxRetries = 0)
        assertNull(policy.onFailure(0L))
    }

    @Test
    fun `a stream that played for a while gets a fresh set of retries`() {
        val policy = policy()
        repeat(3) { policy.onFailure(it * 100L) }
        assertNull(policy.onFailure(300L))
        // The stream then played fine for well over the reset window before dropping again
        assertEquals(1, policy.onFailure(300L + resetAfterMs + 1))
        assertEquals(2, policy.onFailure(300L + resetAfterMs + 2))
    }

    @Test
    fun `failures inside the reset window keep counting up`() {
        val policy = policy()
        assertEquals(1, policy.onFailure(0L))
        // Exactly at the window is still considered the same run of failures
        assertEquals(2, policy.onFailure(resetAfterMs))
        assertEquals(3, policy.onFailure(2 * resetAfterMs - 1))
        assertNull(policy.onFailure(2 * resetAfterMs))
    }

    @Test
    fun `reset forgets previous failures`() {
        val policy = policy()
        repeat(3) { policy.onFailure(it * 100L) }
        assertNull(policy.onFailure(300L))
        policy.reset()
        assertEquals(1, policy.onFailure(400L))
    }

    @Test
    fun `reset on a fresh policy is harmless`() {
        val policy = policy()
        policy.reset()
        assertEquals(1, policy.onFailure(0L))
    }

    @Test
    fun `the first failure always retries however late it arrives`() {
        val policy = policy()
        assertEquals(1, policy.onFailure(999_999_999L))
    }

    @Test
    fun `a direct stream that never started falls back to a transcode`() {
        val policy = policy()
        assertEquals(
            LiveTvRecovery.FallBackToTranscode,
            policy.decide(0L, started = false, transcoding = false),
        )
    }

    @Test
    fun `falling back does not use up a reconnect`() {
        val policy = policy()
        policy.decide(0L, started = false, transcoding = false)
        // The transcode that replaced it then drops, and still gets all of its reconnects
        assertEquals(
            LiveTvRecovery.Reconnect(1, kotlin.time.Duration.ZERO),
            policy.decide(100L, started = true, transcoding = true),
        )
        assertEquals(2, (policy.decide(200L, started = true, transcoding = true) as LiveTvRecovery.Reconnect).attempt)
        assertEquals(3, (policy.decide(300L, started = true, transcoding = true) as LiveTvRecovery.Reconnect).attempt)
        assertEquals(LiveTvRecovery.GiveUp, policy.decide(400L, started = true, transcoding = true))
    }

    @Test
    fun `a transcode that never started reconnects rather than falling back again`() {
        val policy = policy()
        // Already transcoding, so there is nothing to fall back to and looping on it would hang
        assertEquals(
            LiveTvRecovery.Reconnect(1, kotlin.time.Duration.ZERO),
            policy.decide(0L, started = false, transcoding = true),
        )
    }

    @Test
    fun `a direct stream that dropped mid show reconnects the same way`() {
        val policy = policy()
        // It played, so the method works and the drop is the tuner, not the format
        assertEquals(
            LiveTvRecovery.Reconnect(1, kotlin.time.Duration.ZERO),
            policy.decide(0L, started = true, transcoding = false),
        )
    }

    @Test
    fun `the first reconnect is immediate and later ones back off`() {
        assertEquals(kotlin.time.Duration.ZERO, LiveTvRetryPolicy.delayFor(1))
        assertEquals(LIVE_TV_RETRY_DELAY, LiveTvRetryPolicy.delayFor(2))
        assertEquals(LIVE_TV_RETRY_DELAY * 2, LiveTvRetryPolicy.delayFor(3))
    }
}

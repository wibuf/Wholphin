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
}

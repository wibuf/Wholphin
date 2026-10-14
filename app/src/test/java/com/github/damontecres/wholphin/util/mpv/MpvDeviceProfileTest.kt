package com.github.damontecres.wholphin.util.mpv

import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvDeviceProfileTest {
    private fun h264Profiles(supportsAVCHigh10: Boolean) =
        mpvDeviceProfile(supportsAVCHigh10)
            .codecProfiles
            .filter { it.type == CodecType.VIDEO && it.codec == "h264" }

    @Test
    fun withoutAHi10PDecoderHigh10IsLeftToTheServer() {
        val profiles = h264Profiles(supportsAVCHigh10 = false)
        assertEquals(1, profiles.size)
        val condition = profiles.single().conditions.single()
        assertEquals(ProfileConditionValue.VIDEO_PROFILE, condition.property)
        val allowed = condition.value.orEmpty().split("|")
        assertFalse("high 10" in allowed)
        assertTrue(listOf("high", "main", "baseline", "constrained baseline").all { it in allowed })
    }

    @Test
    fun withADecoderEveryH264StillDirectPlays() {
        assertTrue(h264Profiles(supportsAVCHigh10 = true).isEmpty())
    }

    @Test
    fun nothingElseChanges() {
        val with = mpvDeviceProfile(true)
        val without = mpvDeviceProfile(false)
        assertEquals(with.directPlayProfiles, without.directPlayProfiles)
        assertEquals(with.transcodingProfiles, without.transcodingProfiles)
        assertEquals(with.subtitleProfiles, without.subtitleProfiles)
    }
}

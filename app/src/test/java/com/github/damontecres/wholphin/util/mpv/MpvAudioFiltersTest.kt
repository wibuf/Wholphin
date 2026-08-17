package com.github.damontecres.wholphin.util.mpv

import com.github.damontecres.wholphin.preferences.MpvOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAudioFiltersTest {
    private fun options(
        boost: Boolean = false,
        compress: Boolean = false,
    ): MpvOptions =
        MpvOptions
            .newBuilder()
            .setBoostDialogue(boost)
            .setCompressLoudScenes(compress)
            .build()

    @Test
    fun `no filters when both are off`() {
        assertEquals("", options().audioFilterChain())
    }

    @Test
    fun `dialogue boost alone is a single pan filter`() {
        val chain = options(boost = true).audioFilterChain()
        assertTrue("Should pan to stereo: $chain", chain.contains("pan=stereo"))
        assertTrue("Centre should feed both sides: $chain", chain.contains("FL=0.5*FC"))
        assertTrue("Should not compress: $chain", !chain.contains("dynaudnorm"))
    }

    @Test
    fun `compression alone is a single dynaudnorm filter`() {
        val chain = options(compress = true).audioFilterChain()
        assertTrue("Should compress: $chain", chain.contains("dynaudnorm"))
        assertTrue("Should not pan: $chain", !chain.contains("pan="))
    }

    @Test
    fun `both filters are chained in order`() {
        val chain = options(boost = true, compress = true).audioFilterChain()
        // Downmix first, then even out what came of it
        assertTrue("Pan should come first: $chain", chain.indexOf("pan=") < chain.indexOf("dynaudnorm"))
        assertEquals("Should be exactly two filters: $chain", 1, chain.count { it == ',' })
    }

    @Test
    fun `each filter is wrapped for the libavfilter bridge`() {
        val chain = options(boost = true, compress = true).audioFilterChain()
        assertEquals("Every filter needs lavfi[]: $chain", 2, Regex("lavfi=\\[").findAll(chain).count())
        assertEquals("Brackets should balance: $chain", chain.count { it == '[' }, chain.count { it == ']' })
    }

    @Test
    fun `channel layout separators survive inside the brackets`() {
        // mpv only parses the | separators correctly inside lavfi[...]
        val chain = options(boost = true).audioFilterChain()
        assertTrue("Pan needs its channel separators: $chain", chain.contains("|FR="))
        assertTrue("Pan must stay bracketed: $chain", chain.trim().endsWith("]"))
    }
}

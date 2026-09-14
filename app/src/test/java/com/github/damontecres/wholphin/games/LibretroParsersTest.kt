package com.github.damontecres.wholphin.games

import org.junit.Assert.assertEquals
import org.junit.Test

class LibretroParsersTest {
    @Test
    fun `controller types parse the tab-joined JNI payload and skip malformed rows`() {
        val parsed =
            NativeControllerTypeParser.parse(
                arrayOf("0\t1\tRetroPad", "1\t517\tAnalog\tExtra", "bad", "-1\t1\tNegative", "2\tx\tNaN"),
            )
        assertEquals(2, parsed.size)
        assertEquals(NativeControllerType(0, 1, "RetroPad"), parsed[0])
        // A label that itself contains a tab survives because the split is limited
        assertEquals("Analog\tExtra", parsed[1].label)
    }

    @Test
    fun `input descriptors keep descriptions intact`() {
        val parsed = NativeInputDescriptorParser.parse(arrayOf("0\t1\t0\t8\tFire", "0\t1\t0\t9\tCoin\tA", "0\t1\t0"))
        assertEquals(2, parsed.size)
        assertEquals(NativeInputDescriptor(0, 1, 0, 8, "Fire"), parsed[0])
        assertEquals("Coin\tA", parsed[1].description)
    }
}

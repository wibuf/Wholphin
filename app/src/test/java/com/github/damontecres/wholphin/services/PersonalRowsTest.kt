package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork-only (GooseFlix #1636) */
class PersonalRowsTest {
    private val rows = PersonalRows.rows().filterIsInstance<HomeRowConfig.CustomEndpoint>()
    private val picked = rows.first { it.endpoint.endsWith("picked-for-you") }
    private val mood = rows.first { it.endpoint.endsWith("mood") }

    @Test
    fun `five rows, all recognised as personal`() {
        assertEquals(2 + PersonalRows.MOOD_SLOTS, rows.size)
        assertTrue(rows.all { PersonalRows.isPersonal(it) })
        assertFalse(PersonalRows.isPersonal(HomeRowConfig.CustomEndpoint(endpoint = "/HomeScreen/Section/X", title = "X")))
        assertEquals(listOf("1", "2", "3"), rows.filter { it.endpoint.endsWith("mood") }.map { it.query!!.single().value })
    }

    @Test
    fun `the plugin's title wins, and a mood row says how many are in the library`() {
        assertEquals("Picked for you", PersonalRows.title(picked, null, 40))
        assertEquals("Because you watched Inception", PersonalRows.title(picked, " Because you watched Inception ", null))
        assertEquals("Heists & cons · 12 in your library", PersonalRows.title(mood, "Heists & cons", 12))
        assertEquals("Heists & cons", PersonalRows.title(mood, "Heists & cons", 0))
    }

    @Test
    fun `after a not-found the plugin isn't asked again for a while`() {
        PersonalRows.reset()
        assertTrue(PersonalRows.available(now = 1_000))
        PersonalRows.markUnavailable(now = 1_000)
        assertFalse(PersonalRows.available(now = 1_000 + PersonalRows.UNAVAILABLE_FOR.inWholeMilliseconds - 1))
        assertTrue(PersonalRows.available(now = 1_000 + PersonalRows.UNAVAILABLE_FOR.inWholeMilliseconds))
        PersonalRows.markUnavailable(now = 1_000)
        PersonalRows.reset()
        assertTrue(PersonalRows.available(now = 1_000))
    }
}

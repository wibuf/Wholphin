package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.games.model.GameLibrary
import com.github.damontecres.wholphin.services.NewHomeRows.LibraryView
import com.github.damontecres.wholphin.services.NewHomeRows.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class NewHomeRowsTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val movies = UUID.randomUUID()
    private val tv = UUID.randomUUID()
    private val halloween = UUID.randomUUID()
    private val music = UUID.randomUUID()

    private fun library(
        id: UUID,
        daysOld: Long = 400,
    ) = LibraryView(id, now.minus(daysOld, ChronoUnit.DAYS), HomeRowViewOptions())

    private fun row(id: UUID) = HomeRowConfig.RecentlyAdded(id)

    private val saved = listOf(HomeRowConfig.ContinueWatchingCombined(), row(movies), row(tv))
    private val oldLibraries = listOf(library(movies), library(tv), library(music))

    // As Scuffed orders a seasonal library: right after TV, ahead of the rest
    private val withHalloween = listOf(library(movies), library(tv), library(halloween, daysOld = 1), library(music))

    @Test
    fun `a new seasonal library is added once, where it sits in the library order`() {
        val plan =
            NewHomeRows.plan(saved, withHalloween, emptyList(), Record(), now)
        assertTrue(plan.changed)
        assertEquals(listOf(saved[0], row(movies), row(tv), row(halloween)), plan.rows)
        // Music was there before and has no row: most likely removed on purpose, so left alone
        assertFalse(plan.rows.contains(row(music)))

        val again = NewHomeRows.plan(plan.rows, withHalloween, emptyList(), plan.record, now)
        assertFalse("nothing more to add", again.changed)
    }

    @Test
    fun `a removed row stays removed`() {
        val first = NewHomeRows.plan(saved, withHalloween, emptyList(), Record(), now)
        // The user deletes the Halloween row in home settings
        val afterRemoval = first.rows - row(halloween)
        val next = NewHomeRows.plan(afterRemoval, withHalloween, emptyList(), first.record, now)
        assertFalse(next.changed)
        assertFalse(next.rows.contains(row(halloween)))
    }

    @Test
    fun `an added row hides while its library is switched off, and comes back`() {
        val first = NewHomeRows.plan(saved, withHalloween, emptyList(), Record(), now)
        val off = NewHomeRows.plan(first.rows, oldLibraries, emptyList(), first.record, now)
        assertFalse(off.changed)
        assertTrue("kept in settings", off.rows.contains(row(halloween)))
        assertFalse("but not shown", off.visible.contains(row(halloween)))

        val on = NewHomeRows.plan(off.rows, withHalloween, emptyList(), off.record, now)
        assertTrue(on.visible.contains(row(halloween)))
    }

    @Test
    fun `a user's own row hides while its library is away, and comes back`() {
        // The Halloween row came with the user's own home setup, not from here; the season ends
        val own = saved + row(halloween)
        val off = NewHomeRows.plan(own, oldLibraries, emptyList(), Record(), now)
        assertEquals("kept in settings", own, off.rows)
        assertFalse("not shown as a catch-all \"Recently added\"", off.visible.contains(row(halloween)))
        assertTrue(off.visible.containsAll(saved))

        val on = NewHomeRows.plan(off.rows, withHalloween, emptyList(), off.record, now)
        assertTrue(on.visible.contains(row(halloween)))
    }

    @Test
    fun `personal rows go at the bottom once, for existing users too`() {
        val personal = PersonalRows.rows()
        val first = NewHomeRows.plan(saved, oldLibraries, emptyList(), Record(), now, personalRows = personal)
        assertEquals(saved + personal, first.rows)
        assertTrue(first.changed)
        assertTrue(first.visible.containsAll(personal))

        // Removed by the user: they stay removed
        val removed = NewHomeRows.plan(saved, oldLibraries, emptyList(), first.record, now, personalRows = personal)
        assertEquals(saved, removed.rows)
        assertFalse(removed.changed)
    }

    @Test
    fun `personal rows already there are not added twice`() {
        val personal = PersonalRows.rows()
        val plan = NewHomeRows.plan(saved + personal.take(1), oldLibraries, emptyList(), Record(), now, personalRows = personal)
        assertEquals(saved + personal.take(1), plan.rows)
        assertTrue(PersonalRows.OFFER_KEY in plan.record.offered)
    }

    @Test
    fun `libraries added after the first run are offered whatever their age`() {
        val first = NewHomeRows.plan(saved, oldLibraries, emptyList(), Record(), now)
        assertFalse(first.changed)
        val other = UUID.randomUUID()
        val next = NewHomeRows.plan(saved, oldLibraries + library(other, daysOld = 900), emptyList(), first.record, now)
        assertTrue(next.rows.contains(row(other)))
    }

    @Test
    fun `games libraries get their row once`() {
        val games = listOf(GameLibrary(id = "moonbase", name = "Video Games"))
        val first = NewHomeRows.plan(saved, oldLibraries, games, Record(), now)
        assertEquals(HomeRowConfig.Games("moonbase", "Video Games"), first.rows.last())

        val removed = first.rows.dropLast(1)
        val next = NewHomeRows.plan(removed, oldLibraries, games, first.record, now)
        assertFalse("removed games row stays removed", next.changed)
    }

    @Test
    fun `a library first in the order goes before the next library's row`() {
        val first = UUID.randomUUID()
        val libraries = listOf(library(first, daysOld = 1), library(movies), library(tv))
        val plan = NewHomeRows.plan(saved, libraries, emptyList(), Record(), now)
        assertEquals(listOf(saved[0], row(first), row(movies), row(tv)), plan.rows)
    }
}

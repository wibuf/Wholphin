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
        tvShows: Boolean = false,
    ) = LibraryView(id, tvShows, now.minus(daysOld, ChronoUnit.DAYS), HomeRowViewOptions())

    private fun row(id: UUID) = HomeRowConfig.RecentlyAdded(id)

    private val saved = listOf(HomeRowConfig.ContinueWatchingCombined(), row(movies), row(tv))
    private val oldLibraries = listOf(library(movies), library(tv, tvShows = true), library(music))

    @Test
    fun `a new seasonal library is added once, right after TV`() {
        val plan =
            NewHomeRows.plan(saved, oldLibraries + library(halloween, daysOld = 1), emptyList(), Record(), now)
        assertTrue(plan.changed)
        assertEquals(listOf(saved[0], row(movies), row(tv), row(halloween)), plan.rows)
        // Music was there before and has no row: most likely removed on purpose, so left alone
        assertFalse(plan.rows.contains(row(music)))

        val again = NewHomeRows.plan(plan.rows, oldLibraries + library(halloween, daysOld = 1), emptyList(), plan.record, now)
        assertFalse("nothing more to add", again.changed)
    }

    @Test
    fun `a removed row stays removed`() {
        val first = NewHomeRows.plan(saved, oldLibraries + library(halloween, daysOld = 1), emptyList(), Record(), now)
        // The user deletes the Halloween row in home settings
        val afterRemoval = first.rows - row(halloween)
        val next = NewHomeRows.plan(afterRemoval, oldLibraries + library(halloween, daysOld = 1), emptyList(), first.record, now)
        assertFalse(next.changed)
        assertFalse(next.rows.contains(row(halloween)))
    }

    @Test
    fun `an added row hides while its library is switched off, and comes back`() {
        val first = NewHomeRows.plan(saved, oldLibraries + library(halloween, daysOld = 1), emptyList(), Record(), now)
        val off = NewHomeRows.plan(first.rows, oldLibraries, emptyList(), first.record, now)
        assertFalse(off.changed)
        assertTrue("kept in settings", off.rows.contains(row(halloween)))
        assertFalse("but not shown", off.visible.contains(row(halloween)))

        val on = NewHomeRows.plan(off.rows, oldLibraries + library(halloween, daysOld = 1), emptyList(), off.record, now)
        assertTrue(on.visible.contains(row(halloween)))
    }

    @Test
    fun `a user's own rows are never hidden`() {
        val plan = NewHomeRows.plan(saved, listOf(library(movies)), emptyList(), Record(), now)
        assertEquals("TV is missing from the views, but the row is the user's own", saved, plan.visible)
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
}

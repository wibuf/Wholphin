package com.github.damontecres.wholphin.ui.search

import com.github.damontecres.wholphin.data.model.BaseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SearchLibrariesTest {
    private fun item(
        name: String,
        year: Int?,
        type: BaseItemKind = BaseItemKind.MOVIE,
    ) = BaseItem(BaseItemDto(id = UUID.randomUUID(), type = type, name = name, productionYear = year), false)

    @Test
    fun `only titles in more than one library are looked up`() {
        val hbo = item("Supergirl", 2026)
        val movies = item("Supergirl", 2026)
        val cams = item("supergirl ", 2026)
        val older = item("Supergirl", 1984)
        val show = item("Supergirl", 2026, BaseItemKind.SERIES)
        val episodes = listOf(item("Pilot", 2020, BaseItemKind.EPISODE), item("Pilot", 2020, BaseItemKind.EPISODE))
        val ids = SearchLibraries.duplicateIds(listOf(hbo, movies, cams, older, show) + episodes)
        assertEquals(setOf(hbo.id, movies.id, cams.id), ids.toSet())
    }

    @Test
    fun `the library goes after the year`() {
        assertEquals("2026 · HBO Max", SearchLibraries.subtitle("2026", "HBO Max"))
        assertEquals("HBO Max", SearchLibraries.subtitle(null, "HBO Max"))
        assertEquals("2026", SearchLibraries.subtitle("2026", null))
        assertNull(SearchLibraries.subtitle(" ", null))
    }
}

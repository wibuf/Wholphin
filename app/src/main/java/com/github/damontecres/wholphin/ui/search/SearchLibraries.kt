package com.github.damontecres.wholphin.ui.search

import com.github.damontecres.wholphin.data.model.BaseItem
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * Fork: which library a search result is in, for titles that are in more than one. Searching
 * "Supergirl" can find the same movie in Movies, 4K, Cams and HBO Max, four identical cards with
 * no way to tell them apart, so those get the library name after the year ("2026 · HBO Max").
 * Only duplicates are looked up (one small request each), so a normal search costs nothing extra.
 */
object SearchLibraries {
    private val labelled = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)

    /** Movies and shows whose name and year appear more than once in [items] */
    fun duplicateIds(items: List<BaseItem>): List<UUID> =
        items
            .filter { it.type in labelled }
            .groupBy { Triple(it.type, it.name?.trim()?.lowercase(), it.data.productionYear) }
            .values
            .filter { it.size > 1 }
            .flatten()
            .map { it.id }

    /** "2026 · HBO Max", or whichever part there is */
    fun subtitle(
        subtitle: String?,
        library: String?,
    ): String? = listOfNotNull(subtitle?.ifBlank { null }, library?.ifBlank { null }).joinToString(" · ").ifEmpty { null }
}

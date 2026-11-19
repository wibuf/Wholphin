package com.github.damontecres.wholphin.util

import com.github.damontecres.wholphin.services.PluginRatings
import com.github.damontecres.wholphin.services.RatingScore
import com.github.damontecres.wholphin.services.RatingSortSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/**
 * Fork-only (GooseFlix #1671): a library sorted by one rating source (IMDb, RT, MyAnimeList, ...).
 *
 * Jellyfin can only sort by its own two rating fields, so on the first page this:
 * 1. asks Jellyfin for every title matching the library's request (the user's filters and access,
 *    by name, ids only);
 * 2. asks the GooseFlix plugin for the source's score of every title in the library;
 * 3. orders the titles by score, unrated ones last (by name).
 *
 * Each page is then the next slice of that order, fetched by id with the request's fields. [scores]
 * holds the scores for the cards' badges.
 */
@Serializable
class RatingSortRequestHandler(
    val sourceKey: String,
    val descending: Boolean,
) : RequestHandler<GetItemsRequest> {
    @Transient
    private val mutex = Mutex()

    @Transient
    private var order: List<UUID>? = null

    @Transient
    private val _scores = MutableStateFlow<Map<UUID, RatingScore>>(emptyMap())

    /** Every title's score for this source, once the first page has loaded */
    val scores: StateFlow<Map<UUID, RatingScore>> get() = _scores

    val source: RatingSortSource get() = RatingSortSource.fromKey(sourceKey) ?: RatingSortSource.IMDB

    override fun prepare(
        request: GetItemsRequest,
        startIndex: Int,
        limit: Int,
        enableTotalRecordCount: Boolean,
    ): GetItemsRequest =
        request.copy(
            startIndex = startIndex,
            limit = limit,
            enableTotalRecordCount = enableTotalRecordCount,
        )

    override suspend fun execute(
        api: ApiClient,
        request: GetItemsRequest,
    ): Response<BaseItemDtoQueryResult> {
        val order = order(api, request)
        val start = (request.startIndex ?: 0).coerceIn(0, order.size)
        val slice = order.subList(start, (start + (request.limit ?: order.size)).coerceAtMost(order.size))
        if (slice.isEmpty()) {
            return Response(BaseItemDtoQueryResult(emptyList(), order.size, start), 200, emptyMap())
        }
        val page by api.itemsApi.getItems(
            request.copy(
                ids = slice,
                startIndex = null,
                limit = null,
                sortBy = null,
                sortOrder = null,
                enableTotalRecordCount = false,
            ),
        )
        val byId = page.items.associateBy { it.id }
        return Response(BaseItemDtoQueryResult(slice.mapNotNull { byId[it] }, order.size, start), 200, emptyMap())
    }

    private suspend fun order(
        api: ApiClient,
        request: GetItemsRequest,
    ): List<UUID> =
        mutex.withLock {
            order ?: run {
                val all by api.itemsApi.getItems(
                    request.copy(
                        startIndex = null,
                        limit = null,
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        fields = emptyList(),
                        enableImages = false,
                        enableImageTypes = emptyList(),
                        enableUserData = false,
                        enableTotalRecordCount = false,
                    ),
                )
                val ids = all.items.map { it.id }
                val scores = request.parentId?.let { PluginRatings.values(api, it, source) }.orEmpty()
                _scores.value = scores
                PluginRatings.order(ids, scores, descending).also { order = it }
            }
        }
}

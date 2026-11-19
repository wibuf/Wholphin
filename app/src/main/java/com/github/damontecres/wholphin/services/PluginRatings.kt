package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/** Fork (GooseFlix #1671): a rating source the library can be sorted by, from the GooseFlix plugin */
enum class RatingSortSource(
    /** MDBList's name for it */
    val key: String,
    val label: String,
) {
    IMDB("imdb", "IMDb"),
    TOMATOES("tomatoes", "RT critics"),
    POPCORN("popcorn", "RT audience"),
    MAL("myanimelist", "MyAnimeList"),
    METACRITIC("metacritic", "Metacritic"),
    LETTERBOXD("letterboxd", "Letterboxd"),
    TMDB("tmdb", "TMDB"),
    TRAKT("trakt", "Trakt"),
    MDBLIST("mdblist", "MDBList score"),
    ;

    companion object {
        fun fromKey(key: String?): RatingSortSource? = entries.firstOrNull { it.key == key }

        /** MyAnimeList for an anime library, IMDb for everything else (owner, 11-19) */
        fun defaultFor(libraryName: String?): RatingSortSource = if (libraryName?.contains("anime", ignoreCase = true) == true) MAL else IMDB
    }
}

/** One title's rating from one source: [score] is 0–100, [value] the source's own scale */
@Serializable
data class RatingScore(
    val value: Double? = null,
    val score: Double,
    val votes: Int? = null,
)

@Serializable
private data class RatingValuesResponse(
    val source: String = "",
    val values: Map<String, RatingScore> = emptyMap(),
)

/**
 * Fork-only (GooseFlix #1671): ratings served by the GooseFlix server plugin, which fetches every
 * title's MDBList ratings itself.
 *
 * - [available]: whether the plugin lists the `ratings` feature (cached ten minutes per server).
 *   Without it the library keeps Jellyfin's own Community and Critic rating sorts.
 * - [values]: one source's score for every title in a library, for the "Rating" sort.
 * - The per-title ratings for the details row come from `MdbListRatingsService`, which asks the
 *   plugin first and the MDBList Ratings plugin second.
 */
object PluginRatings {
    const val VALUES_PATH = "/wholphin/ratings/values"
    const val ITEM_PATH = "/wholphin/ratings/item"
    private const val CAPABILITIES_PATH = PersonalRows.PREFIX + "capabilities"
    private const val FEATURE = "ratings"
    private const val CACHE_MS = 10 * 60 * 1000L
    private const val PREFS = "gooseflix_rating_sort"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var checked: Triple<String?, Long, Boolean>? = null

    /** Whether the server's GooseFlix plugin serves ratings */
    suspend fun available(
        api: ApiClient,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        checked?.let { (server, at, result) -> if (server == api.baseUrl && now - at < CACHE_MS) return result }
        val result =
            withContext(WholphinDispatchers.IO) {
                try {
                    val response = api.request(method = HttpMethod.GET, pathTemplate = CAPABILITIES_PATH)
                    response.status == 200 && hasFeature(response.body.decodeToString())
                } catch (ex: Exception) {
                    Timber.d(ex, "Rating sort: no capabilities")
                    false
                }
            }
        checked = Triple(api.baseUrl, now, result)
        return result
    }

    /** Whether a capabilities answer (`{"Features": [...]}`, any case) lists the ratings feature */
    internal fun hasFeature(body: String): Boolean =
        try {
            val features =
                (json.parseToJsonElement(body) as? JsonObject)
                    ?.entries
                    ?.firstOrNull { it.key.equals("features", ignoreCase = true) }
                    ?.value as? JsonArray
            features.orEmpty().any { (it as? JsonPrimitive)?.content.equals(FEATURE, ignoreCase = true) }
        } catch (ex: Exception) {
            false
        }

    /** One source's score for every title in [parentId] the user can see; titles without it are absent */
    suspend fun values(
        api: ApiClient,
        parentId: UUID,
        source: RatingSortSource,
    ): Map<UUID, RatingScore> =
        withContext(WholphinDispatchers.IO) {
            val response =
                api.request(
                    method = HttpMethod.GET,
                    pathTemplate = VALUES_PATH,
                    queryParameters = mapOf("parentId" to parentId.toString(), "source" to source.key),
                )
            if (response.status != 200) throw IllegalStateException("Ratings: HTTP ${response.status}")
            parseValues(response.body.decodeToString())
        }

    internal fun parseValues(body: String): Map<UUID, RatingScore> =
        json
            .decodeFromString<RatingValuesResponse>(body)
            .values
            .mapNotNull { (id, score) -> parseId(id)?.let { it to score } }
            .toMap()

    /** Jellyfin ids come without dashes ("N" format) */
    private fun parseId(id: String): UUID? =
        try {
            if (id.length == 32) {
                UUID.fromString("${id.take(8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}")
            } else {
                UUID.fromString(id)
            }
        } catch (ex: IllegalArgumentException) {
            null
        }

    /**
     * [ids] ordered by score, highest first (lowest first if not [descending]); titles without a
     * score go last either way, keeping their order (by name) among themselves.
     */
    fun order(
        ids: List<UUID>,
        scores: Map<UUID, RatingScore>,
        descending: Boolean,
    ): List<UUID> {
        val (rated, unrated) = ids.withIndex().partition { scores[it.value] != null }
        val comparator = compareBy<IndexedValue<UUID>> { scores.getValue(it.value).score }
        val sorted = rated.sortedWith(if (descending) comparator.reversed().thenBy { it.index } else comparator.thenBy { it.index })
        return sorted.map { it.value } + unrated.map { it.value }
    }

    /** A card's badge: "★ 9.2" for 10-point and 5-point scales, "92%" for Rotten Tomatoes, "★ 86" otherwise */
    fun label(
        source: RatingSortSource,
        score: RatingScore,
    ): String {
        val value = score.value
        return when {
            source == RatingSortSource.TOMATOES || source == RatingSortSource.POPCORN -> "${(value ?: score.score).toInt()}%"
            value != null && value <= 10 -> "★ ${String.format(Locale.US, "%.1f", value)}"
            else -> "★ ${(value ?: score.score).toInt()}"
        }
    }

    /** The source the user picked for a library, else [RatingSortSource.defaultFor] its name */
    fun savedSource(
        context: Context,
        userRowId: Int?,
        libraryId: String,
        libraryName: String?,
    ): RatingSortSource =
        RatingSortSource.fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("$userRowId:$libraryId", null),
        ) ?: RatingSortSource.defaultFor(libraryName)

    fun saveSource(
        context: Context,
        userRowId: Int?,
        libraryId: String,
        source: RatingSortSource,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("$userRowId:$libraryId", source.key)
            .apply()
    }

    /** For tests: forget the cached capability check */
    internal fun reset() {
        checked = null
    }
}


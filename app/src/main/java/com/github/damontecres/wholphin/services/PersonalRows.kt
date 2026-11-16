package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.KeyValueEntry
import kotlin.time.Duration.Companion.minutes

/**
 * Fork-only (GooseFlix #1636): personal home rows, served by the GooseFlix server plugin (#1635).
 *
 * They are ordinary custom endpoint rows pointed at the plugin's `/wholphin/rows/...` endpoints, so
 * home settings can move or remove them like any other row. The app must work the same without the
 * plugin (not installed, upstream's plugin instead, or an error): a personal row that can't be
 * fetched comes back empty, and empty rows don't show. After a "not found" the app stops asking for
 * [UNAVAILABLE_FOR], so a server without the plugin isn't asked five times on every home load.
 */
object PersonalRows {
    const val PREFIX = "/wholphin/rows/"

    /** The plugin's row title, eg "Because you watched Inception" or a mood's name */
    const val TITLE_HEADER = "X-GooseFlix-Row-Title"

    /** Remembered in [NewHomeRows.Record.offered] once a user has been given these rows */
    const val OFFER_KEY = "personal:v1"

    /**
     * Rows added after [OFFER_KEY] (the movie and show "Because you watched", moods 4 to 6): given
     * once to homes that still have the rows they extend ([withNewPersonalRows])
     */
    const val OFFER_V2 = "personal:v2"

    /** Mood slots the app asks for; a slot the plugin has no mood for today is empty and hidden */
    const val MOOD_SLOTS = 6

    private const val MOOD = "mood"

    const val BECAUSE_YOU_WATCHED = PREFIX + "because-you-watched"

    /**
     * Which "Because you watched" row the plugin answered for. A plugin from before the movie and
     * show rows (0.1.1.500) ignores the kind and sends no such header, and would send the
     * "anything" row three times, so a kind row without it is treated as empty.
     */
    const val KIND_HEADER = "X-GooseFlix-Row-Kind"

    /** The "Because you watched" row's kind (`movie`, `series`), or null for the one from anything */
    fun kindOf(row: HomeRowConfig.CustomEndpoint): String? = row.query?.firstOrNull { it.key == "kind" }?.value

    /** Not a plugin endpoint: the app fetches the user's own Seerr requests itself ([MyRequests]) */
    const val MY_REQUESTS = PREFIX + "my-requests"

    val UNAVAILABLE_FOR = 30.minutes

    /** The personal rows, in their order at the bottom of the home page */
    fun rows(): List<HomeRowConfig> =
        buildList {
            add(HomeRowConfig.CustomEndpoint(endpoint = PREFIX + "picked-for-you", title = "Picked for you"))
            add(becauseYouWatched("movie", "Because you watched (movies)"))
            add(becauseYouWatched("series", "Because you watched (shows)"))
            add(becauseYouWatched(null, "Because you watched"))
            for (slot in 1..MOOD_SLOTS) {
                add(
                    HomeRowConfig.CustomEndpoint(
                        endpoint = PREFIX + MOOD,
                        title = "Mood $slot",
                        query = listOf(KeyValueEntry("slot", slot.toString())),
                    ),
                )
            }
            add(HomeRowConfig.CustomEndpoint(endpoint = MY_REQUESTS, title = "My requests"))
        }

    private fun becauseYouWatched(
        kind: String?,
        title: String,
    ) = HomeRowConfig.CustomEndpoint(
        endpoint = BECAUSE_YOU_WATCHED,
        title = title,
        query = kind?.let { listOf(KeyValueEntry("kind", it)) },
    )

    /**
     * [rows] plus the personal rows added since [OFFER_KEY] that it lacks: the movie and show
     * "Because you watched" before the user's own (the one from anything), moods 4 to 6 after their
     * last mood. Only where the user still has that kind of row: someone who removed every mood
     * row doesn't get three new ones.
     */
    fun withNewPersonalRows(
        rows: List<HomeRowConfig>,
        personal: List<HomeRowConfig>,
    ): List<HomeRowConfig> {
        val result = rows.toMutableList()
        for (row in personal) {
            if (row !is HomeRowConfig.CustomEndpoint || row in result) continue
            when {
                row.endpoint == BECAUSE_YOU_WATCHED && kindOf(row) != null -> {
                    val first = result.indexOfFirst { it is HomeRowConfig.CustomEndpoint && it.endpoint == BECAUSE_YOU_WATCHED && kindOf(it) == null }
                    if (first >= 0) result.add(first, row)
                }

                row.endpoint == PREFIX + MOOD -> {
                    val last = result.indexOfLast { it is HomeRowConfig.CustomEndpoint && it.endpoint == PREFIX + MOOD }
                    if (last >= 0) result.add(last + 1, row)
                }
            }
        }
        return result
    }

    /** The source title's TMDB id, which the "Because you watched" rows send */
    const val SOURCE_TMDB_HEADER = "X-GooseFlix-Source-Tmdb"

    /**
     * One card per title. A movie that's in two libraries (eg Movies and 4K) comes back from the
     * plugin once per copy; the first copy, highest in the row, stays. A title counts as the same
     * when its type and TMDB id match (else IMDb id, else name and year). Copies of the title a
     * "Because you watched" row is built from ([sourceTmdb]) are dropped too.
     */
    fun distinctTitles(
        items: List<org.jellyfin.sdk.model.api.BaseItemDto>,
        sourceTmdb: String? = null,
    ): List<org.jellyfin.sdk.model.api.BaseItemDto> {
        val seen = HashSet<String>()
        return items.filter { item ->
            val ids = item.providerIds.orEmpty()
            val tmdb = ids.entries.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            val imdb = ids.entries.firstOrNull { it.key.equals("Imdb", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            if (sourceTmdb != null && tmdb == sourceTmdb) return@filter false
            val key =
                "${item.type}:" + (
                    tmdb?.let { "tmdb:$it" }
                        ?: imdb?.let { "imdb:$it" }
                        ?: "name:${item.name?.trim()?.lowercase()}:${item.productionYear}"
                )
            seen.add(key)
        }
    }

    private val _disliked = kotlinx.coroutines.flow.MutableStateFlow<Set<java.util.UUID>>(emptySet())

    /**
     * Titles marked "Don't recommend" on this device since the app started. The plugin leaves
     * disliked titles out itself (0.1.1.501); this hides them at once, and from an older plugin.
     */
    val disliked: kotlinx.coroutines.flow.StateFlow<Set<java.util.UUID>> = _disliked

    fun markDisliked(itemId: java.util.UUID) {
        _disliked.value = _disliked.value + itemId
    }

    /**
     * Where My requests goes (owner, 2026-11-15): above the seasonal library, ie right after the TV
     * library's row, which the seasonal one follows (Scuffed's library order). Without a TV row,
     * after Continue Watching / Next Up; without those, at the top.
     */
    fun myRequestsIndex(
        rows: List<HomeRowConfig>,
        tvLibraryId: java.util.UUID?,
    ): Int {
        tvLibraryId
            ?.let { tv -> rows.indexOfFirst { (it as? HomeRowConfig.RecentlyAdded)?.parentId == tv } }
            ?.takeIf { it >= 0 }
            ?.let { return it + 1 }
        val lastWatching =
            rows.indexOfLast {
                it is HomeRowConfig.ContinueWatching || it is HomeRowConfig.NextUp || it is HomeRowConfig.ContinueWatchingCombined
            }
        return lastWatching + 1
    }

    /**
     * [rows] plus [personal]: My requests placed by [myRequestsIndex], the rest at the bottom, but
     * above the rows that always stay last ([withBottomLast]).
     */
    fun withPersonal(
        rows: List<HomeRowConfig>,
        personal: List<HomeRowConfig>,
        tvLibraryId: java.util.UUID?,
        bottomLibraryIds: List<java.util.UUID> = emptyList(),
    ): List<HomeRowConfig> {
        val (requests, others) = personal.partition { (it as? HomeRowConfig.CustomEndpoint)?.endpoint == MY_REQUESTS }
        val result = (rows + others).toMutableList()
        requests.forEach { result.add(myRequestsIndex(result, tvLibraryId), it) }
        return withBottomLast(result, bottomLibraryIds)
    }

    /**
     * Libraries whose home rows stay at the very bottom, in this order: below the personal rows too.
     * Matched by name, like the TV library.
     */
    val BOTTOM_LIBRARIES = listOf("Recordings", "Video Games")

    /** Moves the bottom rows of an existing home to the end once (GooseFlix #1636) */
    const val BOTTOM_KEY = "bottom:v1"

    /** The ids of [BOTTOM_LIBRARIES] among [libraries] (id, name), in [BOTTOM_LIBRARIES] order */
    fun bottomLibraryIds(libraries: List<Pair<java.util.UUID, String?>>): List<java.util.UUID> =
        BOTTOM_LIBRARIES.mapNotNull { name -> libraries.firstOrNull { it.second.equals(name, ignoreCase = true) }?.first }

    /** Whether [row] is one of the rows that stay last: a bottom library's, or a Moonbase games row */
    fun isBottom(
        row: HomeRowConfig,
        bottomLibraryIds: List<java.util.UUID>,
    ): Boolean = row is HomeRowConfig.Games || (row is HomeRowConfig.RecentlyAdded && row.parentId in bottomLibraryIds)

    /** [rows] with the bottom rows moved to the end: the libraries' rows in order, then games rows */
    fun withBottomLast(
        rows: List<HomeRowConfig>,
        bottomLibraryIds: List<java.util.UUID>,
    ): List<HomeRowConfig> {
        val (bottom, rest) = rows.partition { isBottom(it, bottomLibraryIds) }
        val ordered =
            bottom.sortedBy { row ->
                (row as? HomeRowConfig.RecentlyAdded)?.let { bottomLibraryIds.indexOf(it.parentId) } ?: bottomLibraryIds.size
            }
        return rest + ordered
    }

    /** Whether a personal row sits below a bottom row, as the first personal rows were placed */
    fun personalBelowBottom(
        rows: List<HomeRowConfig>,
        bottomLibraryIds: List<java.util.UUID>,
    ): Boolean {
        val firstBottom = rows.indexOfFirst { isBottom(it, bottomLibraryIds) }
        return firstBottom >= 0 && rows.drop(firstBottom).any { isPersonal(it) }
    }

    fun isPersonal(row: HomeRowConfig): Boolean = row is HomeRowConfig.CustomEndpoint && row.endpoint.startsWith(PREFIX)

    /**
     * The title to show: the plugin's, if it sent one, else the row's own. A mood row adds how many
     * titles the user has in it ("Heists & cons · 12 in your library").
     */
    fun title(
        row: HomeRowConfig.CustomEndpoint,
        headerTitle: String?,
        total: Int?,
    ): String {
        val name = headerTitle?.let(::decodeHeader)?.trim()?.takeIf { it.isNotEmpty() } ?: row.title
        return if (row.endpoint == PREFIX + MOOD && total != null && total > 0) {
            "$name · $total in your library"
        } else {
            name
        }
    }

    /**
     * The plugin percent-encodes header values (UTF-8), since titles can be non-ASCII. Uses the
     * charset-name overload: `decode(String, Charset)` is Android 13+ only, and crashed the home page
     * on the Shield (Android 11).
     */
    fun decodeHeader(value: String): String =
        try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (ex: IllegalArgumentException) {
            value
        }

    @Volatile
    private var unavailableUntil = 0L

    fun available(now: Long = System.currentTimeMillis()): Boolean = now >= unavailableUntil

    /** The plugin isn't there (a 404, or upstream's plugin without these endpoints) */
    fun markUnavailable(now: Long = System.currentTimeMillis()) {
        unavailableUntil = now + UNAVAILABLE_FOR.inWholeMilliseconds
    }

    /** Eg on sign-in to another server or user */
    fun reset() {
        unavailableUntil = 0L
    }
}

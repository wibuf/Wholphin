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

    const val MOOD_SLOTS = 3

    private const val MOOD = "mood"

    /** Not a plugin endpoint: the app fetches the user's own Seerr requests itself ([MyRequests]) */
    const val MY_REQUESTS = PREFIX + "my-requests"

    val UNAVAILABLE_FOR = 30.minutes

    /** The personal rows, in their order at the bottom of the home page */
    fun rows(): List<HomeRowConfig> =
        buildList {
            add(HomeRowConfig.CustomEndpoint(endpoint = PREFIX + "picked-for-you", title = "Picked for you"))
            add(HomeRowConfig.CustomEndpoint(endpoint = PREFIX + "because-you-watched", title = "Because you watched"))
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

    /** [rows] plus [personal], My requests placed by [myRequestsIndex] and the rest at the bottom */
    fun withPersonal(
        rows: List<HomeRowConfig>,
        personal: List<HomeRowConfig>,
        tvLibraryId: java.util.UUID?,
    ): List<HomeRowConfig> {
        val (requests, others) = personal.partition { (it as? HomeRowConfig.CustomEndpoint)?.endpoint == MY_REQUESTS }
        val result = (rows + others).toMutableList()
        requests.forEach { result.add(myRequestsIndex(result, tvLibraryId), it) }
        return result
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

    /** The plugin percent-encodes header values (UTF-8), since titles can be non-ASCII */
    fun decodeHeader(value: String): String =
        try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
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

package com.github.damontecres.wholphin.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single rating from the MDBList Ratings server plugin
 *
 * [value] is in the source's own scale (IMDb 7.2/10, Rotten Tomatoes 68/100) while [score] is
 * normalised to 0-100 for comparing sources against each other.
 */
@Serializable
data class MdbListRating(
    val source: String,
    val value: Double? = null,
    val score: Double? = null,
    val votes: Int? = null,
    val url: String? = null,
)

/**
 * The plugin's cached ratings for one item
 *
 * Unknown fields are ignored rather than failing the whole response, so a newer plugin adding
 * fields does not stop the ratings we do understand from rendering.
 */
@Serializable
data class MdbListRatingsResponse(
    val hasCache: Boolean = false,
    @SerialName("cachedAtUtc") val cachedAtUtc: String? = null,
    val ratings: List<MdbListRating> = emptyList(),
) {
    /** The rating from [source], or null when the plugin has no cached value for it */
    fun forSource(source: MdbListSource): MdbListRating? =
        ratings.firstOrNull { it.source.equals(source.key, ignoreCase = true) }
}

/**
 * The rating sources this app knows how to display
 *
 * The plugin returns others (trakt, letterboxd, metacritic); they are simply not rendered.
 */
enum class MdbListSource(
    val key: String,
) {
    /** Out of 10 */
    IMDB("imdb"),

    /** Rotten Tomatoes critics, a percentage */
    TOMATOES("tomatoes"),

    /** Rotten Tomatoes audience, a percentage */
    POPCORN("popcorn"),

    /** Out of 100 */
    TMDB("tmdb"),
}

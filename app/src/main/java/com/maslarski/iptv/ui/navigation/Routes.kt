package com.maslarski.iptv.ui.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object Live : Route
    @Serializable data object Movies : Route
    @Serializable data object Series : Route
    @Serializable data object Search : Route
    @Serializable data object Favorites : Route
    @Serializable data object Settings : Route
    @Serializable data object Playlists : Route
    @Serializable data class EditPlaylist(val playlistId: Long = 0L) : Route
    @Serializable data class MovieDetails(val playlistId: Long, val movieId: String) : Route
    @Serializable data class SeriesDetails(val playlistId: Long, val seriesId: String) : Route
    @Serializable data class Player(
        val playlistId: Long,
        val contentId: String,
        val contentType: String,
        val categoryId: String? = null,
        val favoritesOnly: Boolean = false,
    ) : Route
    @Serializable data object Activation : Route
    @Serializable data object ManageCategories : Route
}

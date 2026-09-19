package com.maslarski.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.maslarski.iptv.R
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.ui.browse.BrowseScreen
import com.maslarski.iptv.ui.browse.LiveViewModel
import com.maslarski.iptv.ui.browse.MoviesViewModel
import com.maslarski.iptv.ui.browse.SeriesViewModel
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberFocusState
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.details.MovieDetailsScreen
import com.maslarski.iptv.ui.details.SeriesDetailsScreen
import com.maslarski.iptv.ui.favorites.FavoritesScreen
import com.maslarski.iptv.ui.guide.GuideScreen
import com.maslarski.iptv.ui.home.Featured
import com.maslarski.iptv.ui.home.HomeScreen
import com.maslarski.iptv.ui.navigation.Route
import com.maslarski.iptv.ui.player.PlayerScreen
import com.maslarski.iptv.ui.playlists.EditPlaylistScreen
import com.maslarski.iptv.ui.playlists.PlaylistsScreen
import com.maslarski.iptv.ui.search.SearchScreen
import com.maslarski.iptv.ui.settings.SettingsScreen
import com.maslarski.iptv.ui.theme.Palette

private data class NavItem(val route: Route, val icon: ImageVector, val label: Int)

private val NavItems = listOf(
    NavItem(Route.Home, Icons.Filled.Home, R.string.nav_home),
    NavItem(Route.Live, Icons.Filled.LiveTv, R.string.nav_live),
    NavItem(Route.Guide, Icons.Filled.ViewAgenda, R.string.nav_guide),
    NavItem(Route.Movies, Icons.Filled.Movie, R.string.nav_movies),
    NavItem(Route.Series, Icons.Filled.Tv, R.string.nav_series),
    NavItem(Route.Search, Icons.Filled.Search, R.string.nav_search),
    NavItem(Route.Favorites, Icons.Filled.Favorite, R.string.nav_favorites),
    NavItem(Route.Settings, Icons.Filled.Settings, R.string.nav_settings),
)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun IptvApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val activity = LocalActivity.current
    val isCompact = activity != null && calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Compact
    val isPlayer = destination?.hasRoute<Route.Player>() == true
    val showNav = !isPlayer && NavItems.any { item -> destination?.hasRoute(item.route::class) == true }

    fun navigateTop(route: Route) {
        nav.navigate(route) {
            popUpTo(Route.Home) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        val content: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.then(if (isPlayer) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                AppNavHost(nav, isCompact)
            }
        }
        if (isCompact) {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f))
                AnimatedVisibility(showNav, enter = fadeIn(), exit = fadeOut()) {
                    BottomBar(destination?.let { d -> NavItems.firstOrNull { d.hasRoute(it.route::class) } }, ::navigateTop)
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(showNav, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
                    SideRail(destination?.let { d -> NavItems.firstOrNull { d.hasRoute(it.route::class) } }, ::navigateTop)
                }
                content(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, isCompact: Boolean) {
    val play: (MediaItem) -> Unit = { item ->
        nav.navigate(Route.Player(item.playlistId, item.id, item.type.name, item.categoryId))
    }
    val openDetails: (MediaItem) -> Unit = { item ->
        when (item.type) {
            ContentType.LIVE -> play(item)
            ContentType.MOVIE -> nav.navigate(Route.MovieDetails(item.playlistId, item.id))
            ContentType.SERIES -> nav.navigate(Route.SeriesDetails(item.playlistId, item.seriesId ?: item.id))
        }
    }
    NavHost(
        navController = nav,
        startDestination = Route.Home,
        enterTransition = { fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.98f) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(300)) },
        popExitTransition = { fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.98f) },
    ) {
        composable<Route.Home> {
            HomeScreen(
                onPlay = play,
                onOpenDetails = openDetails,
                onAddPlaylist = { nav.navigate(Route.EditPlaylist()) },
                onOpenFeatured = { f ->
                    when (f) {
                        is Featured.OfMovie -> nav.navigate(Route.MovieDetails(f.movie.playlistId, f.movie.id))
                        is Featured.OfSeries -> nav.navigate(Route.SeriesDetails(f.series.playlistId, f.series.id))
                    }
                },
                isCompact = isCompact,
            )
        }
        composable<Route.Live> {
            BrowseScreen(stringResource(R.string.nav_live), ContentType.LIVE, hiltViewModel<LiveViewModel>(), isCompact, play)
        }
        composable<Route.Movies> {
            BrowseScreen(stringResource(R.string.nav_movies), ContentType.MOVIE, hiltViewModel<MoviesViewModel>(), isCompact, openDetails)
        }
        composable<Route.Series> {
            BrowseScreen(stringResource(R.string.nav_series), ContentType.SERIES, hiltViewModel<SeriesViewModel>(), isCompact, openDetails)
        }
        composable<Route.Guide> {
            GuideScreen(onPlayChannel = { c -> nav.navigate(Route.Player(c.playlistId, c.id, ContentType.LIVE.name, c.categoryId)) })
        }
        composable<Route.Search> { SearchScreen(onPlay = play, onOpenDetails = openDetails) }
        composable<Route.Favorites> { FavoritesScreen(onPlay = play, onOpenDetails = openDetails) }
        composable<Route.Settings> { SettingsScreen(onManagePlaylists = { nav.navigate(Route.Playlists) }) }
        composable<Route.Playlists> {
            PlaylistsScreen(onAdd = { nav.navigate(Route.EditPlaylist()) }, onEdit = { nav.navigate(Route.EditPlaylist(it)) })
        }
        composable<Route.EditPlaylist> { EditPlaylistScreen(onDone = { nav.popBackStack() }) }
        composable<Route.MovieDetails> {
            MovieDetailsScreen(onPlay = { m -> nav.navigate(Route.Player(m.playlistId, m.id, ContentType.MOVIE.name)) }, isCompact = isCompact)
        }
        composable<Route.SeriesDetails> {
            SeriesDetailsScreen(onPlayEpisode = { e -> nav.navigate(Route.Player(e.playlistId, e.id, ContentType.SERIES.name)) }, isCompact = isCompact)
        }
        composable<Route.Player>(
            enterTransition = { fadeIn(tween(400)) },
            exitTransition = { fadeOut(tween(300)) },
        ) { PlayerScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun SideRail(selected: NavItem?, onSelect: (Route) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(96.dp).background(Palette.Surface).windowInsetsPadding(WindowInsets.safeDrawing).padding(vertical = 24.dp).zIndex(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.FocusGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.LiveTv, null, tint = Color.White)
        }
        Spacer(Modifier.height(18.dp))
        NavItems.forEach { item -> RailItem(item, item == selected) { onSelect(item.route) } }
    }
}

@Composable
private fun RailItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(80.dp)
            .focusGlow(interaction, shape, focusedScale = 1.06f, borderWidth = 2.dp)
            .clip(shape)
            .background(if (focused) Palette.NeonPurple else if (selected) Palette.SurfaceHighest else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(item.icon, null, tint = if (focused || selected) Color.White else Palette.Muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall, color = if (focused || selected) Color.White else Palette.Muted, maxLines = 1)
    }
}

@Composable
private fun BottomBar(selected: NavItem?, onSelect: (Route) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItems.filter { it.route != Route.Guide && it.route != Route.Favorites }.forEach { item ->
            val interaction = rememberInteractionSource()
            val isSel = item == selected
            Column(
                Modifier.clip(CircleShape)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(item.route) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, null, tint = if (isSel) Palette.NeonPurple else Palette.Muted, modifier = Modifier.size(22.dp))
                Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall, color = if (isSel) Palette.NeonPurple else Palette.Muted)
            }
        }
    }
}

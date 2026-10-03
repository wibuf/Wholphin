package com.github.damontecres.wholphin.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.LibraryMatch
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.OnNow
import com.github.damontecres.wholphin.services.QuickPillsService
import com.github.damontecres.wholphin.services.ResolvedPill
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.tvAccess
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.CollectionType
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import javax.inject.Inject

/** Loads the signed-in user's quick pills for the home page, and does what they're pressed for */
@HiltViewModel
class QuickPillsViewModel
    @Inject
    constructor(
        private val quickPillsService: QuickPillsService,
        private val serverRepository: ServerRepository,
        private val userPreferencesService: UserPreferencesService,
        private val backdropService: BackdropService,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _pills = MutableStateFlow<List<ResolvedPill>>(emptyList())
        val pills: StateFlow<List<ResolvedPill>> = _pills

        /** True once the first load has finished, so the home page knows where to put focus */
        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready

        /**
         * Called whenever the home page comes back. Draws the row straight away from what the
         * device kept, then catches up (config, what's on) in the background.
         */
        fun load() {
            viewModelScope.launchIO {
                try {
                    val hidden =
                        userPreferencesService
                            .getCurrent()
                            .appPreferences.homePagePreferences.hideQuickPills
                    val user = serverRepository.currentUserDto
                    if (hidden || user == null) {
                        _pills.value = emptyList()
                        return@launchIO
                    }
                    if (_pills.value.isEmpty()) {
                        quickPillsService.cached(user.id, user.tvAccess)?.let { _pills.value = it }
                    }
                    if (_pills.value.isNotEmpty()) _ready.value = true
                    // Never hold the page's focus for long on a slow server
                    viewModelScope.launch {
                        delay(READY_TIMEOUT_MS)
                        _ready.value = true
                    }
                    _pills.value = quickPillsService.refresh(user.id, user.tvAccess)
                    _ready.value = true
                    addLibraryArt(user.id)
                } finally {
                    _ready.value = true
                }
            }
        }

        /**
         * Brings the live game pills up to date: called every minute while home is showing, so a
         * game's pill appears when its pregame starts and goes when it ends
         */
        fun refreshLive() {
            viewModelScope.launchIO {
                val user = serverRepository.currentUserDto ?: return@launchIO
                val hidden =
                    userPreferencesService
                        .getCurrent()
                        .appPreferences.homePagePreferences.hideQuickPills
                if (hidden) return@launchIO
                val live = quickPillsService.liveNow(user.id, user.tvAccess)
                _pills.update { pills -> live + pills.filterNot { it is ResolvedPill.Live } }
            }
        }

        private var focusedChannel: UUID? = null

        /**
         * Art and episode details from the library for what's on, looked up only now the row is up,
         * all channels at once, so the home page never waits for them
         */
        private suspend fun addLibraryArt(userId: UUID) {
            coroutineScope {
                _pills.value
                    .filterIsInstance<ResolvedPill.Channel>()
                    .filter { it.onNow != null }
                    .map { pill -> async { pill.channelId to quickPillsService.libraryMatch(userId, pill.onNow!!) } }
                    .awaitAll()
                    .filter { it.second != null }
                    .forEach { (channelId, match) -> applyMatch(channelId, match!!) }
            }
        }

        private suspend fun applyMatch(
            channelId: UUID,
            match: LibraryMatch,
        ) {
            _pills.update { pills ->
                pills.map { pill ->
                    if (pill is ResolvedPill.Channel && pill.channelId == channelId && pill.onNow != null) {
                        pill.copy(
                            onNow =
                                pill.onNow.copy(
                                    imageUrl = match.backdropUrl ?: pill.onNow.imageUrl,
                                    logoUrl = match.logoUrl,
                                    episodeTitle = pill.onNow.episodeTitle ?: match.episodeTitle,
                                    overview = match.overview ?: pill.onNow.overview,
                                ),
                        )
                    } else {
                        pill
                    }
                }
            }
            // Already looking at it: swap the stock art for the real thing
            if (focusedChannel == channelId) {
                match.backdropUrl?.let { backdropService.submit("pill_$channelId", it) }
            }
        }

        fun onFocus(pill: ResolvedPill) {
            if (pill !is ResolvedPill.Channel) focusedChannel = null
            viewModelScope.launch {
                when (pill) {
                    is ResolvedPill.Channel -> {
                        focusedChannel = pill.channelId
                        val art = pill.onNow?.imageUrl ?: pill.artUri
                        if (art != null) backdropService.submit("pill_${pill.channelId}", art) else backdropService.clearBackdrop()
                    }

                    is ResolvedPill.Resume -> {
                        pill.item?.let { backdropService.submit(it) } ?: backdropService.clearBackdrop()
                    }

                    is ResolvedPill.Guide -> {
                        backdropService.submit("pill_guide", pill.artUri)
                    }

                    is ResolvedPill.Live -> {
                        backdropService.submit("pill_live", pill.artUri)
                    }
                }
            }
        }

        fun onClick(pill: ResolvedPill) {
            when (pill) {
                is ResolvedPill.Guide -> {
                    navigationManager.navigateTo(Destination.MediaItem(pill.libraryId, pill.libraryType, CollectionType.LIVETV))
                }

                is ResolvedPill.Channel -> {
                    navigationManager.navigateTo(Destination.Playback(itemId = pill.channelId, positionMs = 0L))
                }

                is ResolvedPill.Live -> {
                    navigationManager.navigateTo(Destination.Playback(itemId = pill.channelId, positionMs = 0L))
                }

                is ResolvedPill.Resume -> {
                    val item = pill.item
                    if (item != null) {
                        navigationManager.navigateTo(Destination.Playback(item))
                    } else {
                        // Pressed before the background lookup finished
                        viewModelScope.launchIO {
                            val user = serverRepository.currentUserDto ?: return@launchIO
                            quickPillsService.lastWatched(user.id)?.let { navigationManager.navigateTo(Destination.Playback(it)) }
                        }
                    }
                }
            }
        }

        private companion object {
            const val READY_TIMEOUT_MS = 1500L
        }
    }

/** The row of pills: a station logo or icon and a short name each */
@Composable
fun QuickPillsRow(
    pills: List<ResolvedPill>,
    onFocusPill: (ResolvedPill) -> Unit,
    onClickPill: (ResolvedPill) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 32.dp, top = 10.dp, bottom = 10.dp),
        modifier = modifier,
    ) {
        // Keyed, so focus stays on the same pill when a live game's pill comes or goes in front of it
        itemsIndexed(pills, key = { index, pill -> rowKey(pills, index, pill) }) { index, pill ->
            val chipModifier =
                (if (index == 0) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocusPill(pill) }
            if (pill is ResolvedPill.Live) {
                LivePillChip(pill = pill, onClick = { onClickPill(pill) }, modifier = chipModifier)
            } else {
                QuickPillChip(pill = pill, onClick = { onClickPill(pill) }, modifier = chipModifier)
            }
        }
    }
}

/** [ResolvedPill.key], made unique if the same pill is in the row twice */
internal fun rowKey(
    pills: List<ResolvedPill>,
    index: Int,
    pill: ResolvedPill,
): String {
    val before = pills.take(index).count { it.key == pill.key }
    return if (before == 0) pill.key else "${pill.key}#$before"
}

private val LiveYellow = Color(0xFFFACC15)

/** A live game: the team's colours, its logo and a yellow LIVE tag (SOON during the pregame) */
@Composable
private fun LivePillChip(
    pill: ResolvedPill.Live,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = Color.White,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.5.dp, LiveYellow.copy(alpha = 0.55f)), shape = CircleShape),
                focusedBorder = Border(BorderStroke(3.dp, LiveYellow), shape = CircleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LiveYellow.copy(alpha = 0.6f), 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        modifier = modifier.height(52.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(listOf(Color(pill.colors.background), Color(pill.colors.backgroundEnd))),
                    ).padding(start = 8.dp, end = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(width = 56.dp, height = 36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(pill.colors.tile))
                        .padding(3.dp),
            ) {
                if (pill.logoUrl != null) {
                    AsyncImage(
                        model = pill.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(pill.number, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = pill.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = if (pill.isLive) "LIVE" else "SOON",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF111111),
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(LiveYellow)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun QuickPillChip(
    pill: ResolvedPill,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.10f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = Color.White,
                focusedContentColor = Color(0xFF0D1018),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        modifier = modifier.height(52.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxHeight().padding(start = 8.dp, end = 20.dp),
        ) {
            when (pill) {
                is ResolvedPill.Channel -> {
                    StationLogo(pill, Modifier.size(width = 56.dp, height = 36.dp))
                }

                is ResolvedPill.Guide -> {
                    PillIcon(Icons.Default.DateRange, Color(0xFF3B82F6))
                }

                is ResolvedPill.Resume -> {
                    PillIcon(Icons.Default.PlayArrow, Color(0xFF16A34A))
                }

                is ResolvedPill.Live -> {}
            }
            Text(
                text = pill.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PillIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** The home header while a pill is selected: what's on now for a channel, else a short line */
@Composable
fun QuickPillHeader(
    pill: ResolvedPill,
    allPills: List<ResolvedPill>,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    val now = LocalDateTime.now()
    when (pill) {
        is ResolvedPill.Live -> {
            LivePillHeader(pill, now, modifier)
        }

        is ResolvedPill.Resume -> {
            val item = pill.item
            if (item != null) {
                HomePageHeader(item = item, showLogo = showLogo, modifier = modifier)
            } else {
                TitleOrLogo(title = pill.label, logoImageUrl = null, showLogo = false, modifier = modifier)
            }
        }

        is ResolvedPill.Guide -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
                TitleOrLogo(title = "TV Guide", logoImageUrl = null, showLogo = false, modifier = Modifier.fillMaxWidth(.75f))
                Text(
                    text = "See what's on every channel right now, and pick something to watch.",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(520.dp),
                )
            }
        }

        is ResolvedPill.Channel -> {
            val onNow = pill.onNow
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StationLogo(pill, Modifier.size(width = 64.dp, height = 38.dp))
                    Text(
                        text = if (onNow != null) "ON NOW · ${pill.label}" else pill.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFCA5A5),
                    )
                }
                TitleOrLogo(
                    title = onNow?.title ?: pill.channelName.ifBlank { pill.label },
                    logoImageUrl = onNow?.logoUrl,
                    showLogo = showLogo && onNow?.logoUrl != null,
                    modifier = Modifier.fillMaxWidth(.75f),
                )
                Text(
                    text = if (onNow != null) channelMeta(pill, onNow, now) else "Channel ${pill.number}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val progress = onNow?.let { progress(it, now) }
                if (progress != null) {
                    Box(
                        Modifier
                            .width(220.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(Color(0xFF60A5FA)),
                        )
                    }
                }
                val overview = onNow?.overview
                if (!overview.isNullOrBlank()) {
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(520.dp),
                    )
                }
            }
        }
    }
}

/** "LIVE NOW · BRUINS", the matchup, network and time, and how far into the game */
@Composable
private fun LivePillHeader(
    pill: ResolvedPill.Live,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (pill.isLive) Color(0xFFEF4444) else LiveYellow),
            )
            Text(
                text = liveEyebrow(pill, now),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (pill.isLive) Color(0xFFFCA5A5) else LiveYellow,
            )
        }
        TitleOrLogo(title = pill.title, logoImageUrl = null, showLogo = false, modifier = Modifier.fillMaxWidth(.75f))
        Text(
            text = liveMeta(pill, now),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pill.isLive) {
            val progress =
                progress(
                    OnNow(pill.title, episodeTitle = null, overview = null, start = pill.start, end = pill.end, imageUrl = null),
                    now,
                )
            if (progress != null) {
                Box(
                    Modifier
                        .width(220.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(LiveYellow),
                    )
                }
            }
        } else if (pill.onNow.isNotBlank() && pill.onNow != pill.title) {
            Text(
                text = "On now: ${pill.onNow}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(520.dp),
            )
        }
    }
}

/** "LIVE NOW · BRUINS", or before the game "PREGAME · BRUINS · STARTS IN 25 MIN" */
internal fun liveEyebrow(
    pill: ResolvedPill.Live,
    now: LocalDateTime,
): String {
    val team = pill.label.uppercase()
    if (pill.isLive) return "LIVE NOW · $team"
    val minutes = Duration.between(now, pill.start).toMinutes()
    return if (minutes >= 1) "PREGAME · $team · STARTS IN $minutes MIN" else "PREGAME · $team · STARTING NOW"
}

/** "NHL Hockey · NESN · Ch 1002 · 8:00 – 10:30 PM · 45 min left" */
internal fun liveMeta(
    pill: ResolvedPill.Live,
    now: LocalDateTime,
): String {
    val left =
        Duration
            .between(now, pill.end)
            .toMinutes()
            .takeIf { pill.isLive && it >= 0 }
            ?.let { "$it min left" }
    return listOfNotNull(
        pill.subtitle.ifBlank { null },
        "Ch ${pill.number}",
        "${TIME.format(pill.start)} – ${TIME.format(pill.end)}",
        left,
    ).joinToString(" · ")
}

/** A channel's logo on a dark tile, as in the guide; white logos vanish on a light one */
@Composable
private fun StationLogo(
    channel: ResolvedPill.Channel,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0D1018))
                .padding(3.dp),
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(channel.number, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "7 News · 5:00 – 5:30 PM · 12 min left" (the SDK gives programme times in local time) */
internal fun channelMeta(
    pill: ResolvedPill.Channel,
    onNow: OnNow,
    now: LocalDateTime,
): String {
    val times =
        if (onNow.start != null && onNow.end != null) "${TIME.format(onNow.start)} – ${TIME.format(onNow.end)}" else null
    val left =
        onNow.end
            ?.let { Duration.between(now, it).toMinutes() }
            ?.takeIf { it >= 0 }
            ?.let { "$it min left" }
    val seasonEpisode =
        if (onNow.season != null && onNow.episode != null) "S${onNow.season} E${onNow.episode}" else null
    return listOfNotNull(
        pill.channelName.ifBlank { null },
        seasonEpisode,
        onNow.episodeTitle,
        "New".takeIf { onNow.isNew },
        times,
        left,
    ).joinToString(" · ")
}

internal fun progress(
    onNow: OnNow,
    now: LocalDateTime,
): Float? {
    val start = onNow.start ?: return null
    val end = onNow.end ?: return null
    val total = Duration.between(start, end).toMillis().takeIf { it > 0 } ?: return null
    return (Duration.between(start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
}

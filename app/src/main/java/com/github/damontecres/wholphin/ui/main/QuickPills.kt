package com.github.damontecres.wholphin.ui.main

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.BackdropService
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.CollectionType
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

        /** Called whenever the home page comes back, since what's on will have moved on */
        fun load() {
            viewModelScope.launchIO {
                val hidden =
                    userPreferencesService
                        .getCurrent()
                        .appPreferences.homePagePreferences.hideQuickPills
                val user = serverRepository.currentUserDto
                if (hidden || user == null) {
                    _pills.value = emptyList()
                    return@launchIO
                }
                val config = quickPillsService.configFor(user.id)
                _pills.value = quickPillsService.resolve(user.id, user.tvAccess, config)
            }
        }

        fun onFocus(pill: ResolvedPill) {
            viewModelScope.launch {
                when (pill) {
                    is ResolvedPill.Channel -> {
                        val url = pill.onNow?.imageUrl
                        if (url != null) backdropService.submit("pill_${pill.channelId}", url) else backdropService.clearBackdrop()
                    }

                    is ResolvedPill.Resume -> {
                        backdropService.submit(pill.item)
                    }

                    is ResolvedPill.Guide -> {
                        backdropService.clearBackdrop()
                    }
                }
            }
        }

        fun onClick(pill: ResolvedPill) {
            navigationManager.navigateTo(
                when (pill) {
                    is ResolvedPill.Guide -> Destination.MediaItem(pill.libraryId, pill.libraryType, CollectionType.LIVETV)
                    is ResolvedPill.Channel -> Destination.Playback(itemId = pill.channelId, positionMs = 0L)
                    is ResolvedPill.Resume -> Destination.Playback(pill.item)
                },
            )
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
        itemsIndexed(pills) { index, pill ->
            QuickPillChip(
                pill = pill,
                onClick = { onClickPill(pill) },
                modifier =
                    (if (index == 0) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocusPill(pill) },
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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(width = 56.dp, height = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
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
                            Text(pill.number, color = Color(0xFF0D1018), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                is ResolvedPill.Guide -> {
                    PillIcon(Icons.Default.DateRange, Color(0xFF3B82F6))
                }

                is ResolvedPill.Resume -> {
                    PillIcon(Icons.Default.PlayArrow, Color(0xFF16A34A))
                }
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
    showLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    when (pill) {
        is ResolvedPill.Resume -> {
            HomePageHeader(item = pill.item, showLogo = showLogo, modifier = modifier)
        }

        is ResolvedPill.Guide -> {
            HeaderText(
                eyebrow = null,
                title = "TV Guide",
                meta = "See what's on every channel",
                overview = null,
                progress = null,
                modifier = modifier,
            )
        }

        is ResolvedPill.Channel -> {
            val onNow = pill.onNow
            if (onNow == null) {
                HeaderText(
                    eyebrow = pill.label,
                    title = pill.channelName,
                    meta = "Channel ${pill.number}",
                    overview = null,
                    progress = null,
                    modifier = modifier,
                )
            } else {
                HeaderText(
                    eyebrow = "ON NOW · ${pill.label}",
                    title = onNow.title,
                    meta = channelMeta(pill, onNow, LocalDateTime.now()),
                    overview = onNow.overview,
                    progress = progress(onNow, LocalDateTime.now()),
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun HeaderText(
    eyebrow: String?,
    title: String,
    meta: String,
    overview: String?,
    progress: Float?,
    modifier: Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFCA5A5),
            )
        }
        TitleOrLogo(title = title, logoImageUrl = null, showLogo = false, modifier = Modifier.fillMaxWidth(.75f))
        Text(text = meta, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    return listOfNotNull(pill.channelName.ifBlank { null }, onNow.episodeTitle, times, left).joinToString(" · ")
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

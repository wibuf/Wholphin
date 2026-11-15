package com.github.damontecres.wholphin.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.DiscoverItemCard
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Fork-only (GooseFlix #1636): the home page's "My requests" row, the user's own Seerr requests as
 * Discover cards. Selecting one opens its Discover page, which plays it if it's in the library.
 */
@Composable
fun RequestsHomeRow(
    row: HomeRowLoadingState.Requests,
    onFocusPosition: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RequestsRowViewModel = hiltViewModel(),
) {
    ItemRow(
        title = row.title.getString(),
        items = row.items,
        onClickItem = { _, item -> viewModel.open(item) },
        onLongClickItem = { _, _ -> },
        modifier = modifier,
        horizontalPadding = row.viewOptions.spacing.dp,
        cardContent = { index, item, cardModifier, onClick, onLongClick ->
            DiscoverItemCard(
                item = item,
                onClick = onClick,
                onLongClick = onLongClick,
                showOverlay = true,
                modifier =
                    cardModifier.onFocusChanged {
                        if (it.isFocused) {
                            onFocusPosition(index)
                            item?.let(viewModel::showBackdrop)
                        }
                    },
            )
        },
    )
}

/** The home header while a request is focused: its title or logo, what it is, and its status */
@Composable
fun RequestHomeHeader(
    item: DiscoverItem,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        TitleOrLogo(
            title = item.title.orEmpty(),
            logoImageUrl = item.logoUrl,
            showLogo = item.logoUrl != null,
            modifier = Modifier.fillMaxWidth(.75f),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(.6f),
        ) {
            Text(
                text = requestFacts(item),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val overviewModifier = Modifier.height(60.dp).width(400.dp)
            val overview = item.overview
            if (!overview.isNullOrBlank()) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = overviewModifier,
                )
            } else {
                Spacer(overviewModifier)
            }
        }
    }
}

/** "2024 · Movie · Requested", "Show · Available" */
internal fun requestFacts(item: DiscoverItem): String =
    listOfNotNull(
        item.releaseDate?.year?.toString(),
        when (item.type) {
            SeerrItemType.MOVIE -> "Movie"
            SeerrItemType.TV -> "Show"
            else -> null
        },
        when (item.availability) {
            SeerrAvailability.AVAILABLE -> "Available"
            SeerrAvailability.PARTIALLY_AVAILABLE -> "Partly available"
            SeerrAvailability.PROCESSING -> "Downloading"
            SeerrAvailability.PENDING -> "Requested"
            else -> "Requested"
        },
    ).joinToString(" · ")

@HiltViewModel
class RequestsRowViewModel
    @Inject
    constructor(
        private val navigationManager: NavigationManager,
        private val backdropService: BackdropService,
    ) : ViewModel() {
        fun open(item: DiscoverItem) {
            navigationManager.navigateTo(Destination.DiscoveredItem(item))
        }

        fun showBackdrop(item: DiscoverItem) {
            viewModelScope.launchIO {
                backdropService.submit("discover_${item.id}", item.backDropUrl)
            }
        }
    }

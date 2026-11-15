package com.github.damontecres.wholphin.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.github.damontecres.wholphin.services.MyRequests
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.DiscoverItemCard
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
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
    val removed by MyRequests.removed.collectAsState()
    val entries = remember(row, removed) { row.visible(removed) }
    // Long press: open it, or take it off the list (like a library card's long-press menu)
    var menuFor by remember { mutableStateOf<MyRequests.Entry?>(null) }
    menuFor?.let { entry ->
        DialogPopup(
            params =
                DialogParams(
                    fromLongClick = true,
                    title = entry.item.title.orEmpty(),
                    items =
                        listOf(
                            DialogItem(headlineContent = { Text("Open") }, onClick = { viewModel.open(entry.item) }),
                            DialogItem(
                                headlineContent = { Text("Remove from My requests") },
                                supportingContent = { Text("The title stays in the library if it's there") },
                                onClick = { viewModel.remove(entry.requestId) },
                            ),
                        ),
                ),
            onDismissRequest = { menuFor = null },
        )
    }
    ItemRow(
        title = row.title.getString(),
        items = entries.map { it.item },
        onClickItem = { _, item -> viewModel.open(item) },
        onLongClickItem = { index, _ -> menuFor = entries.getOrNull(index) },
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
        private val myRequests: MyRequests,
    ) : ViewModel() {
        fun open(item: DiscoverItem) {
            navigationManager.navigateTo(Destination.DiscoveredItem(item))
        }

        fun remove(requestId: Int) {
            viewModelScope.launchIO {
                try {
                    myRequests.remove(requestId)
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    timber.log.Timber.w(ex, "Could not remove request %s", requestId)
                }
            }
        }

        fun showBackdrop(item: DiscoverItem) {
            viewModelScope.launchIO {
                backdropService.submit("discover_${item.id}", item.backDropUrl)
            }
        }
    }

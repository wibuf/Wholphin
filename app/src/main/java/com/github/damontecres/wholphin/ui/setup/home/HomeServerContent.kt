package com.github.damontecres.wholphin.ui.setup.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.setup.SwitchServerContent
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState

/**
 * Stands in for the server list when the build has a [HomeServer]: connects to it and goes
 * straight on to signing in. If the server can't be reached it says so, with a retry, and
 * keeps the normal server list one press away.
 */
@Composable
fun HomeServerContent(
    modifier: Modifier = Modifier,
    viewModel: HomeServerViewModel = hiltViewModel(),
) {
    var otherServer by remember { mutableStateOf(false) }
    if (otherServer) {
        SwitchServerContent(modifier)
        return
    }

    LaunchedEffect(Unit) { viewModel.connect() }
    val state by viewModel.state.collectAsState()

    Box(
        modifier = modifier.background(HomeBrand.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            HomeLogo(gooseHeight = 110.dp, wordmarkWidth = 280.dp)
            when (val s = state) {
                is LoadingState.Error -> {
                    val retryFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { retryFocus.tryRequestFocus() }
                    Text(
                        text = "Can't reach ${stringResource(R.string.app_name)} right now",
                        style = MaterialTheme.typography.titleLarge,
                        color = HomeBrand.text,
                    )
                    Text(
                        text = "Check this TV is online, then try again. (${s.message ?: HomeServer.url})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeBrand.muted,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { viewModel.connect() },
                            modifier = Modifier.focusRequester(retryFocus),
                        ) {
                            Text("Try again")
                        }
                        Button(onClick = { otherServer = true }) {
                            Text("Use a different server")
                        }
                    }
                }

                else -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(32.dp),
                    ) {
                        CircularProgress(Modifier.size(20.dp))
                        Text(
                            text = "Connecting…",
                            style = MaterialTheme.typography.titleMedium,
                            color = HomeBrand.subtext,
                        )
                    }
                }
            }
        }
    }
}

/** The goose, centred over the GOOSEFLIX wordmark */
@Composable
internal fun HomeLogo(
    gooseHeight: Dp,
    wordmarkWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.padding(4.dp),
    ) {
        val goose = painterResource(R.drawable.sign_in_goose)
        Image(
            painter = goose,
            contentDescription = null,
            modifier = Modifier.height(gooseHeight).aspectRatio(goose.aspect()),
        )
        val wordmark = painterResource(R.drawable.sign_in_wordmark)
        Image(
            painter = wordmark,
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.width(wordmarkWidth).aspectRatio(wordmark.aspect()),
        )
    }
}

private fun Painter.aspect(): Float = intrinsicSize.let { if (it.isSpecified && it.height > 0f) it.width / it.height else 1f }

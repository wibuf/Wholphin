package com.github.damontecres.wholphin.ui.setup.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.setup.JellyfinUserAndImage
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.SwitchUserResult
import com.github.damontecres.wholphin.ui.setup.SwitchUserState
import com.github.damontecres.wholphin.ui.setup.SwitchUserViewModel
import com.github.damontecres.wholphin.ui.setup.UserList
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Jellyfin forgets a Quick Connect code after 10 minutes, so a fresh one is shown before then
 */
private val QUICK_CONNECT_REFRESH = 9.minutes

/** Who the sign-in panel is for: someone already on this device, or null for anyone */
private data class SignInFor(
    val user: JellyfinUser?,
)

/**
 * Stands in for the user list when the build has a [HomeServer].
 *
 * People who have signed in on this device are offered as profiles. Otherwise, or when adding
 * someone, it shows the sign-in panel: username and password beside a Quick Connect code that
 * is fetched straight away, so either way works without first picking one. The server's
 * public user list is never shown, so a server reachable from the internet doesn't list its
 * users to anyone who installs the app.
 */
@Composable
fun HomeSignInContent(
    server: JellyfinServer,
    modifier: Modifier = Modifier,
    viewModel: SwitchUserViewModel =
        hiltViewModel<SwitchUserViewModel, SwitchUserViewModel.Factory>(
            creationCallback = { it.create(server) },
        ),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.init() }

    val state by viewModel.state.collectAsState()
    val currentUser by viewModel.serverRepository.currentUserFlow.collectAsState(null)
    val profiles = remember(state.users) { state.users.filter { it.known } }

    var signIn by remember { mutableStateOf<SignInFor?>(null) }
    var pinUser by remember { mutableStateOf<JellyfinUser?>(null) }

    fun switchTo(user: JellyfinUser) {
        val result = viewModel.trySwitchUser(user)
        scope.launch {
            val r = result.await()
            if (r is SwitchUserResult.Error) {
                Toast.makeText(context, r.errorMessage, Toast.LENGTH_LONG).show()
                if (r.showLogin) signIn = SignInFor(user)
            }
        }
    }

    Box(modifier = modifier.background(HomeBrand.background)) {
        when (val loading = state.loading) {
            is LoadingState.Error -> {
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { retryFocus.tryRequestFocus() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    HomeLogo(gooseHeight = 96.dp, wordmarkWidth = 240.dp)
                    Text(
                        text = "Can't reach ${stringResource(R.string.app_name)} right now",
                        style = MaterialTheme.typography.titleLarge,
                        color = HomeBrand.text,
                    )
                    Text(
                        text = loading.message ?: loading.exception?.localizedMessage ?: server.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeBrand.muted,
                    )
                    Button(
                        onClick = { viewModel.init() },
                        modifier = Modifier.focusRequester(retryFocus),
                    ) {
                        Text("Try again")
                    }
                }
            }

            LoadingState.Loading,
            LoadingState.Pending,
            -> {
                LoadingPage(Modifier.fillMaxSize())
            }

            LoadingState.Success -> {
                val request = signIn
                if (request != null || profiles.isEmpty()) {
                    BackHandler(enabled = request != null && profiles.isNotEmpty()) {
                        viewModel.cancelQuickConnect()
                        signIn = null
                    }
                    val existingUser = request?.user
                    SignInPanel(
                        existingUser = existingUser,
                        state = state,
                        onStartQuickConnect = { viewModel.initiateQuickConnect(server, existingUser) },
                        onStopQuickConnect = { viewModel.cancelQuickConnect() },
                        onLogin = { username, password ->
                            viewModel.clearSwitchUserState()
                            viewModel.login(server, existingUser, username, password)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ProfilesPanel(
                        profiles = profiles,
                        currentUser = currentUser,
                        onSelect = { user ->
                            if (user.accessToken == null || user.requireLogin) {
                                signIn = SignInFor(user)
                            } else if (user.hasPin) {
                                pinUser = user
                            } else {
                                switchTo(user)
                            }
                        },
                        onAdd = { signIn = SignInFor(null) },
                        onRemove = { viewModel.removeUser(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    pinUser?.let { user ->
        PinEntryDialog(
            onDismissRequest = { pinUser = null },
            onClickServerAuth = {
                pinUser = null
                signIn = SignInFor(user)
            },
            onTextChange = {
                if (it == user.pin) {
                    pinUser = null
                    switchTo(user)
                }
            },
        )
    }
}

/** "Who's watching?": the people who have signed in on this device */
@Composable
internal fun ProfilesPanel(
    profiles: List<JellyfinUserAndImage>,
    currentUser: JellyfinUser?,
    onSelect: (JellyfinUser) -> Unit,
    onAdd: () -> Unit,
    onRemove: (JellyfinUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        HomeLogo(
            gooseHeight = 56.dp,
            wordmarkWidth = 140.dp,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 40.dp, top = 28.dp),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
        ) {
            Text(
                text = "Who's watching?",
                style = MaterialTheme.typography.displaySmall,
                color = HomeBrand.text,
            )
            UserList(
                users = profiles,
                currentUser = currentUser,
                onSwitchUser = onSelect,
                onAddUser = onAdd,
                onRemoveUser = onRemove,
                onSwitchServer = {},
                showSwitchServer = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = "Only people who have signed in on this TV are shown. Hold OK on a profile to remove it.",
            style = MaterialTheme.typography.bodySmall,
            color = HomeBrand.muted,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
        )
    }
}

/**
 * Username and password beside a Quick Connect code. Talks to the server only through the
 * callbacks, so it can be drawn without one.
 */
@Composable
internal fun SignInPanel(
    existingUser: JellyfinUser?,
    state: SwitchUserState,
    onStartQuickConnect: () -> Unit,
    onStopQuickConnect: () -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by remember(existingUser) { mutableStateOf(existingUser?.name ?: "") }
    var password by remember(existingUser) { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // Quick Connect runs for as long as this panel is up, with a fresh code before the old one
    // expires. A password attempt stops it (in the view model), so a failed one restarts it.
    var quickConnectRound by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.quickConnectEnabled, quickConnectRound) {
        if (!state.quickConnectEnabled) return@LaunchedEffect
        while (true) {
            onStartQuickConnect()
            delay(QUICK_CONNECT_REFRESH)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onStopQuickConnect() }
    }
    LaunchedEffect(state.switchUserState) {
        val s = state.switchUserState
        if (submitting && s is LoadingState.Error) {
            submitting = false
            passwordError = s.message ?: s.exception?.localizedMessage ?: "Couldn't sign in"
            quickConnectRound++
        }
    }

    fun submit() {
        if (username.isBlank() || submitting) return
        passwordError = null
        submitting = true
        onLogin(username.trim(), password)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        modifier = modifier.padding(horizontal = 56.dp, vertical = 44.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(0.36f),
        ) {
            HomeLogo(gooseHeight = 96.dp, wordmarkWidth = 240.dp)
            Text(
                text = "Sign in to start watching",
                style = MaterialTheme.typography.headlineSmall,
                color = HomeBrand.text,
            )
            Text(
                text =
                    "Use your ${stringResource(R.string.app_name)} username and password, " +
                        "or sign in from your phone with a code. No server setup needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = HomeBrand.subtext,
            )
            Text(
                text = "Trouble signing in? Ask whoever invited you for a password reset.",
                style = MaterialTheme.typography.bodySmall,
                color = HomeBrand.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Row(
            modifier =
                Modifier
                    .weight(0.64f)
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(20.dp))
                    .background(HomeBrand.slate.copy(alpha = 0.85f))
                    .padding(28.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                val usernameFocus = remember { FocusRequester() }
                val passwordFocus = remember { FocusRequester() }
                LaunchedEffect(existingUser) {
                    if (username.isBlank()) usernameFocus.tryRequestFocus() else passwordFocus.tryRequestFocus()
                }
                Heading("Sign in", "With your username and password")
                FieldLabel("Username")
                EditTextBox(
                    value = username,
                    onValueChange = {
                        username = it
                        passwordError = null
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                        ),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.tryRequestFocus() }),
                    isInputValid = { passwordError == null },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(usernameFocus),
                )
                FieldLabel("Password")
                EditTextBox(
                    value = password,
                    onValueChange = {
                        password = it
                        passwordError = null
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    isInputValid = { passwordError == null },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocus),
                )
                passwordError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { submit() },
                    enabled = username.isNotBlank() && !submitting,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (submitting) {
                            CircularProgress(Modifier.size(18.dp))
                        } else {
                            Text("Sign in")
                        }
                    }
                }
            }

            if (state.quickConnectEnabled) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            .width(48.dp)
                            .fillMaxHeight(),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.12f)),
                    )
                    Text("or", style = MaterialTheme.typography.bodySmall, color = HomeBrand.muted)
                    Box(
                        Modifier
                            .weight(1f)
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.12f)),
                    )
                }
                QuickConnectColumn(
                    code = state.quickConnectStatus?.code,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuickConnectColumn(
    code: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Heading("Use your phone", "Already signed in on your phone or computer? Approve this TV there.")
        Text(
            text = code?.let(::formatQuickConnectCode) ?: "••• •••",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
            maxLines = 1,
            softWrap = false,
            color = if (code != null) Color.White else HomeBrand.muted,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "1. Scan the QR code, or go to ${HomeServer.codePage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeBrand.subtext,
                )
                Text(
                    text = "2. Check the code matches and tap Authorize",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeBrand.subtext,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(7.dp),
            ) {
                QrCode(
                    content = HomeServer.quickConnectLink(code),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(HomeBrand.lightBlue),
            )
            Text(
                text =
                    if (code != null) {
                        "Waiting for approval. This screen signs in by itself."
                    } else {
                        "Getting a code…"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = HomeBrand.muted,
            )
        }
    }
}

@Composable
private fun ColumnScope.Heading(
    title: String,
    subtitle: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = HomeBrand.text,
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = HomeBrand.subtext,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = HomeBrand.subtext,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/** "482913" as "482 913", which is easier to read off a TV and type on a phone */
internal fun formatQuickConnectCode(code: String): String =
    code.trim().let { if (it.length == 6 && it.all(Char::isDigit)) "${it.take(3)} ${it.drop(3)}" else it }

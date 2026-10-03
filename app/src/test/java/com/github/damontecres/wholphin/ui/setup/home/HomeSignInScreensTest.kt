package com.github.damontecres.wholphin.ui.setup.home

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.test.TestActivity
import com.github.damontecres.wholphin.ui.setup.JellyfinUserAndImage
import com.github.damontecres.wholphin.ui.setup.SwitchUserState
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * Draws the GooseFlix sign-in screens as a 1080p TV would, checks the key text is there, and
 * saves each one to app/build/screenshots so the layout can be looked at without a device.
 */
@HiltAndroidTest
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@RunWith(RobolectricTestRunner::class)
class HomeSignInScreensTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val registerActivity =
        object : TestWatcher() {
            override fun starting(description: Description?) {
                val app: Application = ApplicationProvider.getApplicationContext()
                val info =
                    ActivityInfo().apply {
                        name = TestActivity::class.java.name
                        packageName = app.packageName
                    }
                shadowOf(app.packageManager).addOrUpdateActivity(info)
            }
        }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<TestActivity>()

    private val serverId = UUID.randomUUID()

    private fun user(name: String) =
        JellyfinUserAndImage(
            user = JellyfinUser(id = UUID.randomUUID(), name = name, serverId = serverId, accessToken = "token"),
            imageUrl = null,
            known = true,
        )

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.BLUE) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(HomeBrand.background),
                ) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `sign in shows password and a ready Quick Connect code`() {
        var quickConnectStarts = 0
        show {
            SignInPanel(
                existingUser = null,
                state =
                    SwitchUserState(
                        loading = LoadingState.Success,
                        quickConnectEnabled = true,
                        quickConnectStatus =
                            QuickConnectResult(
                                authenticated = false,
                                secret = "secret",
                                code = "482913",
                                deviceId = "tv",
                                deviceName = "Shield",
                                appName = "GooseFlix",
                                appVersion = "1",
                                dateAdded = java.time.LocalDateTime.now(),
                            ),
                    ),
                onStartQuickConnect = { quickConnectStarts++ },
                onStopQuickConnect = {},
                onLogin = { _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onNodeWithText("482 913").assertIsDisplayed()
        compose.onNodeWithText("Sign in to start watching").assertIsDisplayed()
        compose.onNodeWithText("Use your phone").assertIsDisplayed()
        assertEquals("a code is requested as soon as the screen opens", 1, quickConnectStarts)
        save("sign_in")
    }

    @Test
    fun `without Quick Connect only the password half shows`() {
        show {
            SignInPanel(
                existingUser = null,
                state = SwitchUserState(loading = LoadingState.Success, quickConnectEnabled = false),
                onStartQuickConnect = {},
                onStopQuickConnect = {},
                onLogin = { _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onNodeWithText("Use your phone").assertDoesNotExist()
        save("sign_in_no_quick_connect")
    }

    @Test
    fun `profiles list the people signed in on this device`() {
        show {
            ProfilesPanel(
                profiles = listOf(user("Scott"), user("Nicky"), user("Kids")),
                currentUser = null,
                onSelect = {},
                onAdd = {},
                onRemove = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onNodeWithText("Who's watching?").assertIsDisplayed()
        compose.onNodeWithText("Nicky").assertIsDisplayed()
        save("profiles")
    }
}

class HomeServerTest {
    @Test
    fun `codes read in two groups of three`() {
        assertEquals("482 913", formatQuickConnectCode("482913"))
        assertEquals("482 913", formatQuickConnectCode(" 482913 "))
        assertEquals("ABC", formatQuickConnectCode("ABC"))
    }

    @Test
    fun `the phone page sits on the server's own host`() {
        assertEquals("jelly.grit.bot/code", HomeServer.codePageFor("https://jelly.grit.bot"))
        assertEquals("jelly.grit.bot/code", HomeServer.codePageFor("https://jelly.grit.bot/"))
        assertEquals("10.0.0.5:8096/code", HomeServer.codePageFor("http://10.0.0.5:8096"))
    }

    @Test
    fun `the QR code opens Jellyfin's Quick Connect page with the code filled in`() {
        assertEquals(
            "https://jelly.grit.bot/web/#/quickconnect?code=482913",
            HomeServer.quickConnectLinkFor("https://jelly.grit.bot/", "482 913"),
        )
        assertEquals(
            "https://jelly.grit.bot/web/#/quickconnect",
            HomeServer.quickConnectLinkFor("https://jelly.grit.bot", null),
        )
        val modules = qrModules("https://jelly.grit.bot/web/#/quickconnect?code=482913")
        assertTrue("a real QR code is at least 21 modules across", modules != null && modules.width >= 21)
    }
}

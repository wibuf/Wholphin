package com.github.damontecres.wholphin.ui.main

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.LiveColors
import com.github.damontecres.wholphin.services.ResolvedPill
import com.github.damontecres.wholphin.test.TestActivity
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.jellyfin.sdk.model.api.BaseItemKind
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
import java.time.LocalDateTime
import java.util.UUID

/** Draws the pill row with a live game in front, as a 1080p TV would, into app/build/screenshots */
@HiltAndroidTest
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@RunWith(RobolectricTestRunner::class)
class QuickPillsScreensTest {
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

    private val now = LocalDateTime.now()

    private fun live(
        label: String,
        isLive: Boolean,
        colors: LiveColors,
    ) = ResolvedPill.Live(
        label = label,
        number = "1002",
        channelId = UUID.randomUUID(),
        isLive = isLive,
        title = if (label == "Bruins") "Bruins at Wild" else "Patriots at Bills",
        subtitle = if (label == "Bruins") "NHL Hockey · NESN" else "NFL Football · CBS (WBZ)",
        start = if (isLive) now.minusMinutes(50) else now.plusMinutes(25),
        end = if (isLive) now.plusMinutes(100) else now.plusMinutes(230),
        onNow = "Patriots Pregame Live",
        colors = colors,
        logoUrl = null,
        artUri = "",
    )

    private fun row(first: ResolvedPill.Live) =
        listOf(
            first,
            ResolvedPill.Guide("Guide", UUID.randomUUID(), BaseItemKind.USER_VIEW, ""),
            ResolvedPill.Channel("7 News", "807", UUID.randomUUID(), "WHDH", null, null),
            ResolvedPill.Channel("Hallmark", "907", UUID.randomUUID(), "Hallmark", null, null),
            ResolvedPill.Resume("Resume", null),
        )

    private fun show(pills: List<ResolvedPill>) {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.BLUE) {
                val focus = remember { FocusRequester() }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Column(verticalArrangement = Arrangement.spacedBy(40.dp), modifier = Modifier.padding(start = 60.dp, top = 30.dp)) {
                        QuickPillHeader(pills.first(), pills, showLogo = false)
                        QuickPillsRow(pills = pills, onFocusPill = {}, onClickPill = {}, focusRequester = focus)
                    }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
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
    fun `a live game leads the row`() {
        show(row(live("Bruins", true, LiveColors(0xFF111111, 0xFF3A3A3A, 0xFFFFB81C))))
        compose.onNodeWithText("LIVE NOW · BRUINS").assertIsDisplayed()
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        save("pills_live")
    }

    @Test
    fun `pregame says soon`() {
        show(row(live("Patriots", false, LiveColors.DEFAULT)))
        compose.onNodeWithText("SOON").assertIsDisplayed()
        compose.onNodeWithText("On now: Patriots Pregame Live").assertIsDisplayed()
        save("pills_pregame")
    }
}

package com.github.damontecres.wholphin.services.update

import com.github.damontecres.wholphin.services.getDownloadUrl
import com.github.damontecres.wholphin.services.releaseVersion
import com.github.damontecres.wholphin.util.Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkUpdatesTest {
    private val feed = "https://add.beefmc.com/update"
    private val hour = 3_600_000L

    @Test
    fun `old installs on upstream's feed move to the fork's`() {
        assertEquals(feed, forkUpdateUrlMigration(UPSTREAM_UPDATE_URL, feed))
        assertEquals(feed, forkUpdateUrlMigration("", feed))
    }

    @Test
    fun `installs pinned to one branch's GitHub release move too`() {
        val branch = "https://api.github.com/repos/wibuf/Wholphin/releases/tags/claude-tender-johnson-wqvwci"
        assertEquals(feed, forkUpdateUrlMigration(branch, feed))
    }

    @Test
    fun `a URL someone chose on purpose is left alone`() {
        assertNull(forkUpdateUrlMigration(feed, feed))
        assertNull(forkUpdateUrlMigration("https://example.com/my-feed", feed))
    }

    @Test
    fun `nothing installs over something playing`() {
        assertEquals(InstallAction.WAIT, decideInstall(true, false, busy = true, null, 12 * hour))
        assertEquals(InstallAction.WAIT, decideInstall(false, true, busy = true, null, 12 * hour))
    }

    @Test
    fun `newer Android installs silently, but only off screen`() {
        assertEquals(InstallAction.SILENT, decideInstall(true, inForeground = false, false, null, 12 * hour))
        assertEquals(InstallAction.WAIT, decideInstall(true, inForeground = true, false, null, 12 * hour))
    }

    @Test
    fun `older Android asks on screen and waits off screen`() {
        assertEquals(InstallAction.PROMPT, decideInstall(false, inForeground = true, false, null, 12 * hour))
        assertEquals(InstallAction.WAIT, decideInstall(false, inForeground = false, false, null, 12 * hour))
    }

    @Test
    fun `not now is respected until the interval passes`() {
        assertEquals(InstallAction.WAIT, decideInstall(false, true, false, sinceLastPromptMs = hour, 12 * hour))
        assertEquals(InstallAction.PROMPT, decideInstall(false, true, false, sinceLastPromptMs = 12 * hour, 12 * hour))
    }

    @Test
    fun `fork release titles carry the branch after the version`() {
        assertEquals(
            Version(1, 0, 7, 83, "89fac169"),
            releaseVersion("v1.0.7-83-g89fac169 (claude/tender-johnson-wqvwci)"),
        )
        assertEquals(Version(1, 0, 9), releaseVersion("v1.0.9"))
        assertNull(releaseVersion("Nightly build"))
        assertNull(releaseVersion(null))
    }

    private val forkAssets =
        Json
            .parseToJsonElement(
                """
                [
                  {"name": "Wholphin-default-release-1.0.7-83-g89fac169-59-arm64-v8a.apk", "browser_download_url": "https://x/arm64.apk"},
                  {"name": "Wholphin-default-release-1.0.7-83-g89fac169-59-armeabi-v7a.apk", "browser_download_url": "https://x/armv7.apk"},
                  {"name": "Wholphin-default-release-1.0.7-83-g89fac169-59-x86_64.apk", "browser_download_url": "https://x/x86_64.apk"},
                  {"name": "Wholphin-default-release-1.0.7-83-g89fac169-59.apk", "browser_download_url": "https://x/universal.apk"}
                ]
                """.trimIndent(),
            ).jsonArray

    @Test
    fun `full build names pick the device's ABI`() {
        assertEquals("https://x/arm64.apk", getDownloadUrl(forkAssets, false, listOf("arm64-v8a", "armeabi-v7a")))
        // Older Fire TV sticks run a 32 bit system
        assertEquals("https://x/armv7.apk", getDownloadUrl(forkAssets, false, listOf("armeabi-v7a")))
    }

    @Test
    fun `full build names fall back to the universal APK`() {
        assertEquals("https://x/universal.apk", getDownloadUrl(forkAssets, false, listOf("mips")))
        assertEquals("https://x/universal.apk", getDownloadUrl(forkAssets, false, listOf()))
    }

    @Test
    fun `a release build never picks a debug APK`() {
        assertNull(getDownloadUrl(forkAssets, true, listOf("arm64-v8a")))
    }
}

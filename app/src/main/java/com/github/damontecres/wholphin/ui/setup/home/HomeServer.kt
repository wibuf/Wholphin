package com.github.damontecres.wholphin.ui.setup.home

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.github.damontecres.wholphin.BuildConfig

/**
 * The one server a GooseFlix build signs in to, set at build time (BuildConfig.HOME_SERVER_URL).
 *
 * When it is set, the app never asks for a server: the server screens are replaced by
 * [HomeServerContent], which adds this server by itself, and by [HomeSignInContent].
 * Upstream builds leave it empty and keep the normal server and user screens.
 */
object HomeServer {
    val url: String = BuildConfig.HOME_SERVER_URL.trim().trimEnd('/')

    val enabled: Boolean get() = url.isNotEmpty()

    /** The short address shown on screen; the server's proxy redirects it to Quick Connect */
    val codePage: String = codePageFor(url)

    fun codePageFor(url: String): String = url.trim().trimEnd('/').substringAfter("://") + "/code"

    /** What the QR code opens: Jellyfin's own Quick Connect page with [code] already filled in */
    fun quickConnectLink(code: String?): String = quickConnectLinkFor(url, code)

    fun quickConnectLinkFor(
        url: String,
        code: String?,
    ): String {
        val base = url.trim().trimEnd('/')
        val digits = code?.filter(Char::isLetterOrDigit).orEmpty()
        return if (digits.isEmpty()) "$base/web/#/quickconnect" else "$base/web/#/quickconnect?code=$digits"
    }
}

/** GooseFlix colours, from the Media Adder palette, for the branded setup screens */
internal object HomeBrand {
    val navy = Color(0xFF0F172A)
    val navyHigh = Color(0xFF172554)
    val slate = Color(0xFF1E293B)
    val blue = Color(0xFF3B82F6)
    val lightBlue = Color(0xFF60A5FA)
    val text = Color(0xFFE2E8F0)
    val subtext = Color(0xFFCBD5E1)
    val muted = Color(0xFF94A3B8)

    val background =
        Brush.linearGradient(
            colors = listOf(navyHigh, navy, navy),
        )
}

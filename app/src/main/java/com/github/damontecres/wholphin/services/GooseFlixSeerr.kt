package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.data.SeerrServerDao
import com.github.damontecres.wholphin.data.model.JellyfinUser
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Fork-only (GooseFlix #1516): Seerr for every user, set up automatically.
 *
 * Upstream makes each user add a Seerr server by hand on every device. GooseFlix instead connects
 * every signed-in user to Scuffed's Seerr proxy ([BuildConfig.SEERR_PROXY_URL]), sending their
 * Jellyfin access token where a Seerr API key would go. The proxy checks the token, acts as that
 * person in Seerr, and refuses accounts it blocks (eg the Play review account), for whom this
 * removes Seerr so they see no Discover. Off while the URL is empty.
 */
object GooseFlixSeerr {
    val proxyUrl: String get() = BuildConfig.SEERR_PROXY_URL

    val enabled: Boolean get() = BuildConfig.GOOSEFLIX && proxyUrl.isNotBlank()

    /** Connects [user] to the proxy; false (and no Seerr) if they can't use it */
    suspend fun connect(
        user: JellyfinUser,
        repository: SeerrServerRepository,
        dao: SeerrServerDao,
    ): Boolean {
        val token = user.accessToken?.takeIf { it.isNotBlank() } ?: return false
        return try {
            // Saves the server and this user's credential (replacing any older token), then signs in
            repository.addAndChangeServer(proxyUrl, token)
            true
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val status = (ex as? ClientException)?.statusCode
            if (status == 401 || status == 403) {
                // Not allowed: forget the saved connection so Discover stays hidden next time too
                Timber.i("Seerr not available for this account (HTTP %s)", status)
                dao.getServer(proxyUrl)?.server?.let { dao.deleteUser(it.id, user.rowId) }
            } else {
                Timber.w(ex, "Could not connect to Seerr through %s", proxyUrl)
            }
            repository.clear()
            false
        }
    }
}

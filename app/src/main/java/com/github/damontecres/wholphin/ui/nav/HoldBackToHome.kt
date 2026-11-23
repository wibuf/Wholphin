package com.github.damontecres.wholphin.ui.nav

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Fork-only (GooseFlix): holding Back for about a second, on any page or menu, goes all the way
 * back to Home and reloads it from scratch. A short press is Back as always.
 *
 * Not while something is playing (video, music's Now Playing, a slideshow, a game), where Back
 * belongs to the player. While Back is held its repeats don't reach the page, so library grids'
 * own hold (jump back to the card you were on) gives way to this one, and once the hold has
 * worked, letting go isn't also a Back press.
 *
 * MainActivity feeds it every key; ApplicationContent, which exists only once someone is signed
 * in, does the reset, so on the sign-in screens a hold is just Back.
 */
object HoldBackToHome {
    /** How long Back is held before going Home */
    const val HOLD_MS = 1_000L

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Collected by ApplicationContent, which goes Home */
    val requests: SharedFlow<Unit> = _requests

    private var triggered = false

    /** True if [event] was used here and shouldn't reach the page */
    fun onKey(
        event: KeyEvent,
        current: Destination?,
    ): Boolean =
        onKey(
            keyCode = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            up = event.action == KeyEvent.ACTION_UP,
            repeatCount = event.repeatCount,
            heldMs = event.eventTime - event.downTime,
            current = current,
            listening = _requests.subscriptionCount.value > 0,
            goHome = { _requests.tryEmit(Unit) },
        )

    /** [onKey] without the Android event, for tests */
    internal fun onKey(
        keyCode: Int,
        down: Boolean,
        up: Boolean,
        repeatCount: Int,
        heldMs: Long,
        current: Destination?,
        listening: Boolean,
        goHome: () -> Unit,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return false
        if (!listening || playing(current)) {
            triggered = false
            return false
        }
        return when {
            down && repeatCount == 0 -> {
                // The press itself reaches the page as usual
                triggered = false
                false
            }

            down -> {
                if (!triggered && heldMs >= HOLD_MS) {
                    triggered = true
                    goHome()
                }
                true
            }

            up && triggered -> {
                triggered = false
                true
            }

            else -> {
                false
            }
        }
    }

    /** Pages where Back belongs to a player */
    fun playing(current: Destination?): Boolean =
        current is Destination.Playback ||
            current is Destination.PlaybackList ||
            current is Destination.Slideshow ||
            current is Destination.GamePlayer ||
            current is Destination.NowPlaying
}

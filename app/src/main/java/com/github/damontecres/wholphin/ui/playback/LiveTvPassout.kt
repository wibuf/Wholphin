package com.github.damontecres.wholphin.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Fork: passout protection for live TV. A channel never ends, so the next-episode check
 * ([PlaybackViewModel.shouldAutoPlayNextUp]) never applies to it, and a TV left on a channel
 * played all night. After the passout time with no button pressed, the player asks
 * "Still watching?" for [PROMPT_SECONDS], and stops if nobody answers, which also lets the TV sleep.
 */
object LiveTvPassout {
    const val PROMPT_SECONDS = 60

    /** How often the player checks */
    const val CHECK_INTERVAL_MS = 15_000L

    /** Whether it's time to ask: passout protection is on and nothing has been pressed for that long */
    fun isDue(
        nowMs: Long,
        lastInteractionMs: Long,
        passOutMs: Long,
    ): Boolean = passOutMs > 0 && nowMs - lastInteractionMs >= passOutMs
}

/**
 * The prompt, drawn over the picture. It takes no focus, so any button goes to the player as
 * usual, which counts as an interaction and clears it.
 */
@Composable
fun StillWatchingPrompt(
    secondsLeft: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .widthIn(max = 560.dp)
                .background(Color(0xE6101418), RoundedCornerShape(16.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Text(
            text = "Still watching?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = "Press any button to keep watching. Stopping in $secondsLeft seconds.",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

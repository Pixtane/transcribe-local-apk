package com.pixtane.holos.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-process view of the player for the UI (position is read directly for smooth karaoke sync). */
object PlayerBus {
    data class Snapshot(
        val recordingId: String? = null,
        val playWhenReady: Boolean = false,
        val state: Int = Player.STATE_IDLE,
        val speed: Float = 1f,
    ) {
        val isPlaying get() = playWhenReady && state == Player.STATE_READY
        val isBuffering get() = playWhenReady && state == Player.STATE_BUFFERING
    }

    @Volatile internal var player: SegmentPlayer? = null
    internal val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    fun positionMs(): Long = player?.positionMsDirect() ?: 0L
}

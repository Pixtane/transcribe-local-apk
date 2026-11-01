package androidx.media3.session

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.pixtane.holos.playback.PlayerBus
import com.pixtane.holos.playback.SegmentPlayer

/**
 * The few MediaController calls the shared screens make, forwarded to the desktop player.
 * (On Android the same calls go through a Media3 session to the playback service.)
 */
class MediaController(private val player: SegmentPlayer) {
    val currentMediaItem: MediaItem?
        get() = PlayerBus.snapshot.value.recordingId?.let { MediaItem.Builder().setMediaId(it).build() }
    val playWhenReady: Boolean get() = PlayerBus.snapshot.value.playWhenReady
    val playbackState: Int get() = PlayerBus.snapshot.value.state

    fun setMediaItem(item: MediaItem) = player.load(item.mediaId)
    fun prepare() = player.prepare()
    fun play() = player.setPlayWhenReady(true)
    fun pause() = player.setPlayWhenReady(false)
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun seekBack() = player.seekTo((player.positionMsDirect() - SegmentPlayer.SEEK_INCREMENT_MS).coerceAtLeast(0))
    fun seekForward() = player.seekTo(player.positionMsDirect() + SegmentPlayer.SEEK_INCREMENT_MS)
    fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)
    fun clearMediaItems() = player.load(null)

    @Suppress("unused") private val idle = Player.STATE_IDLE
}

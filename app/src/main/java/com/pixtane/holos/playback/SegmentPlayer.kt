@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.pixtane.holos.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.compose.ui.graphics.toArgb
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pixtane.holos.Prefs
import com.pixtane.holos.data.Recording
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.synth.SynthesisManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Media3 [Player] that plays a [Recording] whose sentences are still being generated.
 * The whole text is exposed as one media item with a single continuous timeline, so the
 * lock screen / notification seek bar covers the entire text. Sentences that are not
 * generated yet put the player into BUFFERING and ask the synthesizer to jump there.
 */
class SegmentPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "holos:playback").apply { setReferenceCounted(false) }

    private var rec: Recording? = null
    private var playWhenReady = false
    private var stopped = false
    @Volatile private var ended = false
    @Volatile private var buffering = false
    private var speed = Prefs.playbackSpeed.coerceIn(0.5f, 3f)
    private var progressJob: Job? = null
    private var tickerJob: Job? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private val artworkCache = HashMap<String, ByteArray>()

    private val engine = AudioEngine()

    init {
        PlayerBus.player = this
        engine.start()
    }

    // ---- Player state ----------------------------------------------------------------------

    override fun getState(): SimpleBasePlayer.State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
        ).build()
        val b = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        val r = rec ?: return b.setPlaybackState(Player.STATE_IDLE).setPlaylist(emptyList()).build()
        val item = SimpleBasePlayer.MediaItemData.Builder(r.id)
            .setMediaItem(mediaItemFor(r))
            .setMediaMetadata(metadataFor(r))
            .setDurationUs(r.totalMs() * 1000)
            .setIsSeekable(true)
            .setIsDynamic(!r.isComplete)
            .build()
        val state = when {
            stopped -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        return b.setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(state)
            .setIsLoading(!r.isComplete && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
            .setContentPositionMs { engine.positionMs() }
            .setContentBufferedPositionMs {
                val cur = r.segmentAt(r.msToFrames(engine.positionMs()))
                r.framesToMs(r.contiguousReadyEnd(cur))
            }
            .build()
    }

    private fun refresh() {
        invalidateState()
        PlayerBus._snapshot.value = PlayerBus.Snapshot(
            recordingId = rec?.id,
            playWhenReady = playWhenReady,
            state = when {
                rec == null || stopped -> Player.STATE_IDLE
                ended -> Player.STATE_ENDED
                buffering -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            },
            speed = speed,
        )
    }

    fun positionMsDirect(): Long = engine.positionMs()

    // ---- Commands --------------------------------------------------------------------------

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val idx = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val id = mediaItems.getOrNull(idx)?.mediaId
        load(id?.let { RecordingRepository.get(it) }, startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        stopped = false
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val r = rec ?: return Futures.immediateVoidFuture()
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceIn(0L, r.totalMs())
        ended = false
        engine.seekTo(r.msToFrames(target))
        r.savedPositionMs = target
        val seg = r.segmentAt(r.msToFrames(target))
        if (!r.isReady(seg)) SynthesisManager.requestSegment(r.id, seg)
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed.coerceIn(0.5f, 3f)
        Prefs.playbackSpeed = speed
        engine.setSpeed(speed)
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        setPlaying(false)
        stopped = true
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        rec?.let { it.savedPositionMs = engine.positionMs() }
        setPlaying(false)
        engine.quit()
        scope.cancel()
        if (PlayerBus.player === this) PlayerBus.player = null
        PlayerBus._snapshot.value = PlayerBus.Snapshot()
        return Futures.immediateVoidFuture()
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun load(r: Recording?, startPositionMs: Long) {
        rec?.let { it.savedPositionMs = engine.positionMs() }
        rec = r
        ended = false
        buffering = false
        stopped = false
        progressJob?.cancel()
        if (r == null) {
            engine.setRecording(null, 0)
            refresh(); return
        }
        Prefs.lastPlayedId = r.id
        var start = if (startPositionMs == C.TIME_UNSET) r.savedPositionMs else startPositionMs
        if (start >= r.totalMs() - 1500) start = 0
        engine.setRecording(r, r.msToFrames(start))
        val seg = r.segmentAt(r.msToFrames(start))
        if (!r.isComplete) SynthesisManager.requestSegment(r.id, seg)
        progressJob = scope.launch { r.ready.collect { refresh() } }
        refresh()
    }

    private fun setPlaying(play: Boolean) {
        val r = rec
        if (play && r != null) {
            if (ended) {
                ended = false
                engine.seekTo(0)
            }
            stopped = false
            if (!requestFocus()) return
            registerNoisy(true)
            if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
            startTicker()
        } else {
            r?.let { it.savedPositionMs = engine.positionMs() }
            registerNoisy(false)
            if (wakeLock.isHeld) wakeLock.release()
            tickerJob?.cancel()
        }
        playWhenReady = play && r != null
        engine.setPlaying(playWhenReady)
        refresh()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var n = 0
            while (isActive) {
                delay(1000)
                if (++n % 5 == 0) rec?.let { it.savedPositionMs = engine.positionMs() }
            }
        }
    }

    /** Called from the audio thread. */
    private fun onBuffering(value: Boolean) = main.post {
        if (buffering != value) { buffering = value; refresh() }
    }

    private fun onEnded() = main.post {
        ended = true
        rec?.let { it.savedPositionMs = 0 }
        setPlaying(false)
    }

    // ---- Audio focus & noisy ---------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; if (playWhenReady) pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (playWhenReady) { resumeOnFocusGain = true; pause() } }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.setVolume(0.25f)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    engine.setVolume(1f)
                    if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
                }
            }
        }
    }

    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRS)
            .setOnAudioFocusChangeListener(focusListener, main)
            .setWillPauseWhenDucked(false)
            .build()
    } else null

    private fun requestFocus(): Boolean {
        val res = if (Build.VERSION.SDK_INT >= 26) audioManager.requestAudioFocus(focusRequest!!)
        else @Suppress("DEPRECATION") audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        return res != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playWhenReady) pause()
        }
    }

    private fun registerNoisy(on: Boolean) {
        if (on && !noisyRegistered) {
            ContextCompat.registerReceiver(
                context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        } else if (!on && noisyRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    // ---- Metadata --------------------------------------------------------------------------

    private fun mediaItemFor(r: Recording) = MediaItem.Builder()
        .setMediaId(r.id)
        .setMediaMetadata(metadataFor(r))
        .build()

    private fun metadataFor(r: Recording): MediaMetadata {
        val art = artworkCache.getOrPut(r.id + r.lang.code) { artwork(r) }
        return MediaMetadata.Builder()
            .setTitle(r.title)
            .setArtist("${r.lang.flag} ${r.lang.native} · Holos")
            .setAlbumTitle("Holos")
            .setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
    }

    private fun artwork(r: Recording): ByteArray {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(r.lang.deep.toArgb(), 0xFF6D28D9.toInt(), 0xFF0A0A10.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = r.lang.accent.toArgb()
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 34f
        val heights = floatArrayOf(60f, 130f, 200f, 120f, 70f)
        for ((k, h) in heights.withIndex()) {
            val x = 136f + k * 60f
            c.drawLine(x, 256f - h / 2, x, 256f + h / 2, p)
        }
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 54f
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        c.drawText(r.lang.native, 256f, 440f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ---- Audio thread ----------------------------------------------------------------------

    /**
     * Owns the [AudioTrack]. Reads sentence PCM files in order and writes them with
     * non-blocking writes so commands (pause/seek/speed) are handled within ~10 ms.
     * Position = start of the last seek + frames the track has played (x speed).
     */
    private inner class AudioEngine : Thread("holos-audio") {
        private val lock = Object()
        // Commands (guarded by lock)
        private var cRec: Recording? = null
        private var cRecChanged = false
        private var cPlaying = false
        private var cSeek = -1L
        private var cSpeed = speed
        private var cVolume = 1f
        private var cQuit = false

        // Published position state
        @Volatile private var pRec: Recording? = null
        @Volatile private var pendingDisplayFrame = -1L
        @Volatile private var baseFrame = 0L
        @Volatile private var baseHead = 0L
        @Volatile private var outWritten = 0L
        @Volatile private var appliedSpeed = 1f
        @Volatile private var track: AudioTrack? = null

        fun setRecording(r: Recording?, startFrame: Long) = synchronized(lock) {
            cRec = r; cRecChanged = true; cSeek = startFrame
            pRec = r; pendingDisplayFrame = startFrame
            lock.notifyAll()
        }

        fun setPlaying(p: Boolean) = synchronized(lock) { cPlaying = p; lock.notifyAll() }
        fun seekTo(frame: Long) = synchronized(lock) { cSeek = frame; pendingDisplayFrame = frame; lock.notifyAll() }
        fun setSpeed(s: Float) = synchronized(lock) { cSpeed = s; lock.notifyAll() }
        fun setVolume(v: Float) = synchronized(lock) { cVolume = v; lock.notifyAll() }
        fun quit() = synchronized(lock) { cQuit = true; lock.notifyAll() }

        fun positionMs(): Long {
            val r = pRec ?: return 0L
            return r.framesToMs(positionFrames(r))
        }

        private fun head(t: AudioTrack?): Long =
            if (t == null) baseHead else t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

        fun positionFrames(r: Recording): Long {
            val pending = pendingDisplayFrame
            if (pending >= 0) return pending
            val played = (head(track) - baseHead).coerceIn(0L, outWritten)
            return (baseFrame + (played * appliedSpeed).toLong()).coerceIn(0L, r.totalFrames())
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var active: Recording? = null
            var curSeg = 0
            var curOff = 0L
            var raf: RandomAccessFile? = null
            var rafSeg = -1
            val readBytes = ByteArray(CHUNK_FRAMES * 2)
            var out = ShortArray(CHUNK_FRAMES * 4)
            var outOff = 0
            var outLen = 0
            var sonic: SonicAudioProcessor? = null
            var sonicEos = false
            var volume = 1f
            var lastRequest = -1
            var isBuffering = false
            var trackRate = 0

            fun setBuf(v: Boolean) { if (v != isBuffering) { isBuffering = v; onBuffering(v) } }

            while (true) {
                var playing: Boolean
                var seek: Long
                var spd: Float
                var vol: Float
                var r: Recording?
                synchronized(lock) {
                    if (cQuit) {
                        track?.release(); track = null
                        raf?.close()
                        return
                    }
                    r = cRec
                    if (cRecChanged) { cRecChanged = false; active = null }
                    playing = cPlaying
                    seek = cSeek; cSeek = -1
                    spd = cSpeed
                    vol = cVolume
                }
                val rec = r
                if (rec == null) {
                    track?.pause()
                    synchronized(lock) { if (!cQuit && cRec == null) lock.wait(500) }
                    continue
                }
                try {
                    if (active !== rec) {
                        active = rec
                        if (trackRate != rec.sampleRate) {
                            track?.release()
                            track = buildTrack(rec.sampleRate)
                            trackRate = rec.sampleRate
                            sonic = null
                        }
                        if (seek < 0) seek = 0
                    }
                    val t = track ?: continue
                    if (vol != volume) { volume = vol; t.setVolume(vol) }
                    if (spd != appliedSpeed && seek < 0) seek = positionFrames(rec)

                    if (seek >= 0) {
                        t.pause(); t.flush()
                        outOff = 0; outLen = 0; sonicEos = false
                        if (spd != 1f) {
                            val s = sonic ?: SonicAudioProcessor().also { sonic = it }
                            s.configure(AudioProcessor.AudioFormat(rec.sampleRate, 1, C.ENCODING_PCM_16BIT))
                            s.setSpeed(spd)
                            s.flush()
                        }
                        val target = seek.coerceIn(0L, rec.totalFrames())
                        curSeg = rec.segmentAt(target)
                        curOff = (target - rec.startFrame(curSeg)).coerceAtLeast(0L)
                        outWritten = 0
                        baseFrame = rec.startFrame(curSeg) + curOff
                        appliedSpeed = spd
                        baseHead = head(t)
                        pendingDisplayFrame = -1
                        if (playing) t.play()
                    }

                    if (!playing) {
                        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                        setBuf(false)
                        synchronized(lock) { if (!cPlaying && cSeek < 0 && !cQuit && !cRecChanged) lock.wait(500) }
                        continue
                    }
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()

                    // 1) Push pending output.
                    if (outLen > 0) {
                        val w = t.write(out, outOff, outLen, AudioTrack.WRITE_NON_BLOCKING)
                        if (w > 0) { outOff += w; outLen -= w; outWritten += w }
                        if (outLen > 0) { Thread.sleep(8); continue }
                    }

                    val drained = head(t) - baseHead >= outWritten

                    // 2) End of text.
                    if (curSeg >= rec.n) {
                        val s = sonic
                        if (appliedSpeed != 1f && s != null && !sonicEos) {
                            s.queueEndOfStream(); sonicEos = true
                            val o = s.output
                            val cnt = o.remaining() / 2
                            if (cnt > 0) {
                                if (out.size < cnt) out = ShortArray(cnt)
                                o.order(ByteOrder.nativeOrder()).asShortBuffer().get(out, 0, cnt)
                                outOff = 0; outLen = cnt
                            }
                            continue
                        }
                        if (drained) {
                            setBuf(false)
                            synchronized(lock) { cPlaying = false }
                            t.pause()
                            onEnded()
                        } else Thread.sleep(20)
                        continue
                    }

                    // 3) Sentence not generated yet: wait for the synthesizer.
                    if (!rec.isReady(curSeg)) {
                        if (lastRequest != curSeg) { lastRequest = curSeg; SynthesisManager.requestSegment(rec.id, curSeg) }
                        setBuf(drained)
                        synchronized(lock) { if (cSeek < 0 && !cQuit && !cRecChanged) lock.wait(40) }
                        continue
                    }
                    setBuf(false)

                    // 4) Read the next chunk of the current sentence.
                    val total = rec.framesOf(curSeg)
                    if (curOff >= total) { curSeg++; curOff = 0; continue }
                    if (raf == null || rafSeg != curSeg) {
                        raf?.close()
                        raf = RandomAccessFile(rec.segFile(curSeg), "r"); rafSeg = curSeg
                    }
                    val frames = minOf(CHUNK_FRAMES.toLong(), total - curOff).toInt()
                    val f = raf!!
                    f.seek(curOff * 2)
                    f.readFully(readBytes, 0, frames * 2)
                    curOff += frames

                    if (appliedSpeed == 1f) {
                        if (out.size < frames) out = ShortArray(frames)
                        ByteBuffer.wrap(readBytes, 0, frames * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, frames)
                        outOff = 0; outLen = frames
                    } else {
                        val s = sonic ?: continue
                        val inBuf = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
                        val src = ByteBuffer.wrap(readBytes, 0, frames * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (src.hasRemaining()) inBuf.putShort(src.get())
                        inBuf.flip()
                        s.queueInput(inBuf)
                        val o = s.output
                        val cnt = o.remaining() / 2
                        if (cnt > 0) {
                            if (out.size < cnt) out = ShortArray(cnt)
                            o.order(ByteOrder.nativeOrder()).asShortBuffer().get(out, 0, cnt)
                            outOff = 0; outLen = cnt
                        }
                    }
                } catch (e: InterruptedException) {
                    return
                } catch (e: Exception) {
                    Log.e("SegmentPlayer", "audio loop error", e)
                    raf?.close(); raf = null; rafSeg = -1
                    Thread.sleep(100)
                }
            }
        }

        private fun buildTrack(rate: Int): AudioTrack {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(ATTRS)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, rate / 3 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        private const val CHUNK_FRAMES = 2048
        private val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

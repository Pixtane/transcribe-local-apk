package com.pixtane.holos.playback

import androidx.media3.common.Player
import com.pixtane.holos.CrashReport
import com.pixtane.holos.Prefs
import com.pixtane.holos.data.Recording
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.synth.SynthesisManager
import sonic.Sonic
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

/**
 * Plays a recording as one continuous timeline over its per-sentence PCM files (Java Sound).
 * Sentences that are not generated yet are requested from the synthesizer and waited for
 * (buffering), exactly like the Android player.
 */
class SegmentPlayer {
    private val lock = Object()
    // Commands (guarded by lock)
    private var cRec: Recording? = null
    private var cRecChanged = false
    private var cPlaying = false
    private var cSeek = -1L
    private var cSpeed = Prefs.playbackSpeed.coerceIn(0.5f, 3f)
    private var cVolume = Prefs.volume.coerceIn(0f, 1f)
    private var cQuit = false

    // Published state
    @Volatile private var pRec: Recording? = null
    @Volatile private var pendingDisplayFrame = -1L
    @Volatile private var baseFrame = 0L
    @Volatile private var baseHead = 0L
    @Volatile private var outWritten = 0L
    @Volatile private var appliedSpeed = 1f
    @Volatile private var line: SourceDataLine? = null
    /** Some drivers only take stereo: then every sample is written to both channels. */
    @Volatile private var stereo = false
    @Volatile private var buffering = false
    @Volatile private var ended = false

    private val thread = Thread(::run, "holos-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }

    init {
        PlayerBus.player = this
        thread.start()
        Thread(::ticker, "holos-position").apply { isDaemon = true }.start()
    }

    // ---- commands -------------------------------------------------------------------------

    fun load(id: String?) {
        val old = pRec
        old?.let { it.savedPositionMs = positionMsDirect() }
        val r = id?.let { RecordingRepository.get(it) }
        if (r == null) {
            synchronized(lock) { cRec = null; cRecChanged = true; cPlaying = false; pRec = null; lock.notifyAll() }
            publish()
            return
        }
        if (old?.id == r.id) return
        Prefs.lastPlayedId = r.id
        var start = r.savedPositionMs
        if (start >= r.totalMs() - 500) start = 0
        val startFrame = r.msToFrames(start)
        ended = false
        synchronized(lock) {
            cRec = r; cRecChanged = true; cSeek = startFrame
            pRec = r; pendingDisplayFrame = startFrame
            lock.notifyAll()
        }
        val seg = r.segmentAt(startFrame)
        if (!r.isComplete) SynthesisManager.requestSegment(r.id, seg)
        publish()
    }

    fun prepare() = publish()

    fun setPlayWhenReady(play: Boolean) {
        val r = pRec ?: return
        if (play && ended) { ended = false; seekTo(0) }
        synchronized(lock) { cPlaying = play; lock.notifyAll() }
        if (!play) r.savedPositionMs = positionMsDirect()
        publish()
    }

    fun seekTo(ms: Long) {
        val r = pRec ?: return
        val target = r.msToFrames(ms.coerceIn(0, r.totalMs()))
        ended = false
        synchronized(lock) { cSeek = target; pendingDisplayFrame = target; lock.notifyAll() }
        r.savedPositionMs = r.framesToMs(target)
        val seg = r.segmentAt(target)
        if (!r.isReady(seg)) SynthesisManager.requestSegment(r.id, seg)
        publish()
    }

    fun setSpeed(s: Float) {
        val v = s.coerceIn(0.5f, 3f)
        Prefs.playbackSpeed = v
        synchronized(lock) { cSpeed = v; lock.notifyAll() }
        publish()
    }

    fun setVolume(v: Float) {
        Prefs.volume = v
        synchronized(lock) { cVolume = v.coerceIn(0f, 1f); lock.notifyAll() }
    }

    fun release() {
        pRec?.let { it.savedPositionMs = positionMsDirect() }
        synchronized(lock) { cQuit = true; lock.notifyAll() }
    }

    fun positionMsDirect(): Long {
        val r = pRec ?: return 0L
        return r.framesToMs(positionFrames(r))
    }

    // ---- state ----------------------------------------------------------------------------

    private fun publish() {
        val r = pRec
        val playing = synchronized(lock) { cPlaying }
        PlayerBus._snapshot.value = PlayerBus.Snapshot(
            recordingId = r?.id,
            playWhenReady = playing && r != null,
            state = when {
                r == null -> Player.STATE_IDLE
                ended -> Player.STATE_ENDED
                buffering -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            },
            speed = synchronized(lock) { cSpeed },
        )
    }

    /** Saves the listening position every few seconds while playing. */
    private fun ticker() {
        var n = 0
        while (true) {
            Thread.sleep(1000)
            val r = pRec ?: continue
            if (PlayerBus.snapshot.value.playWhenReady && ++n % 5 == 0) r.savedPositionMs = positionMsDirect()
        }
    }

    private fun head(l: SourceDataLine?): Long = l?.longFramePosition ?: baseHead

    private fun positionFrames(r: Recording): Long {
        val pending = pendingDisplayFrame
        if (pending >= 0) return pending
        val played = (head(line) - baseHead).coerceIn(0L, outWritten)
        return (baseFrame + (played * appliedSpeed).toLong()).coerceIn(0L, r.totalFrames())
    }

    private fun setBuffering(v: Boolean) {
        if (v != buffering) { buffering = v; publish() }
    }

    private fun onEnded() {
        ended = true
        pRec?.let { it.savedPositionMs = 0 }
        publish()
    }

    // ---- audio loop -----------------------------------------------------------------------

    private fun run() {
        var active: Recording? = null
        var curSeg = 0
        var curOff = 0L
        var raf: RandomAccessFile? = null
        var rafSeg = -1
        val readBytes = ByteArray(CHUNK_FRAMES * 2)
        val inShorts = ShortArray(CHUNK_FRAMES)
        var out = ShortArray(CHUNK_FRAMES * 4)
        var outBytes = ByteArray(out.size * 2)
        var outOff = 0
        var outLen = 0
        var sonic: Sonic? = null
        var sonicEos = false
        var volume = -1f
        var lastRequest = -1
        var lineRate = 0

        while (true) {
            var playing: Boolean
            var seek: Long
            var spd: Float
            var vol: Float
            var r: Recording?
            synchronized(lock) {
                if (cQuit) {
                    line?.let { runCatching { it.stop(); it.close() } }; line = null
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
                line?.stop()
                synchronized(lock) { if (!cQuit && cRec == null) lock.wait(500) }
                continue
            }
            try {
                if (active !== rec) {
                    if (lineRate != rec.sampleRate) {
                        line?.let { runCatching { it.stop(); it.close() } }
                        line = null; lineRate = 0
                        val opened = try {
                            openLine(rec.sampleRate)
                        } catch (e: Exception) {
                            // No usable audio output (e.g. headphones unplugged): retry shortly
                            // without losing the requested position.
                            CrashReport.crumb("audio output unavailable: $e")
                            if (seek >= 0) synchronized(lock) { if (cSeek < 0) cSeek = seek }
                            Thread.sleep(1000)
                            continue
                        }
                        line = opened
                        lineRate = rec.sampleRate
                        sonic = null
                        volume = -1f
                    }
                    active = rec
                    lastRequest = -1
                    if (seek < 0) seek = 0
                }
                val l = line ?: continue
                if (vol != volume) { volume = vol; applyVolume(l, vol) }
                if (spd != appliedSpeed && seek < 0) seek = positionFrames(rec)

                if (seek >= 0) {
                    l.stop(); l.flush()
                    outOff = 0; outLen = 0; sonicEos = false
                    sonic = if (spd != 1f) Sonic(rec.sampleRate, 1).apply { speed = spd } else null
                    val target = seek.coerceIn(0L, rec.totalFrames())
                    curSeg = rec.segmentAt(target)
                    curOff = (target - rec.startFrame(curSeg)).coerceAtLeast(0L)
                    outWritten = 0
                    baseFrame = rec.startFrame(curSeg) + curOff
                    appliedSpeed = spd
                    baseHead = head(l)
                    pendingDisplayFrame = -1
                }

                if (!playing) {
                    if (l.isRunning) l.stop()
                    setBuffering(false)
                    synchronized(lock) { if (!cPlaying && cSeek < 0 && !cQuit && !cRecChanged) lock.wait(500) }
                    continue
                }
                if (!l.isRunning) l.start()

                // 1) Push pending output without blocking.
                if (outLen > 0) {
                    val bpf = if (stereo) 4 else 2
                    val room = l.available() / bpf
                    val n = minOf(room, outLen)
                    if (n > 0) {
                        if (outBytes.size < n * bpf) outBytes = ByteArray(n * bpf)
                        for (k in 0 until n) {
                            val v = out[outOff + k].toInt()
                            val lo = v.toByte(); val hi = (v shr 8).toByte()
                            if (stereo) {
                                outBytes[4 * k] = lo; outBytes[4 * k + 1] = hi; outBytes[4 * k + 2] = lo; outBytes[4 * k + 3] = hi
                            } else {
                                outBytes[2 * k] = lo; outBytes[2 * k + 1] = hi
                            }
                        }
                        l.write(outBytes, 0, n * bpf)
                        outOff += n; outLen -= n; outWritten += n
                    }
                    if (outLen > 0) { Thread.sleep(8); continue }
                }

                val drained = head(l) - baseHead >= outWritten

                // 2) End of text.
                if (curSeg >= rec.n) {
                    val s = sonic
                    if (s != null && !sonicEos) {
                        s.flushStream(); sonicEos = true
                        val cnt = s.samplesAvailable()
                        if (cnt > 0) {
                            if (out.size < cnt) out = ShortArray(cnt)
                            outLen = s.readShortFromStream(out, cnt); outOff = 0
                        }
                        continue
                    }
                    if (drained) {
                        setBuffering(false)
                        synchronized(lock) { cPlaying = false }
                        l.stop()
                        onEnded()
                    } else Thread.sleep(20)
                    continue
                }

                // 3) Sentence not generated yet: wait for the synthesizer.
                if (!rec.isReady(curSeg)) {
                    if (lastRequest != curSeg) { lastRequest = curSeg; SynthesisManager.requestSegment(rec.id, curSeg) }
                    setBuffering(drained)
                    synchronized(lock) { if (cSeek < 0 && !cQuit && !cRecChanged) lock.wait(40) }
                    continue
                }
                setBuffering(false)

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
                ByteBuffer.wrap(readBytes, 0, frames * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inShorts, 0, frames)

                val s = sonic
                if (s == null) {
                    if (out.size < frames) out = ShortArray(frames)
                    System.arraycopy(inShorts, 0, out, 0, frames)
                    outOff = 0; outLen = frames
                } else {
                    s.writeShortToStream(inShorts, frames)
                    val cnt = s.samplesAvailable()
                    if (cnt > 0) {
                        if (out.size < cnt) out = ShortArray(cnt)
                        outLen = s.readShortFromStream(out, cnt); outOff = 0
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                CrashReport.crumb("audio loop error: $e")
                System.err.println("SegmentPlayer: $e")
                raf?.close(); raf = null; rafSeg = -1
                Thread.sleep(200)
            }
        }
    }

    private fun openLine(rate: Int): SourceDataLine {
        var last: Exception? = null
        for (channels in intArrayOf(1, 2)) {
            try {
                val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
                val l = AudioSystem.getSourceDataLine(format)
                l.open(format, (rate / 3) * 2 * channels)
                stereo = channels == 2
                return l
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No audio output")
    }

    private fun applyVolume(l: SourceDataLine, v: Float) {
        runCatching {
            val g = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val db = if (v <= 0.0001f) g.minimum else (20.0 * kotlin.math.log10(v.toDouble())).toFloat()
            g.value = db.coerceIn(g.minimum, g.maximum)
        }
    }

    companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        private const val CHUNK_FRAMES = 2048
    }
}

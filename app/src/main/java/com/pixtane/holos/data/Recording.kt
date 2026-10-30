package com.pixtane.holos.data

import com.pixtane.holos.Lang
import com.pixtane.holos.importer.Chapter
import com.pixtane.holos.tts.EngineCatalog
import com.pixtane.holos.tts.EnginePack
import com.pixtane.holos.tts.Seg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLongArray

/**
 * A text turned (or being turned) into speech. Audio lives as one raw 16-bit mono PCM file
 * per sentence in `seg/`, written atomically, so generation progress survives the app
 * being killed at any moment and playback can start long before generation finishes.
 */
class Recording(
    val dir: File,
    val id: String,
    title: String,
    val lang: Lang,
    val voice: Int,
    val steps: Int,
    val engineId: String,
    val sampleRate: Int,
    val createdAt: Long,
    val text: String,
    val segs: List<Seg>,
    val chapters: List<Chapter> = emptyList(),
) {
    val n = segs.size
    val pack: EnginePack get() = EngineCatalog.get(engineId)

    /** First sentence of each chapter. */
    val chapterSeg: IntArray = IntArray(chapters.size) { k -> segmentForChar(chapters[k].offset) }

    @Volatile var title: String = title
        internal set

    /** Smart generation: only generate while charging (see PowerConditions). */
    @Volatile var smart: Boolean = false
        internal set

    private val frames = AtomicLongArray(n)
    private val _ready = MutableStateFlow(0)
    /** Number of sentences whose audio is on disk. */
    val ready: StateFlow<Int> = _ready

    @Volatile private var prefixCache: LongArray = LongArray(n + 1)
    @Volatile private var prefixVersion = -1
    @Volatile private var fpcCache = 0.0

    private val segDir = File(dir, "seg")

    init {
        segDir.mkdirs()
        var count = 0
        for (i in 0 until n) {
            val f = segFile(i)
            if (f.exists()) {
                frames.set(i, f.length() / 2); count++
            } else {
                frames.set(i, -1)
            }
        }
        _ready.value = count
    }

    val isComplete get() = _ready.value >= n

    fun segFile(i: Int) = File(segDir, String.format(Locale.US, "%05d.pcm", i))
    fun isReady(i: Int) = i in 0 until n && frames.get(i) >= 0
    fun framesOf(i: Int) = frames.get(i)

    fun markReady(i: Int, frameCount: Long) {
        if (frames.getAndSet(i, frameCount) < 0) _ready.update { it + 1 }
    }

    /** Text sent to the TTS engine for sentence [i]. */
    fun ttsText(i: Int): String = text.substring(segs[i].start, segs[i].end).replace(WS, " ").trim()

    fun silenceFrames(i: Int): Int = ((if (segs[i].paraEnd) 0.55 else 0.22) * sampleRate).toInt()

    /** Average speech frames per character over generated sentences (for estimates). */
    private fun framesPerChar(): Double {
        var f = 0L; var c = 0L
        for (i in 0 until n) {
            val fr = frames.get(i)
            if (fr >= 0) { f += fr - silenceFrames(i); c += segs[i].length }
        }
        return if (c > 40) f.toDouble() / c else sampleRate * 0.066
    }

    fun estimatedFrames(i: Int): Long {
        val fr = frames.get(i)
        if (fr >= 0) return fr
        prefix()
        return (segs[i].length * fpcCache).toLong() + silenceFrames(i)
    }

    /** Sentence containing (or following) character [offset]. */
    fun segmentForChar(offset: Int): Int {
        if (n == 0) return 0
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (segs[mid].end <= offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the chapter containing sentence [seg], or -1. */
    fun chapterAt(seg: Int): Int {
        var res = -1
        for (k in chapterSeg.indices) if (chapterSeg[k] <= seg) res = k else break
        return res
    }

    /** Prefix sums of (actual or estimated) frames: prefix[i] = start frame of sentence i. */
    fun prefix(): LongArray {
        val v = _ready.value
        if (v != prefixVersion) {
            val fpc = framesPerChar()
            fpcCache = fpc
            val p = LongArray(n + 1)
            for (i in 0 until n) {
                val fr = frames.get(i)
                p[i + 1] = p[i] + if (fr >= 0) fr else (segs[i].length * fpc).toLong() + silenceFrames(i)
            }
            prefixCache = p
            prefixVersion = v
        }
        return prefixCache
    }

    fun totalFrames() = prefix()[n]
    fun startFrame(i: Int) = prefix()[i.coerceIn(0, n)]

    /** Index of the sentence containing [frame]. */
    fun segmentAt(frame: Long): Int {
        val p = prefix()
        if (n == 0) return 0
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (p[mid] <= frame) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** End frame of the contiguous generated region starting at sentence [from]. */
    fun contiguousReadyEnd(from: Int): Long {
        var i = from
        while (i < n && isReady(i)) i++
        return startFrame(i)
    }

    fun framesToMs(f: Long) = f * 1000 / sampleRate
    fun msToFrames(ms: Long) = ms * sampleRate / 1000

    fun totalMs() = framesToMs(totalFrames())
    fun startMs(i: Int) = framesToMs(startFrame(i))

    fun audioBytesOnDisk(): Long = (0 until n).sumOf { val f = frames.get(it); if (f > 0) f * 2 else 0L }

    var savedPositionMs: Long
        get() = runCatching { File(dir, "position").readText().trim().toLong() }.getOrDefault(0L)
        set(v) { runCatching { File(dir, "position").writeText(v.toString()) } }

    companion object {
        private val WS = Regex("\\s+")
    }
}

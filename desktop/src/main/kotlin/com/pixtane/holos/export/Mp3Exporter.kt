package com.pixtane.holos.export

import com.pixtane.holos.data.Recording
import de.sciss.jump3r.lowlevel.LameEncoder
import de.sciss.jump3r.mp3.MPEGMode
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import kotlin.coroutines.coroutineContext

/** Encodes a recording's sentence PCM files into one MP3 file (pure-Java LAME). */
object Mp3Exporter {
    private const val KBPS = 64

    /** Number of sentences that will be exported (the generated part up to the first gap). */
    fun exportableSentences(rec: Recording): Int {
        var i = 0
        while (i < rec.n && rec.isReady(i)) i++
        return i
    }

    suspend fun export(rec: Recording, target: File, onProgress: (Float) -> Unit) {
        val count = exportableSentences(rec)
        if (count == 0) throw IOException("Nothing generated yet")
        val totalFrames = (0 until count).sumOf { rec.framesOf(it) }.coerceAtLeast(1)
        val format = AudioFormat(rec.sampleRate.toFloat(), 16, 1, true, false)
        val enc = LameEncoder(format, KBPS, MPEGMode.MONO.ordinal, LameEncoder.QUALITY_MIDDLE, false)
        val tmp = File(target.path + ".part")
        try {
            BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
                val pcmChunk = enc.pcmBufferSize
                val inBuf = ByteArray(pcmChunk)
                val mp3 = ByteArray(enc.mP3BufferSize)
                var done = 0L
                for (i in 0 until count) {
                    RandomAccessFile(rec.segFile(i), "r").use { f ->
                        var left = rec.framesOf(i) * 2
                        while (left > 0) {
                            coroutineContext.ensureActive()
                            val n = minOf(left, pcmChunk.toLong()).toInt() and 0x7FFFFFFE
                            if (n == 0) break
                            f.readFully(inBuf, 0, n)
                            val w = enc.encodeBuffer(inBuf, 0, n, mp3)
                            if (w > 0) out.write(mp3, 0, w)
                            left -= n
                            done += n / 2
                        }
                    }
                    onProgress((done.toFloat() / totalFrames).coerceIn(0f, 1f))
                }
                val w = enc.encodeFinish(mp3)
                if (w > 0) out.write(mp3, 0, w)
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save ${target.name}")
        } finally {
            enc.close()
            tmp.delete()
        }
        onProgress(1f)
    }

    fun fileName(rec: Recording): String =
        rec.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifEmpty { "Holos" } + ".mp3"
}

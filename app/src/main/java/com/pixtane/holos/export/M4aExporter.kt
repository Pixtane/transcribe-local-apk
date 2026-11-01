package com.pixtane.holos.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.pixtane.holos.data.Recording
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Encodes a recording's sentence PCM files into one AAC .m4a file with the platform encoder
 * (MediaCodec + MediaMuxer), so it can be played in any standard audio app.
 */
object M4aExporter {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 64_000
    private const val TIMEOUT_US = 10_000L

    /** Number of sentences that will be exported (the generated part up to the first gap). */
    fun exportableSentences(rec: Recording): Int {
        var i = 0
        while (i < rec.n && rec.isReady(i)) i++
        return i
    }

    suspend fun export(context: Context, rec: Recording, target: Uri, onProgress: (Float) -> Unit) {
        val count = exportableSentences(rec)
        if (count == 0) throw IOException("Nothing generated yet")
        val totalFrames = (0 until count).sumOf { rec.framesOf(it) }.coerceAtLeast(1)
        val pfd = context.contentResolver.openFileDescriptor(target, "rw") ?: throw IOException("Cannot open file")
        pfd.use { fd ->
            val format = MediaFormat.createAudioFormat(MIME, rec.sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val codec = MediaCodec.createEncoderByType(MIME)
            val muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxing = false
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var seg = 0
                var raf: RandomAccessFile? = null
                var segLeft = 0L
                var framesIn = 0L
                var inputDone = false
                var outputDone = false
                val chunk = ByteArray(8 * 1024)
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            buf.clear()
                            // Advance to the next sentence file when the current one is used up.
                            while (segLeft == 0L && seg < count) {
                                raf?.close()
                                raf = RandomAccessFile(rec.segFile(seg), "r")
                                segLeft = rec.framesOf(seg) * 2
                                seg++
                            }
                            if (segLeft == 0L) {
                                codec.queueInputBuffer(inIndex, 0, 0, ptsUs(framesIn, rec.sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val n = minOf(buf.remaining().toLong(), segLeft, chunk.size.toLong()).toInt() and 0x7FFFFFFE
                                raf!!.readFully(chunk, 0, n)
                                buf.put(chunk, 0, n)
                                segLeft -= n
                                codec.queueInputBuffer(inIndex, 0, n, ptsUs(framesIn, rec.sampleRate), 0)
                                framesIn += n / 2
                                onProgress((framesIn.toFloat() / totalFrames).coerceIn(0f, 1f))
                            }
                        }
                    }
                    var outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                track = muxer.addTrack(codec.outputFormat)
                                muxer.start(); muxing = true
                            }
                            outIndex >= 0 -> {
                                val out: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (info.size > 0 && muxing) {
                                    out.position(info.offset); out.limit(info.offset + info.size)
                                    muxer.writeSampleData(track, out, info)
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                            }
                        }
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }
                raf?.close()
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxing) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        }
        onProgress(1f)
    }

    private fun ptsUs(frames: Long, rate: Int) = frames * 1_000_000L / rate

    fun fileName(rec: Recording): String =
        rec.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifEmpty { "Holos" } + ".m4a"
}

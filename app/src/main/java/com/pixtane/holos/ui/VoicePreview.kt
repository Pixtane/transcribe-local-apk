package com.pixtane.holos.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pixtane.holos.Lang
import com.pixtane.holos.Prefs
import com.pixtane.holos.tts.EnginePool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Synthesizes a short sample sentence and plays it once (for choosing a voice). */
object VoicePreview {
    private var track: AudioTrack? = null

    suspend fun play(lang: Lang, sid: Int, steps: Int) {
        EnginePool.configure(Prefs.workers, Prefs.threadsPerWorker)
        val samples = EnginePool.withEngine { tts ->
            withContext(Dispatchers.Default) { EnginePool.generate(tts, lang.sample, lang.code, sid, steps) }
        }
        if (samples.isEmpty()) return
        withContext(Dispatchers.IO) {
            stop()
            val pcm = ShortArray(samples.size) { (samples[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(EnginePool.MODEL_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
        }
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

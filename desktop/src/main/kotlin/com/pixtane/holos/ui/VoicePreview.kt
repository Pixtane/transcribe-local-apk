package com.pixtane.holos.ui

import com.pixtane.holos.Lang
import com.pixtane.holos.Prefs
import com.pixtane.holos.tts.EnginePack
import com.pixtane.holos.tts.EnginePool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/** Synthesizes a short sample sentence and plays it once (for choosing a voice). */
object VoicePreview {
    @Volatile private var clip: Clip? = null

    suspend fun play(lang: Lang, pack: EnginePack, sid: Int, steps: Int) {
        val pool = EnginePool.of(pack)
        pool.configure(Prefs.workers, maxOf(Prefs.threadsPerWorker, pack.minThreads))
        val samples = pool.withEngine { tts ->
            withContext(Dispatchers.Default) { pool.generate(tts, lang.prepare(lang.sample), lang.ttsCode, sid, steps) }
        }
        if (samples.isEmpty()) return
        withContext(Dispatchers.IO) {
            stop()
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt()
                bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
            }
            val c = AudioSystem.getClip()
            c.open(AudioFormat(pack.sampleRate.toFloat(), 16, 1, true, false), bytes, 0, bytes.size)
            c.start()
            clip = c
        }
    }

    fun stop() {
        clip?.let { runCatching { it.stop(); it.close() } }
        clip = null
    }
}

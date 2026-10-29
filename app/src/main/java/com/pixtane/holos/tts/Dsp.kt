package com.pixtane.holos.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Dsp {
    private const val TAPS = 47
    private val halfBand: FloatArray by lazy {
        // Windowed-sinc low-pass at 0.45 * new Nyquist, Blackman window.
        val fc = 0.225
        val m = TAPS - 1
        val h = FloatArray(TAPS)
        var sum = 0.0
        for (k in 0 until TAPS) {
            val x = k - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * k / m) + 0.08 * cos(4 * PI * k / m)
            h[k] = (sinc * w).toFloat()
            sum += h[k]
        }
        for (k in 0 until TAPS) h[k] = (h[k] / sum).toFloat()
        h
    }

    /** 44.1 kHz -> 22.05 kHz with an anti-alias filter. */
    fun decimate2(src: FloatArray): FloatArray {
        val h = halfBand
        val half = TAPS / 2
        val out = FloatArray(src.size / 2)
        for (o in out.indices) {
            val center = o * 2
            var acc = 0f
            for (k in 0 until TAPS) {
                val idx = center + k - half
                if (idx >= 0 && idx < src.size) acc += src[idx] * h[k]
            }
            out[o] = acc
        }
        return out
    }

    /** Float [-1,1] samples + trailing silence -> 16-bit little-endian PCM bytes. */
    fun toPcm16(samples: FloatArray, silenceFrames: Int): ByteArray {
        val bb = ByteBuffer.allocate((samples.size + silenceFrames) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s * 32767f).coerceIn(-32768f, 32767f)
            bb.putShort(v.toInt().toShort())
        }
        return bb.array()
    }
}

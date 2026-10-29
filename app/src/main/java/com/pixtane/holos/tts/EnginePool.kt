package com.pixtane.holos.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.pixtane.holos.CrashReport
import com.pixtane.holos.HolosApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A pool of Supertonic engines. Each [OfflineTts] is single-threaded from our side, so
 * running several instances side by side (one per worker) scales throughput on a
 * big.LITTLE phone far better than giving one instance more ONNX threads.
 */
object EnginePool {
    const val MODEL_SAMPLE_RATE = 44_100
    private const val TAG = "EnginePool"
    private const val IDLE_RELEASE_MS = 90_000L

    private val mutex = Mutex()
    /** Engines are created one at a time: loading several models at once spikes memory. */
    private val createMutex = Mutex()
    private val available = ArrayDeque<OfflineTts>()
    private val threadsOf = HashMap<OfflineTts, Int>()
    private var created = 0
    private var borrowed = 0
    private var targetSize = 1
    private var threads = 1
    private var idleJob: Job? = null
    private val signal = Channel<Unit>(Channel.CONFLATED)

    suspend fun configure(workers: Int, threadsPerWorker: Int) = mutex.withLock {
        targetSize = workers.coerceIn(1, 8)
        threads = threadsPerWorker.coerceIn(1, 8)
        val it = available.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (created > targetSize || threadsOf[t] != threads) {
                it.remove(); destroy(t)
            }
        }
    }

    suspend fun borrow(): OfflineTts {
        while (true) {
            var create = false
            val got: OfflineTts? = mutex.withLock {
                idleJob?.cancel()
                val t = available.removeFirstOrNull()
                when {
                    t != null -> { borrowed++; t }
                    created < targetSize -> { created++; borrowed++; create = true; null }
                    else -> null
                }
            }
            if (got != null) return got
            if (create) {
                val threadCount = mutex.withLock { threads }
                return try {
                    withContext(Dispatchers.IO) {
                        createMutex.withLock {
                            CrashReport.crumb("Creating engine (threads=$threadCount)")
                            val t = OfflineTts(config = buildConfig(threadCount))
                            CrashReport.crumb("Engine ready")
                            mutex.withLock { threadsOf[t] = threadCount }
                            t
                        }
                    }
                } catch (e: Throwable) {
                    withContext(NonCancellable) { mutex.withLock { created--; borrowed-- } }
                    throw e
                }
            }
            withTimeoutOrNull(250) { signal.receive() }
        }
    }

    suspend fun giveBack(tts: OfflineTts) {
        withContext(NonCancellable) {
            mutex.withLock {
                borrowed--
                if (created > targetSize || threadsOf[tts] != threads) destroy(tts) else available.addLast(tts)
                if (borrowed == 0) scheduleIdleRelease()
            }
            signal.trySend(Unit)
        }
    }

    suspend fun <T> withEngine(block: suspend (OfflineTts) -> T): T {
        val t = borrow()
        try {
            return block(t)
        } finally {
            giveBack(t)
        }
    }

    fun generate(tts: OfflineTts, text: String, lang: String, sid: Int, steps: Int): FloatArray {
        val cfg = GenerationConfig(
            sid = sid,
            speed = 1.0f,
            numSteps = steps,
            extra = mapOf("lang" to lang, "num_steps" to steps.toString()),
        )
        return tts.generateWithConfig(text, cfg).samples
    }

    /** Frees every idle engine (e.g. after the model was deleted). */
    fun releaseAll() {
        HolosApp.scope.launch {
            mutex.withLock {
                while (available.isNotEmpty()) destroy(available.removeFirst())
            }
        }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = HolosApp.scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                if (borrowed == 0) {
                    Log.i(TAG, "Releasing ${available.size} idle engines")
                    while (available.isNotEmpty()) destroy(available.removeFirst())
                }
            }
        }
    }

    private fun destroy(t: OfflineTts) {
        threadsOf.remove(t)
        created--
        try { t.release() } catch (e: Throwable) { Log.w(TAG, "release failed", e) }
    }

    private fun buildConfig(threadCount: Int) = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = ModelManager.file("duration_predictor.int8.onnx"),
                textEncoder = ModelManager.file("text_encoder.int8.onnx"),
                vectorEstimator = ModelManager.file("vector_estimator.int8.onnx"),
                vocoder = ModelManager.file("vocoder.int8.onnx"),
                ttsJson = ModelManager.file("tts.json"),
                unicodeIndexer = ModelManager.file("unicode_indexer.bin"),
                voiceStyle = ModelManager.file("voice.bin"),
            ),
            numThreads = threadCount,
            debug = false,
            provider = "cpu",
        ),
        maxNumSentences = 1,
        silenceScale = 0.2f,
    )
}

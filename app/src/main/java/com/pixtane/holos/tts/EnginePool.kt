package com.pixtane.holos.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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
import java.util.concurrent.ConcurrentHashMap

/**
 * A pool of engines for one [EnginePack]. Each [OfflineTts] is used by one worker at a time;
 * running several instances side by side (one per worker) scales throughput on a big.LITTLE
 * phone far better than giving one instance more ONNX threads.
 */
class EnginePool private constructor(val pack: EnginePack) {
    private val mutex = Mutex()
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
                            CrashReport.crumb("Creating ${pack.id} engine (threads=$threadCount)")
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

    /** Synthesizes [text]; returns samples at [EnginePack.sampleRate]. */
    fun generate(tts: OfflineTts, text: String, lang: String, sid: Int, steps: Int): FloatArray = when (pack.kind) {
        EngineKind.SUPERTONIC -> tts.generateWithConfig(
            text,
            GenerationConfig(
                sid = sid, speed = 1.0f, numSteps = steps,
                extra = mapOf("lang" to lang, "num_steps" to steps.toString()),
            ),
        ).samples
        EngineKind.VITS -> tts.generate(text, sid = sid.coerceAtLeast(0), speed = 1.0f).samples
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
                    Log.i(TAG, "Releasing ${available.size} idle ${pack.id} engines")
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

    private fun f(name: String) = ModelManager.file(pack, name)

    private fun buildConfig(threadCount: Int): OfflineTtsConfig {
        val model = when (pack.kind) {
            EngineKind.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = f("duration_predictor.int8.onnx"),
                    textEncoder = f("text_encoder.int8.onnx"),
                    vectorEstimator = f("vector_estimator.int8.onnx"),
                    vocoder = f("vocoder.int8.onnx"),
                    ttsJson = f("tts.json"),
                    unicodeIndexer = f("unicode_indexer.bin"),
                    voiceStyle = f("voice.bin"),
                ),
                numThreads = threadCount, debug = false, provider = "cpu",
            )
            EngineKind.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = f("model.onnx"), tokens = f("tokens.txt")),
                numThreads = threadCount, debug = false, provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = model, maxNumSentences = 1, silenceScale = 0.2f)
    }

    companion object {
        private const val TAG = "EnginePool"
        private const val IDLE_RELEASE_MS = 90_000L
        /** Engines are created one at a time (across packs): loading several models at once spikes memory. */
        private val createMutex = Mutex()
        private val pools = ConcurrentHashMap<String, EnginePool>()

        fun of(pack: EnginePack): EnginePool = pools.getOrPut(pack.id) { EnginePool(pack) }
    }
}

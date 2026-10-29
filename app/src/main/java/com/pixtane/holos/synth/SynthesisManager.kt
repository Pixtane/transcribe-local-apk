package com.pixtane.holos.synth

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.pixtane.holos.CrashReport
import com.pixtane.holos.HolosApp
import com.pixtane.holos.Prefs
import com.pixtane.holos.data.Recording
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.tts.Dsp
import com.pixtane.holos.tts.EnginePool
import com.pixtane.holos.tts.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns recordings into audio, one recording at a time, with several engines working on
 * different sentences in parallel. Sentences are picked in reading order starting from a
 * "priority" sentence (where the listener is), so playback can begin almost immediately and
 * jumping ahead re-focuses the workers. Every finished sentence is saved on its own.
 */
object SynthesisManager {
    data class Status(
        val recordingId: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val charsPerSec: Float = 0f,
        val etaSec: Int = -1,
        val loadingEngines: Boolean = false,
        val error: String? = null,
    ) {
        val running get() = recordingId != null
    }

    private const val TAG = "Synthesis"
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val queue = ArrayDeque<String>()
    private val priority = ConcurrentHashMap<String, Int>()
    private val _paused = MutableStateFlow<Set<String>>(emptySet())
    /** Recordings the user explicitly paused. */
    val paused: StateFlow<Set<String>> = _paused

    private var runner: Job? = null
    private var current: Job? = null
    @Volatile private var activeId: String? = null
    @Volatile private var lastError: String? = null

    fun isIdle() = runner?.isActive != true

    fun isQueuedOrActive(id: String) = activeId == id || synchronized(queue) { id in queue }

    fun enqueue(id: String, front: Boolean = false) {
        val rec = RecordingRepository.get(id) ?: return
        if (rec.isComplete) return
        _paused.value = _paused.value - id
        synchronized(queue) {
            if (activeId != id) {
                queue.remove(id)
                if (front) queue.addFirst(id) else queue.addLast(id)
            }
        }
        if (front && activeId != null && activeId != id) {
            // Listener wants a different recording right now: requeue the current one behind it.
            activeId?.let { synchronized(queue) { if (it !in queue) queue.add(1.coerceAtMost(queue.size), it) } }
            current?.cancel()
        }
        ensureRunning()
    }

    /** Called by the player when it needs sentence [seg] (e.g. after a seek). */
    fun requestSegment(id: String, seg: Int) {
        priority[id] = seg
        if (!isQueuedOrActive(id)) enqueue(id, front = true)
    }

    fun pause(id: String) {
        _paused.value = _paused.value + id
        synchronized(queue) { queue.remove(id) }
        if (activeId == id) current?.cancel()
    }

    fun cancel(id: String) = pause(id)

    private fun ensureRunning() {
        if (runner?.isActive == true) return
        startService()
        runner = HolosApp.scope.launch { runLoop() }
    }

    private fun startService() {
        val ctx: Context = HolosApp.instance
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SynthesisService::class.java))
        } catch (e: Exception) {
            // Background start not allowed (Android 12+). The playback service keeps us alive.
            Log.w(TAG, "Could not start foreground service: ${e.message}")
        }
    }

    private suspend fun runLoop() {
        while (true) {
            val id = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            val rec = RecordingRepository.get(id) ?: continue
            if (rec.isComplete || !ModelManager.isReady()) continue
            activeId = id
            _status.value = Status(id, rec.ready.value, rec.n, loadingEngines = true)
            val job = HolosApp.scope.launch { generate(rec) }
            current = job
            job.join()
            activeId = null
        }
        _status.value = Status(error = lastError)
        lastError = null
        val ctx = HolosApp.instance
        ctx.stopService(Intent(ctx, SynthesisService::class.java))
    }

    private suspend fun generate(rec: Recording) {
        val workers = Prefs.workers
        CrashReport.crumb("Start generating ${rec.id}: ${rec.n} sentences, $workers engines, ${rec.steps} steps")
        EnginePool.configure(workers, Prefs.threadsPerWorker)
        val inProgress = HashSet<Int>()
        val lock = Any()
        val chars = AtomicLong(0)
        val startedAt = AtomicLong(0)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)

        fun pick(): Int? = synchronized(lock) {
            val from = (priority[rec.id] ?: 0).coerceIn(0, rec.n)
            for (i in from until rec.n) if (!rec.isReady(i) && i !in inProgress) { inProgress.add(i); return i }
            for (i in 0 until from) if (!rec.isReady(i) && i !in inProgress) { inProgress.add(i); return i }
            null
        }

        try {
            coroutineScope {
                repeat(workers) {
                    launch(Dispatchers.Default) {
                        while (isActive) {
                            val i = pick() ?: break
                            try {
                                EnginePool.withEngine { tts ->
                                    startedAt.compareAndSet(0, SystemClock.elapsedRealtime())
                                    val text = rec.ttsText(i)
                                    CrashReport.crumb("Generating sentence $i (${text.length} chars)")
                                    val raw = EnginePool.generate(tts, text, rec.lang.code, rec.voice, rec.steps)
                                    // An empty result (e.g. text with nothing pronounceable) becomes a short pause.
                                    val samples = if (raw.isEmpty()) raw else if (rec.sampleRate == EnginePool.MODEL_SAMPLE_RATE) raw else Dsp.decimate2(raw)
                                    val silence = rec.silenceFrames(i)
                                    val pcm = Dsp.toPcm16(samples, silence)
                                    val dest = rec.segFile(i)
                                    val tmp = File(dest.path + ".tmp")
                                    tmp.writeBytes(pcm)
                                    tmp.renameTo(dest)
                                    rec.markReady(i, (pcm.size / 2).toLong())
                                    CrashReport.crumb("Saved sentence $i")
                                    chars.addAndGet(text.length.toLong())
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.e(TAG, "Sentence $i failed", e)
                                if (failures.incrementAndGet() >= 3) throw e
                            } finally {
                                synchronized(lock) { inProgress.remove(i) }
                            }
                            publish(rec, chars.get(), startedAt.get())
                        }
                    }
                }
            }
            if (chars.get() > 300) {
                val secs = (SystemClock.elapsedRealtime() - startedAt.get()) / 1000f
                if (secs > 0) Prefs.measuredCps = chars.get() / secs
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Generation of ${rec.id} paused")
        } catch (e: Throwable) {
            Log.e(TAG, "Generation failed", e)
            CrashReport.crumb("Generation failed: $e")
            lastError = e.message ?: e.javaClass.simpleName
            _paused.value = _paused.value + rec.id
        }
    }

    private fun publish(rec: Recording, chars: Long, startedAt: Long) {
        val secs = if (startedAt > 0) (SystemClock.elapsedRealtime() - startedAt) / 1000f else 0f
        val cps = if (secs > 0.5f) chars / secs else 0f
        var remaining = 0L
        for (i in 0 until rec.n) if (!rec.isReady(i)) remaining += rec.segs[i].length
        val eta = if (cps > 0) (remaining / cps).toInt() else -1
        _status.value = Status(rec.id, rec.ready.value, rec.n, cps, eta, loadingEngines = false)
    }
}

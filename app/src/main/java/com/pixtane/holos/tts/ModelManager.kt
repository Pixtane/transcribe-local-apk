package com.pixtane.holos.tts

import android.content.Context
import android.util.Log
import com.pixtane.holos.HolosApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads and tracks the Supertonic 3 model (int8 ONNX export prepared by sherpa-onnx).
 *
 * Files are fetched one by one from Hugging Face; if no mirror answers, the official
 * sherpa-onnx GitHub release tarball is used as a fallback and unpacked on the phone.
 */
object ModelManager {
    sealed interface State {
        data object Missing : State
        data class Downloading(val downloaded: Long, val total: Long, val source: String) : State
        data object Extracting : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    const val MODEL_NAME = "Supertonic 3 · int8"
    const val MODEL_ID = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"

    /** File name to expected size in bytes (used for progress and validation). */
    val FILES: Map<String, Long> = linkedMapOf(
        "tts.json" to 8_253L,
        "unicode_indexer.bin" to 262_144L,
        "voice.bin" to 517_168L,
        "duration_predictor.int8.onnx" to 3_700_147L,
        "text_encoder.int8.onnx" to 36_416_150L,
        "vocoder.int8.onnx" to 25_991_073L,
        "vector_estimator.int8.onnx" to 78_400_833L,
    )
    val TOTAL_BYTES = FILES.values.sum()

    /** Hugging Face repositories hosting the sherpa-onnx export, tried in order. */
    private val HF_REPOS = listOf(
        "csukuangfj2/$MODEL_ID",
        "csukuangfj/$MODEL_ID",
        "k2-fsa/$MODEL_ID",
    )
    private const val TARBALL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_ID.tar.bz2"
    private const val TARBALL_SIZE = 128_774_318L

    private const val MARKER = ".complete"
    private const val TAG = "ModelManager"

    lateinit var dir: File
        private set
    private lateinit var cacheDir: File

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "models/supertonic-3-int8")
        cacheDir = context.cacheDir
        _state.value = if (isReady()) State.Ready else State.Missing
    }

    fun isReady(): Boolean =
        File(dir, MARKER).exists() && FILES.keys.all { File(dir, it).length() > 0 }

    fun file(name: String) = File(dir, name).absolutePath

    fun download() {
        if (job?.isActive == true || isReady()) return
        job = HolosApp.scope.launch(Dispatchers.IO) {
            try {
                dir.mkdirs()
                if (!tryHuggingFace()) downloadTarball()
                File(dir, MARKER).writeText(MODEL_ID)
                _state.value = State.Ready
            } catch (e: CancellationException) {
                _state.value = State.Missing
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete() {
        job?.cancel()
        EnginePool.releaseAll()
        dir.deleteRecursively()
        _state.value = State.Missing
    }

    private suspend fun tryHuggingFace(): Boolean {
        for (repo in HF_REPOS) {
            val base = "https://huggingface.co/$repo/resolve/main/"
            try {
                var done = 0L
                for ((name, size) in FILES) {
                    val dest = File(dir, name)
                    if (dest.length() == size) { done += size; continue }
                    val before = done
                    httpDownload(base + name, dest) { got, _ ->
                        _state.value = State.Downloading(before + got, TOTAL_BYTES, "Hugging Face")
                    }
                    done += dest.length()
                }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Hugging Face mirror $repo unavailable: ${e.message}")
            }
        }
        return false
    }

    private suspend fun downloadTarball() {
        val tar = File(cacheDir, "$MODEL_ID.tar.bz2")
        httpDownload(TARBALL_URL, tar) { got, total ->
            _state.value = State.Downloading(got, if (total > 0) total else TARBALL_SIZE, "GitHub (sherpa-onnx)")
        }
        _state.value = State.Extracting
        val tmp = File(dir, "tmp").apply { deleteRecursively(); mkdirs() }
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(tar.inputStream(), 1 shl 16))).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name in FILES) {
                    FileOutputStream(File(tmp, name)).use { out -> input.copyTo(out, 1 shl 16) }
                }
                entry = input.nextEntry
            }
        }
        for (name in FILES.keys) {
            val src = File(tmp, name)
            if (!src.exists()) throw IOException("Archive is missing $name")
            val dest = File(dir, name)
            dest.delete()
            if (!src.renameTo(dest)) throw IOException("Could not move $name")
        }
        tmp.deleteRecursively()
        tar.delete()
    }

    /** Resumable HTTP download into [dest] via a `.part` file. */
    private suspend fun httpDownload(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Holos-Android/1.0")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && have > 0) {
                dest.delete(); part.renameTo(dest); return
            }
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            if (code == 200) have = 0L
            val len = conn.contentLengthLong
            val total = if (len > 0) have + len else -1L
            FileOutputStream(part, have > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport > 256 * 1024) {
                            lastReport = have
                            onProgress(have, total)
                        }
                    }
                }
            }
            onProgress(have, total)
            if (total > 0 && have != total) throw IOException("Connection closed early ($have of $total bytes)")
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save ${dest.name}")
        } finally {
            conn.disconnect()
        }
    }
}

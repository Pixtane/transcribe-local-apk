package com.pixtane.holos.tts

import android.content.Context
import android.util.Log
import com.pixtane.holos.HolosApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Downloads and tracks engine models. Files are fetched one by one from Hugging Face where a
 * mirror exists; otherwise (or if it fails) the official sherpa-onnx GitHub release tarball is
 * downloaded and unpacked on the phone.
 */
object ModelManager {
    sealed interface State {
        data object Missing : State
        data class Downloading(val downloaded: Long, val total: Long, val source: String) : State
        data object Extracting : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private const val MARKER = ".complete"
    private const val TAG = "ModelManager"

    private lateinit var root: File
    private lateinit var cacheDir: File
    private val states = ConcurrentHashMap<String, MutableStateFlow<State>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Bumped whenever any engine becomes ready or is deleted. */
    val changes = MutableStateFlow(0)

    fun init(context: Context) {
        root = File(context.filesDir, "models")
        cacheDir = context.cacheDir
        for (p in EngineCatalog.ALL) flow(p).value = if (isReady(p)) State.Ready else State.Missing
    }

    private fun flow(p: EnginePack) = states.getOrPut(p.id) { MutableStateFlow(State.Missing) }
    fun state(p: EnginePack): StateFlow<State> = flow(p)

    fun dir(p: EnginePack) = File(root, p.dirName)
    fun file(p: EnginePack, name: String) = File(dir(p), name).absolutePath
    fun isReady(p: EnginePack): Boolean =
        File(dir(p), MARKER).exists() && p.files.keys.all { File(dir(p), it).length() > 0 }
    fun readyPacks(): List<EnginePack> = EngineCatalog.ALL.filter { isReady(it) }

    fun download(p: EnginePack) {
        if (jobs[p.id]?.isActive == true || isReady(p)) return
        jobs[p.id] = HolosApp.scope.launch(Dispatchers.IO) {
            val st = flow(p)
            try {
                dir(p).mkdirs()
                if (!tryHuggingFace(p)) downloadTarball(p)
                File(dir(p), MARKER).writeText(p.id)
                st.value = State.Ready
                changes.value++
            } catch (e: CancellationException) {
                st.value = State.Missing
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${p.id} failed", e)
                st.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel(p: EnginePack) {
        jobs[p.id]?.cancel()
    }

    fun delete(p: EnginePack) {
        jobs[p.id]?.cancel()
        EnginePool.of(p).releaseAll()
        dir(p).deleteRecursively()
        flow(p).value = State.Missing
        changes.value++
    }

    private suspend fun tryHuggingFace(p: EnginePack): Boolean {
        if (p.hfRepos.isEmpty() || p.files.values.any { it < 0 }) return false
        val total = p.files.values.sum()
        for (repo in p.hfRepos) {
            val base = "https://huggingface.co/$repo/resolve/main/"
            try {
                var done = 0L
                for ((name, size) in p.files) {
                    val dest = File(dir(p), name)
                    if (dest.length() == size) { done += size; continue }
                    val before = done
                    httpDownload(base + name, dest) { got, _ ->
                        flow(p).value = State.Downloading(before + got, total, "Hugging Face")
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

    private suspend fun downloadTarball(p: EnginePack) {
        val tar = File(cacheDir, "${p.id}.tar.bz2")
        httpDownload(p.tarballUrl, tar) { got, total ->
            flow(p).value = State.Downloading(got, if (total > 0) total else p.approxBytes, "GitHub (sherpa-onnx)")
        }
        flow(p).value = State.Extracting
        val tmp = File(dir(p), "tmp").apply { deleteRecursively(); mkdirs() }
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(tar.inputStream(), 1 shl 16))).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name in p.files) {
                    FileOutputStream(File(tmp, name)).use { out -> input.copyTo(out, 1 shl 16) }
                }
                entry = input.nextEntry
            }
        }
        for (name in p.files.keys) {
            val src = File(tmp, name)
            if (!src.exists()) throw IOException("Archive is missing $name")
            val dest = File(dir(p), name)
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

package com.pixtane.holos.data

import android.content.Context
import android.util.Log
import com.pixtane.holos.HolosApp
import com.pixtane.holos.Lang
import com.pixtane.holos.tts.Seg
import com.pixtane.holos.tts.TextSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object RecordingRepository {
    private const val TAG = "Recordings"
    lateinit var root: File
        private set
    private val cache = ConcurrentHashMap<String, Recording>()
    private val _list = MutableStateFlow<List<Recording>>(emptyList())
    val list: StateFlow<List<Recording>> = _list

    fun init(context: Context) {
        root = File(context.filesDir, "recordings").apply { mkdirs() }
        HolosApp.scope.launch(Dispatchers.IO) { refresh() }
    }

    @Synchronized
    fun refresh() {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        _list.value = dirs.mapNotNull { get(it.name) }.sortedByDescending { it.createdAt }
    }

    fun get(id: String): Recording? {
        cache[id]?.let { return it }
        val dir = File(root, id)
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val m = JSONObject(metaFile.readText())
            val text = File(dir, "text.txt").readText()
            val arr = m.getJSONArray("segs")
            val segs = ArrayList<Seg>(arr.length() / 3)
            var i = 0
            while (i + 2 < arr.length()) {
                segs.add(Seg(arr.getInt(i), arr.getInt(i + 1), arr.getInt(i + 2) == 1)); i += 3
            }
            val rec = Recording(
                dir = dir,
                id = id,
                title = m.optString("title", "Untitled"),
                lang = Lang.of(m.optString("lang", "en")),
                voice = m.optInt("voice", 0),
                steps = m.optInt("steps", 3),
                sampleRate = m.optInt("sampleRate", 22050),
                createdAt = m.optLong("createdAt", dir.lastModified()),
                text = text,
                segs = segs,
            )
            cache.putIfAbsent(id, rec) ?: rec
        } catch (e: Exception) {
            Log.w(TAG, "Broken recording $id", e)
            null
        }
    }

    fun create(text: String, title: String, lang: Lang, voice: Int, steps: Int, sampleRate: Int): Recording {
        val id = "r" + System.currentTimeMillis().toString(36)
        val dir = File(root, id).apply { mkdirs() }
        val segs = TextSegmenter.split(text)
        File(dir, "text.txt").writeText(text)
        val flat = JSONArray()
        for (s in segs) { flat.put(s.start); flat.put(s.end); flat.put(if (s.paraEnd) 1 else 0) }
        writeMeta(dir, JSONObject()
            .put("title", title)
            .put("lang", lang.code)
            .put("voice", voice)
            .put("steps", steps)
            .put("sampleRate", sampleRate)
            .put("createdAt", System.currentTimeMillis())
            .put("segs", flat))
        val rec = get(id) ?: error("Could not create recording")
        refresh()
        return rec
    }

    fun rename(id: String, title: String) {
        val rec = get(id) ?: return
        val dir = rec.dir
        val m = JSONObject(File(dir, "meta.json").readText()).put("title", title)
        writeMeta(dir, m)
        rec.title = title
        refresh()
    }

    fun delete(id: String) {
        cache.remove(id)
        File(root, id).deleteRecursively()
        refresh()
    }

    private fun writeMeta(dir: File, json: JSONObject) {
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, "meta.json"))
    }

    fun suggestTitle(text: String): String {
        val first = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Untitled"
        return if (first.length <= 48) first else first.take(46).trimEnd() + "…"
    }
}

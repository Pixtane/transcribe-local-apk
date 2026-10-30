package com.pixtane.holos.data

import android.content.Context
import android.util.Log
import com.pixtane.holos.HolosApp
import com.pixtane.holos.Lang
import com.pixtane.holos.Prefs
import com.pixtane.holos.tts.EngineCatalog
import com.pixtane.holos.tts.EnginePack
import com.pixtane.holos.importer.Chapter
import com.pixtane.holos.importer.ChapterDetector
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
            val chapters = ArrayList<Chapter>()
            m.optJSONArray("chapters")?.let { ca ->
                for (k in 0 until ca.length()) {
                    val e = ca.getJSONArray(k)
                    chapters.add(Chapter(e.getString(0), e.getInt(1)))
                }
            }
            val rec = Recording(
                dir = dir,
                id = id,
                title = m.optString("title", "Untitled"),
                lang = Lang.of(m.optString("lang", "en")),
                voice = m.optInt("voice", 0),
                steps = m.optInt("steps", 3),
                engineId = m.optString("engine", EngineCatalog.SUPERTONIC.id),
                sampleRate = m.optInt("sampleRate", 22050),
                createdAt = m.optLong("createdAt", dir.lastModified()),
                text = text,
                segs = segs,
                chapters = chapters,
            )
            rec.smart = m.optBoolean("smart", false)
            cache.putIfAbsent(id, rec) ?: rec
        } catch (e: Exception) {
            Log.w(TAG, "Broken recording $id", e)
            null
        }
    }

    /**
     * [chapters] offsets refer to [rawText]; null means "detect headings in the text".
     */
    fun create(rawText: String, title: String, lang: Lang, voice: Int, steps: Int, pack: EnginePack, chapters: List<Chapter>? = null, smart: Boolean = false): Recording {
        // Supertonic renders at 44.1 kHz; "compact" storage halves that. VITS is already 22.05 kHz.
        val sampleRate = if (pack.sampleRate == 44_100 && Prefs.compactAudio) 22_050 else pack.sampleRate
        val id = "r" + System.currentTimeMillis().toString(36)
        val dir = File(root, id).apply { mkdirs() }
        val lead = rawText.length - rawText.trimStart().length
        val text = rawText.trim()
        val chs = (chapters?.map { it.copy(offset = it.offset - lead) } ?: ChapterDetector.detect(text))
            .filter { it.offset in 0 until text.length && it.title.isNotBlank() }
            .sortedBy { it.offset }.distinctBy { it.offset }
        val segs = TextSegmenter.split(text)
        File(dir, "text.txt").writeText(text)
        val flat = JSONArray()
        for (s in segs) { flat.put(s.start); flat.put(s.end); flat.put(if (s.paraEnd) 1 else 0) }
        writeMeta(dir, JSONObject()
            .put("title", title)
            .put("lang", lang.code)
            .put("voice", voice)
            .put("steps", steps)
            .put("engine", pack.id)
            .put("smart", smart)
            .put("sampleRate", sampleRate)
            .put("createdAt", System.currentTimeMillis())
            .put("segs", flat)
            .put("chapters", JSONArray().apply { for (c in chs) put(JSONArray().put(c.title).put(c.offset)) }))
        val rec = get(id) ?: error("Could not create recording")
        refresh()
        return rec
    }

    fun setSmart(id: String, smart: Boolean) {
        val rec = get(id) ?: return
        writeMeta(rec.dir, JSONObject(File(rec.dir, "meta.json").readText()).put("smart", smart))
        rec.smart = smart
        refresh()
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

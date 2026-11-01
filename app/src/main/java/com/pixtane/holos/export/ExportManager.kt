package com.pixtane.holos.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pixtane.holos.CrashReport
import com.pixtane.holos.data.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Runs one export at a time, outside any screen, so leaving the screen does not cancel it. */
object ExportManager {
    sealed interface State {
        data object Idle : State
        data class Running(val recordingId: String, val title: String, val progress: Float) : State
        data class Done(val recordingId: String, val title: String, val uri: Uri, val where: String, val partial: Boolean) : State
        data class Failed(val title: String, val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** True when exports go to Music/Holos automatically; otherwise the caller asks for a location. */
    val savesToMusic get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun dismiss() { if (_state.value !is State.Running) _state.value = State.Idle }

    fun cancel() { job?.cancel(); _state.value = State.Idle }

    /** Exports to Music/Holos (Android 10+) or to [target] chosen by the user. */
    fun start(context: Context, rec: Recording, target: Uri? = null) {
        if (_state.value is State.Running) return
        val app = context.applicationContext
        val partial = M4aExporter.exportableSentences(rec) < rec.n
        _state.value = State.Running(rec.id, rec.title, 0f)
        CrashReport.crumb("export ${rec.id}")
        job = scope.launch {
            var created: Uri? = null
            try {
                val (uri, where) = if (target != null) target to "" else {
                    val u = createInMusic(app, M4aExporter.fileName(rec)); created = u
                    u to "${Environment.DIRECTORY_MUSIC}/Holos"
                }
                M4aExporter.export(app, rec, uri) { p -> _state.value = State.Running(rec.id, rec.title, p) }
                if (created != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                }
                _state.value = State.Done(rec.id, rec.title, uri, where, partial)
            } catch (e: Throwable) {
                created?.let { runCatching { app.contentResolver.delete(it, null, null) } }
                if (e is kotlinx.coroutines.CancellationException) return@launch
                _state.value = State.Failed(rec.title, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun createInMusic(context: Context, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.TITLE, name.removeSuffix(".m4a"))
            put(MediaStore.Audio.Media.ARTIST, "Holos")
            put(MediaStore.Audio.Media.ALBUM, "Holos")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Holos")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return context.contentResolver.insert(collection, values) ?: error("Cannot create file in Music")
    }
}

package com.pixtane.holos.export

import com.pixtane.holos.CrashReport
import com.pixtane.holos.data.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Runs one export at a time, independent of the screen that started it. */
object ExportManager {
    sealed interface State {
        data object Idle : State
        data class Running(val recordingId: String, val title: String, val progress: Float) : State
        data class Done(val recordingId: String, val title: String, val file: File, val partial: Boolean) : State
        data class Failed(val title: String, val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun dismiss() { if (_state.value !is State.Running) _state.value = State.Idle }

    fun cancel() { job?.cancel(); _state.value = State.Idle }

    fun start(rec: Recording, target: File) {
        if (_state.value is State.Running) return
        val partial = Mp3Exporter.exportableSentences(rec) < rec.n
        _state.value = State.Running(rec.id, rec.title, 0f)
        CrashReport.crumb("export ${rec.id} -> $target")
        job = scope.launch {
            try {
                target.parentFile?.mkdirs()
                Mp3Exporter.export(rec, target) { p -> _state.value = State.Running(rec.id, rec.title, p) }
                _state.value = State.Done(rec.id, rec.title, target, partial)
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (e: Throwable) {
                _state.value = State.Failed(rec.title, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

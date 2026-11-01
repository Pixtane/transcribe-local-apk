package com.pixtane.holos

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.media3.session.MediaController
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.playback.PlayerBus
import com.pixtane.holos.playback.SegmentPlayer
import com.pixtane.holos.synth.SmartScheduler
import com.pixtane.holos.synth.SynthesisManager
import com.pixtane.holos.tts.ModelManager
import com.pixtane.holos.ui.DesktopOs
import com.pixtane.holos.ui.HolosRoot
import com.pixtane.holos.ui.Nav
import com.pixtane.holos.ui.PlayerActions
import com.pixtane.holos.ui.Screen
import com.pixtane.holos.ui.theme.HolosColors
import com.pixtane.holos.ui.theme.HolosTheme
import com.pixtane.holos.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--selftest") { SelfTest.run(args.getOrNull(1)); exitProcess(0) }
    CrashReport.install()
    NativeLibs.load()
    ModelManager.init(File(HolosApp.dataDir, "models"), HolosApp.cacheDir)
    RecordingRepository.init(File(HolosApp.dataDir, "recordings"))
    val player = SegmentPlayer()
    val controller = MediaController(player)
    HolosApp.scope.launch { delay(1_500); SmartScheduler.init() }
    Updater.init()
    // A file passed on the command line ("Open with Holos") goes straight to the create screen.
    val startFile = args.firstOrNull { File(it).isFile }

    application {
        val nav = remember { Nav(Screen.Library) }
        var dropped by remember { mutableStateOf(startFile) }
        var confirmClose by remember { mutableStateOf(false) }
        val state = rememberWindowState(size = DpSize(Prefs.windowW.dp, Prefs.windowH.dp))
        val icon = remember {
            runCatching {
                Thread.currentThread().contextClassLoader.getResourceAsStream("holos.png")!!.use { BitmapPainter(loadImageBitmap(it)) }
            }.getOrNull()
        }

        fun quit() {
            if (state.placement == WindowPlacement.Floating) {
                Prefs.windowW = state.size.width.value.toInt()
                Prefs.windowH = state.size.height.value.toInt()
            }
            player.release()
            exitApplication()
        }

        Window(
            onCloseRequest = { if (SynthesisManager.status.value.running) confirmClose = true else quit() },
            title = "Holos",
            state = state,
            icon = icon,
            onKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                val cur = PlayerBus.snapshot.value.recordingId
                when {
                    e.key == Key.MediaPlayPause && cur != null -> { PlayerActions.toggle(controller, cur); true }
                    nav.current is Screen.Player && e.key == Key.Spacebar -> {
                        PlayerActions.toggle(controller, (nav.current as Screen.Player).id); true
                    }
                    nav.current is Screen.Player && e.key == Key.DirectionLeft && cur != null -> { controller.seekBack(); true }
                    nav.current is Screen.Player && e.key == Key.DirectionRight && cur != null -> { controller.seekForward(); true }
                    e.key == Key.Escape || (e.isCtrlPressed && e.key == Key.DirectionLeft) -> { nav.pop(); nav.stack.size > 1 }
                    else -> false
                }
            },
        ) {
            remember {
                DesktopOs.window = window
                window.minimumSize = Dimension(440, 600)
                window.background = java.awt.Color(0x0A, 0x0A, 0x10)
                // Drop a PDF / EPUB / MOBI / TXT onto the window to start a new recording.
                window.dropTarget = object : DropTarget() {
                    @Synchronized
                    override fun drop(evt: DropTargetDropEvent) {
                        runCatching {
                            evt.acceptDrop(DnDConstants.ACTION_COPY)
                            val files = evt.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
                            (files.firstOrNull() as? File)?.let { dropped = it.absolutePath }
                        }
                    }
                }
                true
            }
            HolosTheme {
                HolosRoot(controller, nav, dropped) { dropped = null }
                if (confirmClose) {
                    AlertDialog(
                        onDismissRequest = { confirmClose = false },
                        title = { Text(t("Generation in progress", "Триває генерація")) },
                        text = {
                            Text(t(
                                "Closing Holos pauses it. Everything generated so far is saved, and it continues when you play the recording again.",
                                "Якщо закрити Holos, генерація призупиниться. Уже згенероване збережено, і вона продовжиться, коли ви знову ввімкнете запис.",
                            ))
                        },
                        confirmButton = { TextButton(onClick = { confirmClose = false; quit() }) { Text(t("Close anyway", "Усе одно закрити"), color = HolosColors.Danger) } },
                        dismissButton = {
                            TextButton(onClick = { confirmClose = false; state.isMinimized = true }) { Text(t("Minimize", "Згорнути")) }
                        },
                    )
                }
            }
        }
    }
}

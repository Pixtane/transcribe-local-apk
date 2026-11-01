package com.pixtane.holos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixtane.holos.Prefs
import com.pixtane.holos.data.Recording
import com.pixtane.holos.export.ExportManager
import com.pixtane.holos.export.Mp3Exporter
import com.pixtane.holos.t
import com.pixtane.holos.ui.DesktopOs
import com.pixtane.holos.ui.theme.HolosColors
import java.io.File

/** Returns a function that asks where to save and starts exporting a recording as MP3. */
@Composable
fun rememberExporter(): (Recording) -> Unit = remember {
    { rec ->
        if (Mp3Exporter.exportableSentences(rec) > 0) {
            val dir = Prefs.exportDir?.let { File(it) } ?: DesktopOs.musicDir.apply { mkdirs() }
            DesktopOs.saveFile(t("Export audio", "Експорт аудіо"), dir, Mp3Exporter.fileName(rec))?.let { target ->
                Prefs.exportDir = target.parentFile?.absolutePath
                ExportManager.start(rec, target)
            }
        }
    }
}

@Composable
fun ExportDialog() {
    val state by ExportManager.state.collectAsStateWithLifecycle()
    when (val s = state) {
        ExportManager.State.Idle -> Unit
        is ExportManager.State.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(t("Exporting audio", "Експорт аудіо")) },
            text = {
                Column {
                    Text(s.title, color = HolosColors.Muted, maxLines = 2)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ExportManager.cancel() }) { Text(t("Cancel", "Скасувати")) } },
        )
        is ExportManager.State.Done -> AlertDialog(
            onDismissRequest = { ExportManager.dismiss() },
            title = { Text(t("Audio saved", "Аудіо збережено")) },
            text = {
                Column {
                    Text(t("Saved as MP3: ${s.file.absolutePath}", "Збережено як MP3: ${s.file.absolutePath}"))
                    if (s.partial) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("Generation isn't finished, so only the generated part was exported.", "Генерацію ще не завершено, тож експортовано лише готову частину."),
                            color = HolosColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { DesktopOs.showInFolder(s.file); ExportManager.dismiss() }) { Text(t("Show in folder", "Показати в теці")) }
            },
            dismissButton = { TextButton(onClick = { ExportManager.dismiss() }) { Text(t("Close", "Закрити")) } },
        )
        is ExportManager.State.Failed -> AlertDialog(
            onDismissRequest = { ExportManager.dismiss() },
            title = { Text(t("Export failed", "Не вдалося експортувати")) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { ExportManager.dismiss() }) { Text("OK") } },
        )
    }
}

package com.pixtane.holos.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixtane.holos.data.Recording
import com.pixtane.holos.export.ExportManager
import com.pixtane.holos.export.M4aExporter
import com.pixtane.holos.t
import com.pixtane.holos.ui.theme.HolosColors

/** Returns a function that starts exporting a recording (asks for a location on Android 8–9). */
@Composable
fun rememberExporter(): (Recording) -> Unit {
    val context = LocalContext.current
    val pendingRef = androidx.compose.runtime.remember { arrayOfNulls<Recording>(1) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val rec = pendingRef[0]; pendingRef[0] = null
        if (uri != null && rec != null) ExportManager.start(context, rec, uri)
    }
    return { rec ->
        if (M4aExporter.exportableSentences(rec) == 0) {
            android.widget.Toast.makeText(context, t("Nothing generated yet", "Ще нічого не згенеровано"), android.widget.Toast.LENGTH_SHORT).show()
        } else if (ExportManager.savesToMusic) ExportManager.start(context, rec)
        else { pendingRef[0] = rec; pick.launch(M4aExporter.fileName(rec)) }
    }
}

/** Progress / result dialog for the current export. Place once per screen. */
@Composable
fun ExportDialog() {
    val context = LocalContext.current
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
                    Text(
                        if (s.where.isNotEmpty())
                            t("Saved as an M4A file in ${s.where}. It plays in any music app.", "Збережено як M4A у теці ${s.where}. Відкривається в будь-якому музичному застосунку.")
                        else t("Saved as an M4A file. It plays in any music app.", "Збережено як M4A. Відкривається в будь-якому музичному застосунку.")
                    )
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
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/mp4"
                        putExtra(Intent.EXTRA_STREAM, s.uri)
                        putExtra(Intent.EXTRA_TITLE, s.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, s.title)) }
                    ExportManager.dismiss()
                }) { Text(t("Share", "Поділитися")) }
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

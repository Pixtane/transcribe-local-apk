package com.pixtane.holos.ui.screens

import com.pixtane.holos.uiName
import com.pixtane.holos.UiLang
import com.pixtane.holos.chaptersCount
import com.pixtane.holos.t
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.pixtane.holos.data.Recording
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.playback.PlayerBus
import com.pixtane.holos.synth.SynthesisManager
import com.pixtane.holos.tts.EngineCatalog
import com.pixtane.holos.tts.ModelManager
import com.pixtane.holos.ui.Nav
import com.pixtane.holos.ui.PlayerActions
import com.pixtane.holos.ui.Screen
import com.pixtane.holos.ui.components.GradientButton
import com.pixtane.holos.ui.components.MiniPlayer
import com.pixtane.holos.ui.components.ModelCard
import com.pixtane.holos.ui.components.Pill
import com.pixtane.holos.ui.components.Waveform
import com.pixtane.holos.ui.formatHuman
import com.pixtane.holos.ui.theme.HolosColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(nav: Nav, controller: MediaController?) {
    val recordings by RecordingRepository.list.collectAsStateWithLifecycle()
    val model by ModelManager.state(EngineCatalog.SUPERTONIC).collectAsStateWithLifecycle()
    val engineChanges by ModelManager.changes.collectAsStateWithLifecycle()
    val anyEngine = remember(engineChanges, model) { ModelManager.readyPacks().isNotEmpty() }
    val snap by PlayerBus.snapshot.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Recording?>(null) }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1033), HolosColors.Bg, HolosColors.Bg)))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 200.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Holos", style = MaterialTheme.typography.displaySmall)
                        Text(t("Offline AI voice · Українська · Deutsch · English", "Офлайн ШІ-озвучення · понад 30 мов"), style = MaterialTheme.typography.bodyMedium, color = HolosColors.Muted)
                    }
                    IconButton(onClick = { nav.push(Screen.Settings) }) {
                        Icon(Icons.Rounded.Settings, t("Settings", "Налаштування"), tint = HolosColors.Muted)
                    }
                }
            }
            item { com.pixtane.holos.ui.components.UpdateBanner() }
            if (model != ModelManager.State.Ready) {
                item { ModelCard(EngineCatalog.SUPERTONIC) }
            }
            if (recordings.isEmpty()) {
                item { EmptyState(onCreate = { nav.push(Screen.Create()) }, enabled = anyEngine) }
            } else {
                item {
                    Text(
                        t("Recordings", "Записи"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(recordings, key = { it.id }) { rec ->
                    Box {
                        RecordingCard(
                            rec = rec,
                            isCurrent = snap.recordingId == rec.id,
                            isPlaying = snap.recordingId == rec.id && snap.playWhenReady,
                            onOpen = { nav.push(Screen.Player(rec.id)) },
                            onMenu = { menuFor = rec },
                        )
                        DropdownMenu(expanded = menuFor?.id == rec.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text(t("Rename", "Перейменувати")) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { renaming = rec; menuFor = null },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Duplicate", "Дублювати")) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = { menuFor = null; nav.push(Screen.Create(duplicateOf = rec.id)) },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Delete", "Видалити"), color = HolosColors.Danger) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = HolosColors.Danger) },
                                onClick = { deleting = rec; menuFor = null },
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            GradientButton(
                t("New recording", "Новий запис"),
                onClick = { nav.push(Screen.Create()) },
                enabled = anyEngine,
                leading = { Icon(Icons.Rounded.Add, null, tint = Color.White) },
            )
            val cur = snap.recordingId?.let { id -> recordings.firstOrNull { it.id == id } }
            if (cur != null) {
                Spacer(Modifier.height(12.dp))
                var pos by remember { mutableLongStateOf(0L) }
                LaunchedEffect(cur.id) { while (true) { pos = PlayerBus.positionMs(); delay(500) } }
                val total = cur.totalMs().coerceAtLeast(1)
                MiniPlayer(
                    title = cur.title,
                    subtitle = "${cur.lang.flag} ${cur.lang.uiName()}",
                    progress = pos.toFloat() / total,
                    playing = snap.playWhenReady,
                    buffering = snap.isBuffering,
                    accent = cur.lang.accent,
                    onToggle = { PlayerActions.toggle(controller, cur.id) },
                    onOpen = { nav.push(Screen.Player(cur.id)) },
                )
            }
        }
    }

    renaming?.let { rec ->
        var title by remember(rec.id) { mutableStateOf(rec.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t("Rename", "Перейменувати")) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { RecordingRepository.rename(rec.id, title.ifBlank { rec.title }); renaming = null }) { Text(t("Save", "Зберегти")) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t("Cancel", "Скасувати")) } },
        )
    }
    deleting?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("Delete recording?", "Видалити запис?")) },
            text = { Text(t("“${rec.title}” and its audio will be removed from this phone.", "«${rec.title}» і його аудіо буде видалено з телефону.")) },
            confirmButton = {
                TextButton(onClick = {
                    SynthesisManager.cancel(rec.id)
                    if (PlayerBus.snapshot.value.recordingId == rec.id) controller?.clearMediaItems()
                    RecordingRepository.delete(rec.id)
                    deleting = null
                }) { Text(t("Delete", "Видалити"), color = HolosColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("Cancel", "Скасувати")) } },
        )
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit, enabled: Boolean) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = HolosColors.Surface,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(HolosColors.accentBrush),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(t("Turn any text into speech", "Перетворіть будь-який текст на мовлення"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                t("Paste an article or a chapter, or import a PDF, EPUB, MOBI or TXT file. Holos reads it aloud with natural voices and highlights every word as it goes.", "Вставте статтю чи розділ або імпортуйте PDF, EPUB, MOBI чи TXT. Holos прочитає текст природним голосом і підсвічуватиме кожне слово."),
                style = MaterialTheme.typography.bodyMedium, color = HolosColors.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            GradientButton(t("Create first recording", "Створити перший запис"), onClick = onCreate, enabled = enabled)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecordingCard(rec: Recording, isCurrent: Boolean, isPlaying: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val ready by rec.ready.collectAsState()
    val synth by SynthesisManager.status.collectAsStateWithLifecycle()
    val pausedSet by SynthesisManager.paused.collectAsStateWithLifecycle()
    val generating = synth.recordingId == rec.id
    val complete = ready >= rec.n
    val totalMs = remember(ready) { rec.totalMs() }
    val listened = remember(isCurrent) { rec.savedPositionMs }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isCurrent) HolosColors.SurfaceHigh else HolosColors.Surface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).combinedClickable(onClick = onOpen, onLongClick = onMenu),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(rec.lang.deep, HolosColors.VioletDeep))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPlaying) Waveform(Color.White, Modifier.size(24.dp), animate = true)
                    else Text(rec.lang.flag, fontSize = 22.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(rec.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = (if (rec.chapters.isNotEmpty()) "${chaptersCount(rec.chapters.size)} · " else "") + rec.text.take(140).replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, t("More", "Більше"), tint = HolosColors.Muted) }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Pill(rec.lang.uiName(), rec.lang.accent)
                Pill(voiceTag(rec), if (rec.pack.voices.size > 1 && rec.pack.voices.getOrNull(rec.voice)?.code?.startsWith("F") == true) HolosColors.Pink else HolosColors.Violet)
                Pill((if (complete) "" else "~") + formatHuman(totalMs), HolosColors.Violet)
                when {
                    complete -> Pill(t("Ready", "Готово"), HolosColors.Success)
                    generating -> Pill(t("Generating ${ready * 100 / rec.n.coerceAtLeast(1)}%", "Генерація ${ready * 100 / rec.n.coerceAtLeast(1)}%"), HolosColors.Pink)
                    SynthesisManager.waitingForCharge(rec) -> Pill(t("Waiting for charging", "Очікує заряджання"), HolosColors.Muted)
                    rec.id in pausedSet -> Pill(t("Paused ${ready * 100 / rec.n.coerceAtLeast(1)}%", "Пауза ${ready * 100 / rec.n.coerceAtLeast(1)}%"), HolosColors.Muted)
                    else -> Pill(t("${ready * 100 / rec.n.coerceAtLeast(1)}% generated", "Згенеровано ${ready * 100 / rec.n.coerceAtLeast(1)}%"), HolosColors.Muted)
                }
                Text(
                    DateFormat.getDateInstance(DateFormat.SHORT, if (UiLang.isUk) java.util.Locale("uk") else java.util.Locale.ENGLISH).format(Date(rec.createdAt)),
                    style = MaterialTheme.typography.labelSmall, color = HolosColors.Faint,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            if (!complete || listened > 0) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.padding(end = 12.dp)) {
                    LinearProgressIndicator(
                        progress = { if (complete) (listened.toFloat() / totalMs.coerceAtLeast(1)) else ready.toFloat() / rec.n.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = if (complete) rec.lang.accent else HolosColors.Pink,
                        trackColor = Color.White.copy(alpha = 0.07f),
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}

/** "F2 · Жін." for Supertonic voices, the engine name for single-voice engines. */
private fun voiceTag(rec: Recording): String {
    val v = rec.pack.voices.getOrNull(rec.voice)
    if (rec.pack.voices.size <= 1 || v == null) return rec.pack.family
    val female = v.code.startsWith("F")
    return v.code + " · " + if (female) t("Female", "Жін.") else t("Male", "Чол.")
}

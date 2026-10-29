package com.pixtane.holos.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    val model by ModelManager.state.collectAsStateWithLifecycle()
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
                        Text("Offline AI voice · Українська · Deutsch · English", style = MaterialTheme.typography.bodyMedium, color = HolosColors.Muted)
                    }
                    IconButton(onClick = { nav.push(Screen.Settings) }) {
                        Icon(Icons.Rounded.Settings, "Settings", tint = HolosColors.Muted)
                    }
                }
            }
            if (model != ModelManager.State.Ready) {
                item { ModelCard(model) }
            }
            if (recordings.isEmpty()) {
                item { EmptyState(onCreate = { nav.push(Screen.Create()) }, enabled = model == ModelManager.State.Ready) }
            } else {
                item {
                    Text(
                        "Recordings",
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
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { renaming = rec; menuFor = null },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = HolosColors.Danger) },
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
                "New recording",
                onClick = { nav.push(Screen.Create()) },
                enabled = model == ModelManager.State.Ready,
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
                    subtitle = "${cur.lang.flag} ${cur.lang.native}",
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
            title = { Text("Rename") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { RecordingRepository.rename(rec.id, title.ifBlank { rec.title }); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete recording?") },
            text = { Text("“${rec.title}” and its audio will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    SynthesisManager.cancel(rec.id)
                    if (PlayerBus.snapshot.value.recordingId == rec.id) controller?.clearMediaItems()
                    RecordingRepository.delete(rec.id)
                    deleting = null
                }) { Text("Delete", color = HolosColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
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
            Text("Turn any text into speech", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste an article, a chapter or import a .txt file. Holos reads it aloud with natural voices and highlights every word as it goes.",
                style = MaterialTheme.typography.bodyMedium, color = HolosColors.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            GradientButton("Create first recording", onClick = onCreate, enabled = enabled)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
                        text = (if (rec.chapters.isNotEmpty()) "${rec.chapters.size} chapters · " else "") + rec.text.take(140).replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "More", tint = HolosColors.Muted) }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(rec.lang.native, rec.lang.accent)
                Pill((if (complete) "" else "~") + formatHuman(totalMs), HolosColors.Violet)
                when {
                    complete -> Pill("Ready", HolosColors.Success)
                    generating -> Pill("Generating ${ready * 100 / rec.n.coerceAtLeast(1)}%", HolosColors.Pink)
                    rec.id in pausedSet -> Pill("Paused ${ready * 100 / rec.n.coerceAtLeast(1)}%", HolosColors.Muted)
                    else -> Pill("${ready * 100 / rec.n.coerceAtLeast(1)}% generated", HolosColors.Muted)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(rec.createdAt)),
                    style = MaterialTheme.typography.labelSmall, color = HolosColors.Faint,
                    modifier = Modifier.padding(end = 12.dp),
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

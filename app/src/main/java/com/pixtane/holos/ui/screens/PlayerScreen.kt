package com.pixtane.holos.ui.screens

import com.pixtane.holos.sentencesCount
import com.pixtane.holos.chaptersCount
import com.pixtane.holos.uiName
import com.pixtane.holos.t
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.pixtane.holos.data.Recording
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.playback.PlayerBus
import com.pixtane.holos.synth.SynthesisManager
import com.pixtane.holos.ui.Nav
import com.pixtane.holos.ui.PlayerActions
import com.pixtane.holos.ui.components.GenerationSeekBar
import com.pixtane.holos.ui.formatBytes
import com.pixtane.holos.ui.formatDuration
import com.pixtane.holos.ui.theme.HolosColors
import com.pixtane.holos.ui.theme.LyricsStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class Para(val index: Int, val first: Int, val last: Int, val start: Int, val end: Int, val display: String)

private fun buildParagraphs(rec: Recording): List<Para> {
    val out = ArrayList<Para>()
    var first = 0
    for (i in 0 until rec.n) {
        if (rec.segs[i].paraEnd || i == rec.n - 1) {
            val start = rec.segs[first].start
            val end = rec.segs[i].end
            val chars = rec.text.substring(start, end).toCharArray()
            for (k in chars.indices) if (chars[k] == '\n' || chars[k] == '\r' || chars[k] == '\t') chars[k] = ' '
            out.add(Para(out.size, first, i, start, end, String(chars)))
            first = i + 1
        }
    }
    return out
}

private fun speedLabel(s: Float): String =
    String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.') + "×"

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

@Composable
fun PlayerScreen(nav: Nav, controller: MediaController?, id: String) {
    val rec = remember(id) { RecordingRepository.get(id) }
    if (rec == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val scope = rememberCoroutineScope()
    val ready by rec.ready.collectAsState()
    val snap by PlayerBus.snapshot.collectAsStateWithLifecycle()
    val synth by SynthesisManager.status.collectAsStateWithLifecycle()
    val pausedSet by SynthesisManager.paused.collectAsStateWithLifecycle()
    val isCurrent = snap.recordingId == id
    val accent = rec.lang.accent

    val paras = remember(rec) { buildParagraphs(rec) }
    val paraOfSeg = remember(rec) {
        IntArray(rec.n).also { arr -> for (p in paras) for (s in p.first..p.last) arr[s] = p.index }
    }

    // Keep generating while the page is open (unless the user paused it).
    LaunchedEffect(id) {
        if (!rec.isComplete && id !in SynthesisManager.paused.value && !SynthesisManager.isQueuedOrActive(id)) {
            SynthesisManager.enqueue(id)
        }
    }

    var pos by remember { mutableLongStateOf(if (isCurrent) PlayerBus.positionMs() else rec.savedPositionMs) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(isCurrent, snap.playWhenReady) {
        while (true) {
            pos = if (isCurrent) PlayerBus.positionMs() else rec.savedPositionMs
            if (isCurrent && snap.playWhenReady) withFrameMillis { } else delay(200)
        }
    }

    val totalMs = remember(ready) { rec.totalMs() }
    val curSeg by remember { derivedStateOf { rec.segmentAt(rec.msToFrames(pos)) } }
    val spokenEnd by remember {
        derivedStateOf {
            val s = rec.segs.getOrNull(curSeg) ?: return@derivedStateOf 0
            val startF = rec.startFrame(curSeg)
            val speech = (rec.estimatedFrames(curSeg) - rec.silenceFrames(curSeg)).coerceAtLeast(1)
            val frac = ((rec.msToFrames(pos) - startF).toDouble() / speech).coerceIn(0.0, 1.0)
            var idx = s.start + (frac * s.length).toInt()
            // Snap forward to the end of the word being spoken.
            while (idx < s.end && !rec.text[idx].isWhitespace()) idx++
            if (frac <= 0.0) s.start else idx
        }
    }
    val readyRanges = remember(ready, totalMs) {
        val ranges = ArrayList<ClosedFloatingPointRange<Float>>()
        val total = rec.totalFrames().coerceAtLeast(1).toFloat()
        var i = 0
        while (i < rec.n) {
            if (rec.isReady(i)) {
                val a = i
                while (i < rec.n && rec.isReady(i)) i++
                ranges.add(rec.startFrame(a) / total..rec.startFrame(i) / total)
            } else i++
        }
        ranges
    }

    fun seekTo(ms: Long) {
        val t = ms.coerceIn(0, totalMs)
        if (isCurrent) controller?.seekTo(t) else { rec.savedPositionMs = t; pos = t }
    }

    fun playSentence(i: Int) {
        val ms = rec.startMs(i.coerceIn(0, rec.n - 1))
        if (isCurrent) {
            controller?.seekTo(ms)
            if (!snap.playWhenReady) controller?.play()
        } else {
            rec.savedPositionMs = ms; pos = ms
            PlayerActions.play(controller, id)
        }
    }

    // ---- Lyrics follow / auto-scroll ----
    val listState = rememberLazyListState()
    val layouts = remember { HashMap<Int, TextLayoutResult>() }
    var follow by remember { mutableStateOf(true) }
    var programmatic by remember { mutableStateOf(false) }
    var userScrollTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !programmatic) { follow = false; userScrollTick++ }
        }
    }
    LaunchedEffect(follow, userScrollTick, snap.playWhenReady) {
        if (!follow && snap.playWhenReady && isCurrent) {
            delay(6000)
            if (!listState.isScrollInProgress) follow = true
        }
    }
    LaunchedEffect(curSeg, follow, paras) {
        if (!follow || rec.n == 0) return@LaunchedEffect
        val pi = paraOfSeg[curSeg.coerceIn(0, rec.n - 1)]
        val item = pi + 1
        val layout = layouts[pi]
        val lineTop = layout?.let { l ->
            val off = (rec.segs[curSeg].start - paras[pi].start).coerceIn(0, l.layoutInput.text.length)
            l.getLineTop(l.getLineForOffset(off))
        } ?: 0f
        val anchor = listState.layoutInfo.viewportSize.height * 0.26f
        programmatic = true
        try {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
            if (info != null) {
                listState.animateScrollBy(info.offset + lineTop - anchor, tween(500))
            } else {
                listState.scrollToItem(item)
                listState.animateScrollBy(lineTop - anchor, tween(300))
            }
        } finally {
            programmatic = false
        }
    }

    var menu by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    val chapterIdx by remember { derivedStateOf { rec.chapterAt(curSeg) } }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(rec.lang.deep.copy(alpha = 0.9f), Color(0xFF120E22), HolosColors.Bg)))
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            // Top bar
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("Back", "Назад")) }
                Column(Modifier.weight(1f)) {
                    Text(t("NOW READING", "ЗАРАЗ ЧИТАЄТЬСЯ"), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), letterSpacing = 1.5.sp)
                    Text(rec.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chapterIdx >= 0) {
                        Text(
                            rec.chapters[chapterIdx].title,
                            style = MaterialTheme.typography.labelMedium, color = accent,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showChapters = true },
                        )
                    }
                }
                IconButton(onClick = { showChapters = true }) {
                    Icon(
                        if (rec.chapters.isEmpty()) Icons.Rounded.Search else Icons.AutoMirrored.Rounded.List,
                        if (rec.chapters.isEmpty()) t("Search", "Пошук") else t("Chapters", "Розділи"), tint = Color.White,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, t("More", "Більше")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(t("Delete", "Видалити"), color = HolosColors.Danger) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = HolosColors.Danger) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }

            // Lyrics
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent, 0.07f to Color.Black,
                                    0.88f to Color.Black, 1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    item(key = "header") {
                        val voiceLabel = if (rec.pack.voices.size > 1) t("Voice", "Голос") + " ${rec.pack.voices.getOrNull(rec.voice)?.code ?: "?"}" else rec.pack.family
                        Text(
                            "${rec.lang.flag}  ${rec.lang.uiName()} · $voiceLabel · " +
                                (if (rec.chapters.isNotEmpty()) chaptersCount(rec.chapters.size) else sentencesCount(rec.n)),
                            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f),
                        )
                    }
                    itemsIndexed(paras, key = { _, p -> p.index }) { _, p ->
                        val inside = curSeg in p.first..p.last
                        val segKey = when {
                            curSeg < p.first -> -1
                            curSeg > p.last -> Int.MAX_VALUE
                            else -> curSeg
                        }
                        ParagraphText(
                            rec = rec, para = p, curSeg = segKey,
                            spokenEnd = if (inside) spokenEnd else 0,
                            ready = ready, accent = accent,
                            onLayout = { layouts[p.index] = it },
                            onTapSentence = { playSentence(it) },
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = !follow,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                ) {
                    Surface(
                        shape = CircleShape, color = Color.White, contentColor = Color.Black,
                        modifier = Modifier.clip(CircleShape).clickable { follow = true },
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("Back to current line", "До поточного рядка"), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // Controls
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 12.dp)
            ) {
                GenerationStatus(rec, ready, synth, id in pausedSet)
                Spacer(Modifier.height(6.dp))
                GenerationSeekBar(
                    positionMs = pos, durationMs = totalMs, readyRanges = readyRanges, accent = accent,
                    onSeek = { seekTo(it) }, onScrub = { scrubMs = it },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDuration(scrubMs ?: pos), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    Text(
                        (if (rec.isComplete) "" else "~") + formatDuration(totalMs),
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SpeedButton(snap.speed) { s -> controller?.setPlaybackSpeed(s) }
                    IconButton(onClick = {
                        val st = rec.startMs(curSeg)
                        playSentence(if (pos - st > 2000) curSeg else curSeg - 1)
                    }) { Icon(Icons.Rounded.SkipPrevious, t("Previous sentence", "Попереднє речення"), Modifier.size(30.dp)) }
                    IconButton(onClick = { seekTo(pos - 10_000) }) { Icon(Icons.Rounded.Replay10, t("Back 10 seconds", "Назад на 10 секунд"), Modifier.size(30.dp)) }
                    val ended = isCurrent && snap.state == androidx.media3.common.Player.STATE_ENDED
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(Color.White)
                            .clickable { PlayerActions.toggle(controller, id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isCurrent && snap.isBuffering -> CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                            ended -> Icon(Icons.Rounded.Replay, t("Replay", "Знову"), tint = Color.Black, modifier = Modifier.size(38.dp))
                            isCurrent && snap.playWhenReady -> Icon(Icons.Rounded.Pause, t("Pause", "Пауза"), tint = Color.Black, modifier = Modifier.size(40.dp))
                            else -> Icon(Icons.Rounded.PlayArrow, t("Play", "Відтворити"), tint = Color.Black, modifier = Modifier.size(42.dp))
                        }
                    }
                    IconButton(onClick = { seekTo(pos + 10_000) }) { Icon(Icons.Rounded.Forward10, t("Forward 10 seconds", "Вперед на 10 секунд"), Modifier.size(30.dp)) }
                    IconButton(onClick = { playSentence(curSeg + 1) }) { Icon(Icons.Rounded.SkipNext, t("Next sentence", "Наступне речення"), Modifier.size(30.dp)) }
                    Box(Modifier.width(52.dp))
                }
            }
        }
    }

    if (showChapters) {
        ChaptersSheet(
            rec = rec, currentSeg = curSeg, ready = ready, accent = accent,
            onJump = { playSentence(it) }, onDismiss = { showChapters = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("Delete recording?", "Видалити запис?")) },
            text = { Text(t("“${rec.title}” and its audio will be removed from this phone.", "«${rec.title}» і його аудіо буде видалено з телефону.")) },
            confirmButton = {
                TextButton(onClick = {
                    SynthesisManager.cancel(id)
                    if (isCurrent) controller?.clearMediaItems()
                    RecordingRepository.delete(id)
                    confirmDelete = false
                    nav.pop()
                }) { Text(t("Delete", "Видалити"), color = HolosColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("Cancel", "Скасувати")) } },
        )
    }
}

@Composable
private fun ParagraphText(
    rec: Recording,
    para: Para,
    curSeg: Int,
    spokenEnd: Int,
    ready: Int,
    accent: Color,
    onLayout: (TextLayoutResult) -> Unit,
    onTapSentence: (Int) -> Unit,
) {
    val past = SpanStyle(color = Color.White.copy(alpha = 0.46f))
    val future = SpanStyle(color = Color.White.copy(alpha = 0.30f))
    val notYet = SpanStyle(color = Color.White.copy(alpha = 0.15f))
    val spoken = SpanStyle(color = Color.White, background = accent.copy(alpha = 0.22f))
    val rest = SpanStyle(color = Color.White.copy(alpha = 0.72f), background = accent.copy(alpha = 0.07f))

    val annotated = remember(para.index, curSeg, spokenEnd, ready, accent) {
        buildAnnotatedString {
            append(para.display)
            for (si in para.first..para.last) {
                val s = rec.segs[si]
                val a = s.start - para.start
                val b = s.end - para.start
                when {
                    si < curSeg -> addStyle(past, a, b)
                    si == curSeg -> {
                        val sp = (spokenEnd - para.start).coerceIn(a, b)
                        if (sp > a) addStyle(spoken, a, sp)
                        if (b > sp) addStyle(rest, sp, b)
                    }
                    !rec.isReady(si) -> addStyle(notYet, a, b)
                    else -> addStyle(future, a, b)
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = LyricsStyle,
        color = Color.White,
        onTextLayout = { layout = it; onLayout(it) },
        modifier = Modifier.fillMaxWidth().pointerInput(para.index) {
            detectTapGestures { o ->
                val l = layout ?: return@detectTapGestures
                val off = l.getOffsetForPosition(o) + para.start
                var target = para.first
                for (si in para.first..para.last) if (rec.segs[si].start <= off) target = si
                onTapSentence(target)
            }
        },
    )
}

@Composable
private fun GenerationStatus(rec: Recording, ready: Int, synth: SynthesisManager.Status, paused: Boolean) {
    val complete = ready >= rec.n
    val active = synth.recordingId == rec.id
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        if (complete) {
            Text(
                t("Saved · ${formatBytes(rec.audioBytesOnDisk())} · plays offline", "Збережено · ${formatBytes(rec.audioBytesOnDisk())} · грає офлайн"),
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f),
            )
            return@Row
        }
        val pct = ready * 100 / rec.n.coerceAtLeast(1)
        Column(Modifier.weight(1f)) {
            val label = when {
                active && synth.loadingEngines -> t("Loading voice engines… $pct%", "Завантаження рушіїв… $pct%")
                active -> buildString {
                    append(t("Generating $pct%", "Генерація $pct%"))
                    if (synth.charsPerSec > 0) append(t(" · ${synth.charsPerSec.toInt()} chars/s", " · ${synth.charsPerSec.toInt()} симв/с"))
                    if (synth.etaSec >= 0) append(t(" · ${formatDuration(synth.etaSec * 1000L)} left", " · залишилось ${formatDuration(synth.etaSec * 1000L)}"))
                }
                paused -> t("Generation paused at $pct%", "Генерацію призупинено на $pct%")
                SynthesisManager.isQueuedOrActive(rec.id) -> t("Waiting in queue · $pct%", "У черзі · $pct%")
                else -> t("$pct% generated", "Згенеровано $pct%")
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            synth.error?.takeIf { paused }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = HolosColors.Danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = {
            if (active || SynthesisManager.isQueuedOrActive(rec.id)) SynthesisManager.pause(rec.id) else SynthesisManager.enqueue(rec.id)
        }) {
            Text(if (active || SynthesisManager.isQueuedOrActive(rec.id)) t("Pause", "Пауза") else t("Resume", "Продовжити"), color = Color.White)
        }
    }
}

@Composable
private fun SpeedButton(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.10f),
            modifier = Modifier.width(52.dp).clip(RoundedCornerShape(12.dp)).clickable { open = true },
        ) {
            Text(
                speedLabel(speed),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in SPEEDS) {
                DropdownMenuItem(
                    text = { Text(speedLabel(s), fontWeight = if (s == speed) FontWeight.Bold else null) },
                    onClick = { onSpeed(s); open = false },
                )
            }
        }
    }
}

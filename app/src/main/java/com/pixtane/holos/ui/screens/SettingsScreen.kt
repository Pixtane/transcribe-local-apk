package com.pixtane.holos.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixtane.holos.Lang
import com.pixtane.holos.Prefs
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.synth.SynthesisManager
import com.pixtane.holos.tts.EngineCatalog
import com.pixtane.holos.tts.EnginePack
import com.pixtane.holos.tts.EnginePool
import com.pixtane.holos.tts.ModelManager
import com.pixtane.holos.ui.CHARS_PER_PAGE
import com.pixtane.holos.ui.Nav
import com.pixtane.holos.ui.components.GradientButton
import com.pixtane.holos.ui.components.ModelCard
import com.pixtane.holos.ui.components.SectionLabel
import com.pixtane.holos.ui.components.SelectableCard
import com.pixtane.holos.ui.formatBytes
import com.pixtane.holos.ui.formatDuration
import com.pixtane.holos.ui.theme.HolosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private val BENCH_TEXT = listOf(
    "Штучний інтелект змінює світ навколо нас, і сьогодні синтез мовлення працює прямо на телефоні.",
    "Ваші тексти залишаються приватними, а озвучення доступне будь-де: у метро, в літаку чи в горах.",
    "Сучасні моделі звучать природно, правильно ставлять наголоси і впевнено читають довгі речення.",
    "Достатньо вставити текст, вибрати голос, і через кілька секунд можна слухати перший абзац.",
    "Поки ви слухаєте початок, решта тексту генерується у фоновому режимі навіть із вимкненим екраном.",
    "Прогрес зберігається після кожного речення, тож нічого не втрачається, якщо застосунок закриють.",
    "Для німецької та англійської мов використовується та сама модель із тими самими десятьма голосами.",
    "Швидкість залежить від кількості паралельних рушіїв і кількості кроків генерації для кожного речення.",
)

@Composable
fun SettingsScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    val model by ModelManager.state(EngineCatalog.SUPERTONIC).collectAsStateWithLifecycle()
    val synth by SynthesisManager.status.collectAsStateWithLifecycle()
    val recordings by RecordingRepository.list.collectAsStateWithLifecycle()

    var steps by remember { mutableIntStateOf(Prefs.steps) }
    var workers by remember { mutableFloatStateOf(Prefs.workers.toFloat()) }
    var threads by remember { mutableFloatStateOf(Prefs.threadsPerWorker.toFloat()) }
    var compact by remember { mutableStateOf(Prefs.compactAudio) }
    var benchRunning by remember { mutableStateOf(false) }
    var benchResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(HolosColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            SectionLabel("Default quality")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((s, label) in QUALITY_PRESETS) {
                    SelectableCard(selected = steps == s, onClick = { steps = s; Prefs.steps = s }, modifier = Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            Text("$s steps", style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
                        }
                    }
                }
            }
            Text(
                "Supertonic is a flow-matching model: each step refines the audio. 2 steps is ~2× faster than 5 with slightly rougher sound.",
                style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("Performance")
            Card {
                LabeledSlider(
                    "Parallel engines", "${workers.toInt()}",
                    "Each engine works on a different sentence (~200 MB RAM each). 4 suits an 8-core phone like the Nothing Phone 3a.",
                    workers, 1f..6f, 4,
                ) { workers = it; Prefs.workers = it.toInt() }
                Spacer(Modifier.height(16.dp))
                LabeledSlider(
                    "Threads per engine", "${threads.toInt()}",
                    "More threads rarely help; parallel engines scale better.",
                    threads, 1f..4f, 2,
                ) { threads = it; Prefs.threadsPerWorker = it.toInt() }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compact audio", style = MaterialTheme.typography.titleSmall)
                        Text("Store speech at 22 kHz (half the size, near-identical for voice)", style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
                    }
                    Switch(
                        checked = compact, onCheckedChange = { compact = it; Prefs.compactAudio = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = HolosColors.VioletDeep),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Speed test")
            Card {
                val cps = Prefs.measuredCps
                Text(
                    if (cps > 0) "Last measured: ${cps.toInt()} characters/s → 10 pages in ~${formatDuration((10 * CHARS_PER_PAGE / cps * 1000).toLong())}"
                    else "Measure how fast this phone generates speech with the current settings.",
                    style = MaterialTheme.typography.bodyMedium, color = HolosColors.Muted,
                )
                benchResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = HolosColors.Text)
                }
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = if (benchRunning) "Measuring…" else "Run speed test",
                    enabled = !benchRunning && !synth.running && model == ModelManager.State.Ready,
                    modifier = Modifier.fillMaxWidth(),
                    leading = {
                        if (benchRunning) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Speed, null, tint = Color.White)
                    },
                    onClick = {
                        benchRunning = true; benchResult = null
                        scope.launch {
                            benchResult = try { runBenchmark(steps) } catch (e: Throwable) { "Failed: ${e.message}" }
                            benchRunning = false
                        }
                    },
                )
                if (synth.running) {
                    Text("Available when no recording is being generated.", style = MaterialTheme.typography.bodySmall, color = HolosColors.Faint, modifier = Modifier.padding(top = 6.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Voice engines")
            EnginesSection(synth.running)

            Spacer(Modifier.height(24.dp))
            SectionLabel("About")
            AboutSection()

            Spacer(Modifier.height(24.dp))
            SectionLabel("Storage")
            Card {
                val bytes = remember(recordings) { recordings.sumOf { it.audioBytesOnDisk() } }
                Text("${recordings.size} recordings · ${formatBytes(bytes)} of audio", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

}

private suspend fun runBenchmark(steps: Int): String = withContext(Dispatchers.Default) {
    val workers = Prefs.workers
    val pool = EnginePool.of(EngineCatalog.SUPERTONIC)
    pool.configure(workers, Prefs.threadsPerWorker)
    val loadStart = SystemClock.elapsedRealtime()
    // Warm-up: make sure every engine is loaded so the timing below is pure generation.
    coroutineScope {
        (0 until workers).map {
            async { pool.withEngine { tts -> pool.generate(tts, "Привіт.", Lang.UK.code, 0, 1) } }
        }.awaitAll()
    }
    val loadMs = SystemClock.elapsedRealtime() - loadStart
    val next = AtomicInteger(0)
    val texts = BENCH_TEXT + BENCH_TEXT
    val chars = texts.sumOf { it.length }
    val t0 = SystemClock.elapsedRealtime()
    var audioSamples = 0L
    coroutineScope {
        (0 until workers).map {
            async {
                var local = 0L
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= texts.size) break
                    local += pool.withEngine { tts -> pool.generate(tts, texts[i], Lang.UK.code, 0, steps).size }
                }
                local
            }
        }.awaitAll().forEach { audioSamples += it }
    }
    val secs = (SystemClock.elapsedRealtime() - t0) / 1000f
    val cps = chars / secs
    Prefs.measuredCps = cps
    val audioSec = audioSamples / pool.pack.sampleRate.toFloat()
    String.format(
        Locale.US,
        "%d chars in %.1f s → %.0f chars/s (%.0f× real time). 10 pages ≈ %s. Engines loaded in %.1f s.",
        chars, secs, cps, audioSec / secs, formatDuration((10 * CHARS_PER_PAGE / cps * 1000).toLong()), loadMs / 1000f,
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = HolosColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun LabeledSlider(
    title: String, value: String, hint: String,
    current: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = HolosColors.Violet)
    }
    Slider(
        value = current, onValueChange = { onChange(Math.round(it).toFloat()) },
        valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = HolosColors.Violet, inactiveTrackColor = HolosColors.SurfaceHigh),
    )
    Text(hint, style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
}

@Composable
private fun EnginesSection(busy: Boolean) {
    var expandCoqui by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EngineRow(EngineCatalog.SUPERTONIC, busy)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expandCoqui = !expandCoqui }) {
                Column(Modifier.weight(1f)) {
                    Text("Coqui VITS voices", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Very fast single-voice models, one per language (${EngineCatalog.COQUI.size} languages, ~67 MB each). " +
                            "A lighter alternative to Supertonic.",
                        style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
                    )
                }
                Icon(if (expandCoqui) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (expandCoqui) {
                Spacer(Modifier.height(8.dp))
                for (p in EngineCatalog.COQUI) CompactEngineRow(p, busy)
            }
        }
    }
}

@Composable
private fun EngineRow(pack: EnginePack, busy: Boolean) {
    val state by ModelManager.state(pack).collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    if (state != ModelManager.State.Ready) {
        ModelCard(pack)
    } else Card {
        Text(pack.name, style = MaterialTheme.typography.titleSmall)
        Text(pack.blurb, style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
        Text(pack.license, style = MaterialTheme.typography.labelSmall, color = HolosColors.Faint, modifier = Modifier.padding(top = 4.dp))
        Text("On disk: ${formatBytes(pack.approxBytes)}", style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
        TextButton(onClick = { confirm = true }, enabled = !busy) { Text("Delete", color = HolosColors.Danger) }
    }
    if (confirm) DeleteEngineDialog(pack) { confirm = false }
}

@Composable
private fun CompactEngineRow(pack: EnginePack, busy: Boolean) {
    val state by ModelManager.state(pack).collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val lang = com.pixtane.holos.Lang.of(pack.languages.first())
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(lang.flag, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(lang.native, style = MaterialTheme.typography.bodyMedium)
            val sub = when (val st = state) {
                is ModelManager.State.Downloading -> "${formatBytes(st.downloaded)} of ${formatBytes(st.total)}"
                ModelManager.State.Extracting -> "Unpacking…"
                is ModelManager.State.Failed -> "Failed: ${st.message}"
                ModelManager.State.Ready -> "Installed"
                ModelManager.State.Missing -> formatBytes(pack.approxBytes)
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = if (state is ModelManager.State.Failed) HolosColors.Danger else HolosColors.Muted)
        }
        when (state) {
            ModelManager.State.Ready -> TextButton(onClick = { confirm = true }, enabled = !busy) { Text("Delete", color = HolosColors.Danger) }
            is ModelManager.State.Downloading, ModelManager.State.Extracting ->
                TextButton(onClick = { ModelManager.cancel(pack) }) { Text("Cancel") }
            else -> TextButton(onClick = { ModelManager.download(pack) }) { Text("Download", color = HolosColors.Violet) }
        }
    }
    if (confirm) DeleteEngineDialog(pack) { confirm = false }
}

@Composable
private fun DeleteEngineDialog(pack: EnginePack, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete ${pack.name}?") },
        text = { Text("Frees ${formatBytes(pack.approxBytes)}. Saved recordings still play; new ones need this engine again.") },
        confirmButton = { TextButton(onClick = { ModelManager.delete(pack); onDone() }) { Text("Delete", color = HolosColors.Danger) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

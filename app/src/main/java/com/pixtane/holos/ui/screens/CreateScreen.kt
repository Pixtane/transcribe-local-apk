package com.pixtane.holos.ui.screens

import com.pixtane.holos.stepsCount
import com.pixtane.holos.voicesCount
import com.pixtane.holos.languagesCount
import com.pixtane.holos.t
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.pixtane.holos.Lang
import com.pixtane.holos.chaptersCount
import com.pixtane.holos.Prefs
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.importer.DocumentImporter
import com.pixtane.holos.importer.ImportedDoc
import com.pixtane.holos.synth.SynthesisManager
import com.pixtane.holos.tts.EngineCatalog
import com.pixtane.holos.tts.ModelManager
import com.pixtane.holos.ui.CHARS_PER_PAGE
import com.pixtane.holos.ui.DEFAULT_GEN_CPS
import com.pixtane.holos.ui.Nav
import com.pixtane.holos.ui.PlayerActions
import com.pixtane.holos.ui.SPEECH_CHARS_PER_SEC
import com.pixtane.holos.ui.Screen
import com.pixtane.holos.ui.VoicePreview
import com.pixtane.holos.ui.components.GradientButton
import com.pixtane.holos.ui.components.ModelCard
import com.pixtane.holos.ui.components.SectionLabel
import com.pixtane.holos.ui.components.SelectableCard
import com.pixtane.holos.ui.formatBytes
import com.pixtane.holos.ui.formatHuman
import com.pixtane.holos.ui.theme.HolosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

val QUALITY_PRESETS: List<Pair<Int, String>> get() = listOf(2 to t("Fast", "Швидко"), 3 to t("Balanced", "Баланс"), 5 to t("Best", "Найкраще"))

@Composable
fun CreateScreen(nav: Nav, controller: MediaController?, initialText: String, initialUri: String? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val engineChanges by ModelManager.changes.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf(initialText) }
    var title by rememberSaveable { mutableStateOf("") }
    var langCode by rememberSaveable { mutableStateOf(Prefs.lastLang) }
    val lang = Lang.of(langCode)
    var showLanguages by remember { mutableStateOf(false) }
    val packs = remember(langCode) { EngineCatalog.forLang(langCode) }
    var packId by rememberSaveable(langCode) {
        mutableStateOf(
            Prefs.engineFor(langCode)?.takeIf { id -> packs.any { it.id == id } }
                ?: packs.firstOrNull { ModelManager.isReady(it) }?.id
                ?: packs.firstOrNull()?.id ?: EngineCatalog.SUPERTONIC.id
        )
    }
    val pack = EngineCatalog.get(packId)
    val packState by ModelManager.state(pack).collectAsStateWithLifecycle()
    val model = packState
    var langTouched by rememberSaveable { mutableStateOf(false) }
    var voice by rememberSaveable { mutableIntStateOf(Prefs.lastVoice) }
    var steps by rememberSaveable { mutableIntStateOf(Prefs.steps) }
    var previewing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var smartMode by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Imported books can be huge, so they are not put into the (saveable) text field.
    var imported by remember { mutableStateOf<ImportedDoc?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }

    fun importUri(uri: Uri) {
        error = null
        importing = "Opening…"
        scope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    DocumentImporter.import(context, uri) { msg -> importing = msg }
                }
                if (doc.text.isBlank()) {
                    error = t("No readable text found in this file", "У цьому файлі немає тексту для читання")
                } else if (doc.chapters.isEmpty() && doc.text.length <= 30_000) {
                    text = doc.text; imported = null
                } else {
                    imported = doc
                }
                if (title.isBlank()) title = doc.title
                if (!langTouched) (doc.language?.let { code -> Lang.entries.firstOrNull { code.lowercase().startsWith(it.code) } } ?: Lang.detect(doc.text))?.let { langCode = it.code }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.pixtane.holos.CrashReport.crumb("Import failed: $e")
                error = t("Could not import: ${e.message ?: e.javaClass.simpleName}", "Не вдалося імпортувати: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                importing = null
            }
        }
    }
    LaunchedEffect(initialUri) { initialUri?.let { importUri(Uri.parse(it)) } }

    LaunchedEffect(text) {
        if (!langTouched) Lang.detect(text)?.let { langCode = it.code }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importUri(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HolosColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { VoicePreview.stop(); nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("Back", "Назад")) }
            Text(t("New recording", "Новий запис"), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {

            OutlinedTextField(
                value = title, onValueChange = { title = it },
                placeholder = { Text(t("Title (optional)", "Назва (необов'язково)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(12.dp))
            val doc = imported
            when {
                importing != null -> ImportProgress(importing!!)
                doc != null -> DocumentCard(doc, onRemove = { imported = null })
                else -> OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(t("Paste or type the text to read aloud, or import a PDF, EPUB, MOBI or TXT file…", "Вставте чи введіть текст для озвучення або імпортуйте файл PDF, EPUB, MOBI чи TXT…")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = fieldColors(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { clipboard.getText()?.text?.let { if (it.isNotBlank()) text = if (text.isBlank()) it else text + "\n\n" + it } },
                    label = { Text(t("Paste", "Вставити")) },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)) },
                    colors = chipColors(),
                )
                AssistChip(
                    onClick = { importer.launch(DocumentImporter.MIME_TYPES) },
                    label = { Text(t("Import PDF · EPUB · MOBI · TXT", "Імпорт PDF · EPUB · MOBI · TXT")) },
                    leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) },
                    colors = chipColors(),
                )
                if (text.isNotEmpty() || imported != null) AssistChip(
                    onClick = { text = ""; imported = null },
                    label = { Text(t("Clear", "Очистити")) },
                    leadingIcon = { Icon(Icons.Rounded.Clear, null, Modifier.size(18.dp)) },
                    colors = chipColors(),
                )
            }
            Stats(imported?.text ?: text)

            Spacer(Modifier.height(20.dp))
            SectionLabel(t("Language", "Мова"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (l in listOf(Lang.UK, Lang.EN)) {
                    SelectableCard(selected = lang === l, onClick = { langCode = l.code; langTouched = true }, modifier = Modifier.weight(1f)) {
                        LangLabel(l.flag, l.native)
                    }
                }
                val other = lang !== Lang.UK && lang !== Lang.EN
                SelectableCard(selected = other, onClick = { showLanguages = true }, modifier = Modifier.weight(1f)) {
                    if (other) LangLabel(lang.flag, lang.native, t("More ▾", "Інші ▾")) else LangLabel("🌐", t("More", "Інші"), languagesCount(Lang.ALL.size - 2))
                }
            }
            when (lang.code) {
                "la" -> Hint(t("Read with ecclesiastical (Italian-style) pronunciation using the Italian voice.", "Читається з церковною (італійською) вимовою італійським голосом."))
                "cu" -> Hint(t("Titlo abbreviations are expanded and old letters modernised, then read with Russian ecclesiastical pronunciation.", "Скорочення під титлами розгортаються, старі літери замінюються сучасними, читання — з церковною вимовою."))
            }

            if (packs.size > 1) {
                Spacer(Modifier.height(20.dp))
                SectionLabel(t("Engine", "Рушій"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in packs) {
                        val ready = remember(engineChanges, p.id) { ModelManager.isReady(p) }
                        SelectableCard(selected = p.id == pack.id, onClick = { packId = p.id; Prefs.setEngineFor(langCode, p.id) }, modifier = Modifier.weight(1f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(p.family, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (ready) (if (p.voices.size > 1) "${voicesCount(p.voices.size)} · " + t("natural", "природні") else t("1 voice · fastest", "1 голос · найшвидший"))
                                    else t("Not downloaded · ${formatBytes(p.approxBytes)}", "Не завантажено · ${formatBytes(p.approxBytes)}"),
                                    style = MaterialTheme.typography.labelSmall, color = if (ready) HolosColors.Muted else HolosColors.Faint,
                                )
                            }
                        }
                    }
                }
            }
            if (model != ModelManager.State.Ready) {
                Spacer(Modifier.height(12.dp))
                ModelCard(pack, compact = true)
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(t("Voice", "Голос"), Modifier.weight(1f))
                val canPreview = model == ModelManager.State.Ready && !previewing
                AssistChip(
                    onClick = {
                        if (!canPreview) return@AssistChip
                        previewing = true
                        scope.launch {
                            try { VoicePreview.play(lang, pack, if (pack.voices.size > 1) voice else 0, steps) } catch (e: Throwable) { error = e.message }
                            previewing = false
                        }
                    },
                    enabled = model == ModelManager.State.Ready,
                    label = { Text(if (previewing) t("Preparing…", "Підготовка…") else t("Preview", "Прослухати")) },
                    leadingIcon = {
                        if (previewing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp))
                    },
                    colors = chipColors(),
                )
            }
            if (pack.voices.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in pack.voices) {
                        SelectableCard(selected = voice == v.sid, onClick = { voice = v.sid }, modifier = Modifier.width(76.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(v.code, style = MaterialTheme.typography.titleMedium, color = if (v.code.startsWith("F")) HolosColors.Pink else HolosColors.Violet)
                                Text(if (v.code.startsWith("F")) t("Female", "Жін.") else t("Male", "Чол."), style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
                            }
                        }
                    }
                }
            } else {
                Text(t("${pack.family} has a single voice for ${lang.english}.", "${pack.family} має один голос для цієї мови."), style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
            }

            if (pack.hasQualitySteps) {
                Spacer(Modifier.height(20.dp))
                SectionLabel(t("Quality", "Якість"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((s, label) in QUALITY_PRESETS) {
                        SelectableCard(selected = steps == s, onClick = { steps = s }, modifier = Modifier.weight(1f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                Text(stepsCount(s), style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
                            }
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = HolosColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            SectionLabel(t("When to generate", "Коли генерувати"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectableCard(selected = !smartMode, onClick = { smartMode = false }, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("Immediately", "Одразу"), style = MaterialTheme.typography.labelLarge)
                        Text(t("Start now", "Почати зараз"), style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
                    }
                }
                SelectableCard(selected = smartMode, onClick = { smartMode = true }, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("Smart", "Розумно"), style = MaterialTheme.typography.labelLarge)
                        Text(t("Activates when charging", "Вмикається під час заряджання"), style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val canCreate = (imported != null || text.isNotBlank()) && importing == null && model == ModelManager.State.Ready && !creating
            GradientButton(
                text = if (creating) t("Preparing…", "Підготовка…") else if (smartMode) t("Create", "Створити") else t("Create & play", "Створити й слухати"),
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth(),
                leading = { Icon(Icons.Rounded.AutoAwesome, null, tint = androidx.compose.ui.graphics.Color.White) },
                onClick = {
                    creating = true
                    VoicePreview.stop()
                    Prefs.lastLang = lang.code
                    Prefs.setEngineFor(lang.code, pack.id)
                    Prefs.lastVoice = voice
                    Prefs.steps = steps
                    scope.launch {
                      try {
                        val doc = imported
                        val body = doc?.text ?: text
                        com.pixtane.holos.CrashReport.crumb("Create: ${body.length} chars, lang=${lang.code}, voice=$voice, steps=$steps")
                        val rec = withContext(Dispatchers.IO) {
                            RecordingRepository.create(
                                rawText = body,
                                title = title.trim().ifBlank { RecordingRepository.suggestTitle(body) },
                                lang = lang, voice = if (pack.voices.size > 1) voice else 0, steps = steps, pack = pack,
                                chapters = doc?.chapters,
                                smart = smartMode,
                            )
                        }
                        if (rec.n == 0) {
                            error = t("Nothing to read in this text", "У цьому тексті нічого читати")
                            RecordingRepository.delete(rec.id)
                            creating = false
                            return@launch
                        }
                        if (smartMode) {
                            SynthesisManager.enqueue(rec.id)
                            com.pixtane.holos.synth.SmartScheduler.ensureScheduled()
                        } else {
                            SynthesisManager.enqueue(rec.id, front = true)
                            PlayerActions.play(controller, rec.id)
                        }
                        nav.replace(Screen.Player(rec.id))
                      } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                      } catch (e: Throwable) {
                        com.pixtane.holos.CrashReport.crumb("Create failed: $e")
                        error = t("Could not start: ${e.message ?: e.javaClass.simpleName}", "Не вдалося почати: ${e.message ?: e.javaClass.simpleName}")
                        creating = false
                      }
                    }
                },
            )
        }
    }
    if (showLanguages) {
        LanguageSheet(
            selected = langCode,
            engineChanges = engineChanges,
            onPick = { langCode = it.code; langTouched = true; showLanguages = false },
            onDismiss = { showLanguages = false },
        )
    }
}

@Composable
private fun Stats(text: String) {
    val chars = text.length
    if (chars == 0) return
    val cps = Prefs.measuredCps.takeIf { it > 0f } ?: DEFAULT_GEN_CPS
    val audioMs = (chars / SPEECH_CHARS_PER_SEC * 1000).toLong()
    val genMs = (chars / cps * 1000).toLong()
    val pages = chars / CHARS_PER_PAGE
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat(String.format(Locale.US, "%,d", chars), t("characters", "символів"))
        Stat(if (pages < 10) String.format(Locale.US, "%.1f", pages) else pages.toInt().toString(), t("pages", "сторінок"))
        Stat("~" + formatHuman(audioMs), t("of audio", "аудіо"))
        Stat("~" + formatHuman(genMs), t("to generate", "на генерацію"))
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = HolosColors.Violet,
    unfocusedBorderColor = HolosColors.Outline,
    focusedContainerColor = HolosColors.Surface,
    unfocusedContainerColor = HolosColors.Surface,
    cursorColor = HolosColors.Violet,
)

@Composable
private fun chipColors() = AssistChipDefaults.assistChipColors(
    containerColor = HolosColors.Surface,
    labelColor = HolosColors.Text,
    leadingIconContentColor = HolosColors.Violet,
)

@Composable
private fun ImportProgress(message: String) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(20.dp), color = HolosColors.Surface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(color = HolosColors.Violet)
            Spacer(Modifier.height(14.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = HolosColors.Muted)
        }
    }
}

@Composable
private fun DocumentCard(doc: ImportedDoc, onRemove: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(20.dp), color = HolosColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, HolosColors.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(HolosColors.accentBrush),
                    contentAlignment = Alignment.Center,
                ) { Text(doc.format.take(4), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(doc.format)
                            if (doc.chapters.isNotEmpty()) append(" · ${chaptersCount(doc.chapters.size)}")
                        },
                        style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Clear, t("Remove", "Прибрати")) }
            }
            if (doc.chapters.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                for (c in doc.chapters.take(6)) {
                    Text("•  ${c.title}", style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (doc.chapters.size > 6) Text(t("…and ${doc.chapters.size - 6} more", "…і ще ${doc.chapters.size - 6}"), style = MaterialTheme.typography.bodySmall, color = HolosColors.Faint)
            } else {
                Spacer(Modifier.height(10.dp))
                Text(doc.text.take(280).replace('\n', ' ') + "…", style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, maxLines = 4)
            }
        }
    }
}

@Composable
private fun LangLabel(flag: String, name: String, sub: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(flag, fontSize = 24.sp)
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted, maxLines = 1)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, modifier = Modifier.padding(top = 8.dp))
}

package com.pixtane.holos.ui.screens

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.pixtane.holos.Lang
import com.pixtane.holos.Prefs
import com.pixtane.holos.VOICES
import com.pixtane.holos.data.RecordingRepository
import com.pixtane.holos.synth.SynthesisManager
import com.pixtane.holos.tts.EnginePool
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
import com.pixtane.holos.ui.formatHuman
import com.pixtane.holos.ui.theme.HolosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

val QUALITY_PRESETS = listOf(2 to "Fast", 3 to "Balanced", 5 to "Best")

@Composable
fun CreateScreen(nav: Nav, controller: MediaController?, initialText: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val model by ModelManager.state.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf(initialText) }
    var title by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf(Lang.of(Prefs.lastLang)) }
    var langTouched by rememberSaveable { mutableStateOf(false) }
    var voice by rememberSaveable { mutableIntStateOf(Prefs.lastVoice) }
    var steps by rememberSaveable { mutableIntStateOf(Prefs.steps) }
    var previewing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(text) {
        if (!langTouched) Lang.detect(text)?.let { lang = it }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (loaded.isNullOrBlank()) error = "Could not read that file" else {
                text = loaded
                if (title.isBlank()) {
                    title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HolosColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { VoicePreview.stop(); nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("New recording", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            if (model != ModelManager.State.Ready) {
                ModelCard(model)
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = title, onValueChange = { title = it },
                placeholder = { Text("Title (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Paste or type the text to read aloud…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                shape = RoundedCornerShape(20.dp),
                colors = fieldColors(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { clipboard.getText()?.text?.let { if (it.isNotBlank()) text = if (text.isBlank()) it else text + "\n\n" + it } },
                    label = { Text("Paste") },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)) },
                    colors = chipColors(),
                )
                AssistChip(
                    onClick = { importer.launch(arrayOf("text/*")) },
                    label = { Text("Import .txt") },
                    leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) },
                    colors = chipColors(),
                )
                if (text.isNotEmpty()) AssistChip(
                    onClick = { text = "" },
                    label = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Rounded.Clear, null, Modifier.size(18.dp)) },
                    colors = chipColors(),
                )
            }
            Stats(text)

            Spacer(Modifier.height(20.dp))
            SectionLabel("Language")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (l in Lang.entries) {
                    SelectableCard(selected = lang == l, onClick = { lang = l; langTouched = true }, modifier = Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(l.flag, fontSize = 24.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(l.native, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Voice", Modifier.weight(1f))
                val canPreview = model == ModelManager.State.Ready && !previewing
                AssistChip(
                    onClick = {
                        if (!canPreview) return@AssistChip
                        previewing = true
                        scope.launch {
                            try { VoicePreview.play(lang, voice, steps) } catch (e: Throwable) { error = e.message }
                            previewing = false
                        }
                    },
                    enabled = model == ModelManager.State.Ready,
                    label = { Text(if (previewing) "Preparing…" else "Preview") },
                    leadingIcon = {
                        if (previewing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp))
                    },
                    colors = chipColors(),
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in VOICES) {
                    SelectableCard(selected = voice == v.sid, onClick = { voice = v.sid }, modifier = Modifier.width(76.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(v.code, style = MaterialTheme.typography.titleMedium, color = if (v.code.startsWith("F")) HolosColors.Pink else HolosColors.Violet)
                            Text(if (v.code.startsWith("F")) "Female" else "Male", style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Quality")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((s, label) in QUALITY_PRESETS) {
                    SelectableCard(selected = steps == s, onClick = { steps = s }, modifier = Modifier.weight(1f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            Text("$s steps", style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
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

        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            val canCreate = text.isNotBlank() && model == ModelManager.State.Ready && !creating
            GradientButton(
                text = if (creating) "Preparing…" else "Create & play",
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth(),
                leading = { Icon(Icons.Rounded.AutoAwesome, null, tint = androidx.compose.ui.graphics.Color.White) },
                onClick = {
                    creating = true
                    VoicePreview.stop()
                    Prefs.lastLang = lang.code
                    Prefs.lastVoice = voice
                    Prefs.steps = steps
                    scope.launch {
                      try {
                        com.pixtane.holos.CrashReport.crumb("Create: ${text.length} chars, lang=${lang.code}, voice=$voice, steps=$steps")
                        val rec = withContext(Dispatchers.IO) {
                            RecordingRepository.create(
                                text = text.trim(),
                                title = title.trim().ifBlank { RecordingRepository.suggestTitle(text) },
                                lang = lang, voice = voice, steps = steps,
                                sampleRate = if (Prefs.compactAudio) EnginePool.MODEL_SAMPLE_RATE / 2 else EnginePool.MODEL_SAMPLE_RATE,
                            )
                        }
                        if (rec.n == 0) {
                            error = "Nothing to read in this text"
                            RecordingRepository.delete(rec.id)
                            creating = false
                            return@launch
                        }
                        SynthesisManager.enqueue(rec.id, front = true)
                        PlayerActions.play(controller, rec.id)
                        nav.replace(Screen.Player(rec.id))
                      } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                      } catch (e: Throwable) {
                        com.pixtane.holos.CrashReport.crumb("Create failed: $e")
                        error = "Could not start: ${e.message ?: e.javaClass.simpleName}"
                        creating = false
                      }
                    }
                },
            )
        }
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
        Stat(String.format(Locale.US, "%,d", chars), "characters")
        Stat(if (pages < 10) String.format(Locale.US, "%.1f", pages) else pages.toInt().toString(), "pages")
        Stat("~" + formatHuman(audioMs), "of audio")
        Stat("~" + formatHuman(genMs), "to generate")
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

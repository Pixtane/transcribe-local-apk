package com.pixtane.holos.ui.screens

import com.pixtane.holos.t
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixtane.holos.BuildConfig
import com.pixtane.holos.ui.theme.HolosColors

const val PRIVACY_URL = "https://github.com/Pixtane/transcribe-local-apk/blob/main/PRIVACY.md"

private val NOTICES = listOf(
    "Supertonic 3 voice model" to "© Supertone Inc., licensed under the OpenRAIL-M license. int8 ONNX export by the " +
        "k2-fsa/sherpa-onnx project (Apache-2.0). By using the generated speech you agree to the license's use restrictions: " +
        "do not use it to break the law, to impersonate real people without consent, to deceive (e.g. present synthetic " +
        "speech as a real person's recording), to harass, defame or discriminate, to generate disinformation, or to provide " +
        "medical or legal advice. Full text: huggingface.co/Supertone/supertonic-3",
    "Coqui VITS voice models" to "Coqui TTS / Neon AI models (BSD-3-Clause), trained on CSS10, M-AILABS and Common Voice " +
        "data; ONNX export by k2-fsa/sherpa-onnx (Apache-2.0).",
    "sherpa-onnx" to "Copyright Xiaomi Corporation and contributors. Apache License 2.0.",
    "ONNX Runtime" to "Copyright Microsoft Corporation. MIT License.",
    "AndroidX, Jetpack Compose, Media3" to "Copyright The Android Open Source Project. Apache License 2.0.",
    "Kotlin & kotlinx.coroutines" to "Copyright JetBrains s.r.o. Apache License 2.0.",
    "PdfBox-Android" to "Apache PDFBox port by Tom Roush. Apache License 2.0.",
    "jsoup" to "Copyright Jonathan Hedley. MIT License.",
    "Apache Commons Compress" to "Copyright The Apache Software Foundation. Apache License 2.0.",
    "Guava" to "Copyright Google LLC. Apache License 2.0.",
)

@Composable
fun AboutSection() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = HolosColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Holos ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text(
                t(
                    "Everything runs on your phone. Holos has no account, no ads, no analytics, and never uploads your texts or audio. The internet is only used to download voice models.",
                    "Усе працює на вашому телефоні. У Holos немає акаунтів, реклами й аналітики, ваші тексти та аудіо нікуди не надсилаються. Інтернет потрібен лише для завантаження голосових моделей.",
                ),
                style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
            }) { Text(t("Privacy policy", "Політика конфіденційності"), color = HolosColors.Violet) }
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Open-source licenses & model terms", "Ліцензії та умови моделей"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (open) {
                for ((name, text) in NOTICES) {
                    Spacer(Modifier.height(10.dp))
                    Text(name, style = MaterialTheme.typography.labelLarge)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
                }
            }
        }
    }
}

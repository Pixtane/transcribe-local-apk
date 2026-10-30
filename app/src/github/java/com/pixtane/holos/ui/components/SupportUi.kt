package com.pixtane.holos.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixtane.holos.t
import com.pixtane.holos.ui.theme.HolosColors

private const val JAR_DEVELOPER = "https://send.monobank.ua/jar/AR8ohY9Fy7"
private const val JAR_ARMY = "https://send.monobank.ua/jar/5JJCq7guKX"

/** Donation links (GitHub build only; the Google Play build is paid and has none). */
@Composable
fun SupportSection() {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Surface(shape = RoundedCornerShape(20.dp), color = HolosColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Holos is free 💛", "Holos безкоштовний 💛"), style = MaterialTheme.typography.titleSmall)
            Text(
                t(
                    "If it helps you, any donation is appreciated. At least half of all donations go to the Armed Forces of Ukraine.",
                    "Якщо він вам допоміг — буду вдячний за будь-який донат. Щонайменше половина всіх донатів іде на ЗСУ.",
                ),
                style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
            )
            Spacer(Modifier.height(4.dp))
            GradientButton(t("Support the developer", "Підтримати розробника"), onClick = { open(JAR_DEVELOPER) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { open(JAR_ARMY) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Text(t("100% to the army fund", "100% — у фонд для війська"), color = HolosColors.Text)
            }
        }
    }
}

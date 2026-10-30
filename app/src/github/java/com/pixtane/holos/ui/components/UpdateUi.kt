package com.pixtane.holos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixtane.holos.BuildConfig
import com.pixtane.holos.Prefs
import com.pixtane.holos.t
import com.pixtane.holos.ui.formatBytes
import com.pixtane.holos.ui.theme.HolosColors
import com.pixtane.holos.update.Updater

/** Shown at the top of the library when a newer version exists. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    val release = when (val s = state) {
        is Updater.State.Available -> s.release
        is Updater.State.Downloading -> s.release
        is Updater.State.Ready -> s.release
        is Updater.State.Installing -> s.release
        is Updater.State.Failed -> s.release
        else -> null
    } ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = HolosColors.SurfaceHigh,
        border = BorderStroke(1.dp, HolosColors.Violet.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(HolosColors.accentBrush),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Update available: ${release.version}", "Доступне оновлення ${release.version}"), style = MaterialTheme.typography.titleSmall)
                    if (release.notes.isNotBlank()) {
                        Text(release.notes.lineSequence().first(), style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            when (val s = state) {
                is Updater.State.Downloading -> {
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = HolosColors.Violet, trackColor = HolosColors.Surface,
                    )
                    Text(
                        "${formatBytes(s.done)} / ${formatBytes(s.total)}",
                        style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is Updater.State.Installing -> Text(t("Installing…", "Встановлення…"), style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
                else -> {
                    if (s is Updater.State.Failed) {
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = HolosColors.Danger, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    if (!Updater.canRequestInstalls(context)) {
                        Text(
                            t("Android will ask you once to allow Holos to install updates.", "Android один раз попросить дозволити Holos встановлювати оновлення."),
                            style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    GradientButton(t("Update", "Оновити"), onClick = { Updater.updateNow(context) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Settings block: current version, auto-update switch, manual check. */
@Composable
fun UpdateSettings() {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    var auto by remember { mutableStateOf(Prefs.autoUpdate) }
    Surface(shape = RoundedCornerShape(20.dp), color = HolosColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Update automatically", "Оновлювати автоматично"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        t("New versions download in the background and install when you're not using the app.",
                            "Нові версії завантажуються у фоні й встановлюються, коли ви не користуєтеся застосунком."),
                        style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { auto = it; Prefs.autoUpdate = it; if (it) Updater.checkInBackground(context, auto = true) },
                    colors = SwitchDefaults.colors(checkedTrackColor = HolosColors.VioletDeep),
                )
            }
            Spacer(Modifier.height(10.dp))
            val status = when (val s = state) {
                Updater.State.Checking -> t("Checking…", "Перевірка…")
                Updater.State.UpToDate -> t("You have the latest version", "У вас найновіша версія")
                is Updater.State.Available -> t("Version ${s.release.version} is available", "Доступна версія ${s.release.version}")
                is Updater.State.Downloading -> t("Downloading ${s.release.version}…", "Завантаження ${s.release.version}…")
                is Updater.State.Ready -> t("Version ${s.release.version} is ready to install", "Версія ${s.release.version} готова до встановлення")
                is Updater.State.Installing -> t("Installing…", "Встановлення…")
                is Updater.State.Failed -> s.message
                Updater.State.Idle -> null
            }
            Text(
                t("Installed: ${BuildConfig.VERSION_NAME}", "Встановлено: ${BuildConfig.VERSION_NAME}") + (status?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
            )
            Row {
                TextButton(onClick = { Updater.checkInBackground(context) }, enabled = state != Updater.State.Checking) {
                    Text(t("Check for updates", "Перевірити оновлення"), color = HolosColors.Violet)
                }
                if (state is Updater.State.Available || state is Updater.State.Ready) {
                    TextButton(onClick = { Updater.updateNow(context) }) { Text(t("Update now", "Оновити зараз"), color = HolosColors.Violet) }
                }
            }
        }
    }
}

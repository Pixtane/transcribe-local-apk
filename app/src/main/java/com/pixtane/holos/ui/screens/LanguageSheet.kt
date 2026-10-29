package com.pixtane.holos.ui.screens

import com.pixtane.holos.uiName
import com.pixtane.holos.t
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixtane.holos.Lang
import com.pixtane.holos.tts.EngineCatalog
import com.pixtane.holos.tts.ModelManager
import com.pixtane.holos.ui.components.Pill
import com.pixtane.holos.ui.theme.HolosColors

/** Every language any engine can speak, with which engines cover it and whether they are installed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSheet(selected: String, engineChanges: Int, onPick: (Lang) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val all = remember { Lang.ALL.filter { EngineCatalog.forLang(it.code).isNotEmpty() } }
    val shown = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) all else all.filter {
            it.english.contains(q, true) || it.native.contains(q, true) || it.uiName().contains(q, true) || it.code.equals(q, true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HolosColors.Surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(t("Language", "Мова"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(t("Search ${all.size} languages", "Пошук серед ${all.size} мов")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HolosColors.Violet, unfocusedBorderColor = HolosColors.Outline,
                    focusedContainerColor = HolosColors.SurfaceHigh, unfocusedContainerColor = HolosColors.SurfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(shown, key = { it.code }) { l ->
                    val isSel = l.code == selected
                    val packs = remember(l.code, engineChanges) { EngineCatalog.forLang(l.code).map { it to ModelManager.isReady(it) } }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(l) }
                            .background(if (isSel) HolosColors.Violet.copy(alpha = 0.10f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(l.flag, fontSize = 22.sp, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.native, style = MaterialTheme.typography.bodyLarge)
                            Text(l.uiName(), style = MaterialTheme.typography.labelSmall, color = HolosColors.Muted)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((p, ready) in packs) {
                                    Pill(
                                        (if (ready) "✓ " else "↓ ") + p.family,
                                        if (ready) HolosColors.Success else HolosColors.Muted,
                                    )
                                }
                            }
                        }
                        if (isSel) Icon(Icons.Rounded.Check, null, tint = HolosColors.Violet)
                    }
                }
            }
        }
    }
}

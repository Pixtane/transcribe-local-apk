package com.pixtane.holos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pixtane.holos.data.Recording
import com.pixtane.holos.ui.formatDuration
import com.pixtane.holos.ui.theme.HolosColors

private data class TextHit(val offset: Int, val seg: Int, val before: String, val match: String, val after: String)

/** Chapter list with search; a search of 3+ letters also finds matches in the full text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    rec: Recording,
    currentSeg: Int,
    ready: Int,
    accent: Color,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val current = rec.chapterAt(currentSeg)
    val q = query.trim()
    val chapterHits = remember(q) {
        rec.chapters.indices.filter { q.isEmpty() || rec.chapters[it].title.contains(q, ignoreCase = true) }
    }
    val textHits = remember(q) {
        if (q.length < 3) emptyList() else {
            val out = ArrayList<TextHit>()
            var i = rec.text.indexOf(q, ignoreCase = true)
            while (i >= 0 && out.size < 60) {
                val a = (i - 40).coerceAtLeast(0)
                val b = (i + q.length + 60).coerceAtMost(rec.text.length)
                out.add(
                    TextHit(
                        i, rec.segmentForChar(i),
                        (if (a > 0) "…" else "") + rec.text.substring(a, i).replace('\n', ' '),
                        rec.text.substring(i, i + q.length),
                        rec.text.substring(i + q.length, b).replace('\n', ' ') + "…",
                    )
                )
                i = rec.text.indexOf(q, i + q.length, ignoreCase = true)
            }
            out
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = HolosColors.Surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(
                if (rec.chapters.isEmpty()) "Search" else "Chapters",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(if (rec.chapters.isEmpty()) "Search the text" else "Search chapters and text") },
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
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                items(chapterHits, key = { "c$it" }) { k ->
                    val seg = rec.chapterSeg[k]
                    val isCurrent = k == current
                    val generated = rec.isReady(seg)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onJump(seg); onDismiss() }
                            .background(if (isCurrent) accent.copy(alpha = 0.10f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${k + 1}", style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) accent else HolosColors.Faint, modifier = Modifier.width(36.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                rec.chapters[k].title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                                color = if (isCurrent) Color.White else HolosColors.Text,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (!generated) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(HolosColors.Faint))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            (if (ready >= rec.n) "" else "~") + formatDuration(rec.startMs(seg)),
                            style = MaterialTheme.typography.labelMedium, color = HolosColors.Muted,
                        )
                    }
                }
                if (textHits.isNotEmpty()) {
                    item(key = "hdr") {
                        Text(
                            "IN TEXT · ${textHits.size}${if (textHits.size >= 60) "+" else ""}",
                            style = MaterialTheme.typography.labelMedium, color = HolosColors.Muted,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(textHits, key = { "t${it.offset}" }) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onJump(h.seg); onDismiss() }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = HolosColors.Muted)) { append(h.before) }
                                    withStyle(SpanStyle(color = Color.White, background = accent.copy(alpha = 0.3f), fontWeight = FontWeight.SemiBold)) { append(h.match) }
                                    withStyle(SpanStyle(color = HolosColors.Muted)) { append(h.after) }
                                },
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            val ch = rec.chapterAt(h.seg)
                            Text(
                                (if (ch >= 0) rec.chapters[ch].title + " · " else "") + formatDuration(rec.startMs(h.seg)),
                                style = MaterialTheme.typography.labelSmall, color = HolosColors.Faint, maxLines = 1,
                            )
                        }
                    }
                }
                if (chapterHits.isEmpty() && textHits.isEmpty() && q.isNotEmpty()) {
                    item { Text("Nothing found", color = HolosColors.Muted, modifier = Modifier.padding(20.dp)) }
                }
            }
        }
    }
}

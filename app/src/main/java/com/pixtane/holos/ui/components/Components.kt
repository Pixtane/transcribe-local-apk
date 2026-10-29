package com.pixtane.holos.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixtane.holos.tts.ModelManager
import com.pixtane.holos.ui.formatBytes
import com.pixtane.holos.ui.theme.HolosColors

@Composable
fun ModelCard(state: ModelManager.State, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = HolosColors.Surface,
        border = BorderStroke(1.dp, HolosColors.Outline),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(HolosColors.accentBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state is ModelManager.State.Failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudDownload,
                        null, tint = Color.White,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Voice model", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${ModelManager.MODEL_NAME} · ${formatBytes(ModelManager.TOTAL_BYTES)} · Ukrainian, German, English",
                        style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            when (state) {
                is ModelManager.State.Downloading -> {
                    val p = if (state.total > 0) state.downloaded.toFloat() / state.total else 0f
                    LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = HolosColors.Violet, trackColor = HolosColors.SurfaceHigh,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(
                            "${formatBytes(state.downloaded)} of ${formatBytes(state.total)} · ${state.source}",
                            style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { ModelManager.cancel() }) { Text("Cancel") }
                    }
                }
                ModelManager.State.Extracting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = HolosColors.Violet, trackColor = HolosColors.SurfaceHigh)
                    Spacer(Modifier.height(8.dp))
                    Text("Unpacking model…", style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted)
                }
                is ModelManager.State.Failed -> {
                    Text("Download failed: ${state.message}", style = MaterialTheme.typography.bodySmall, color = HolosColors.Danger)
                    Spacer(Modifier.height(10.dp))
                    GradientButton("Try again", onClick = { ModelManager.download() })
                }
                else -> {
                    Text(
                        "Downloaded once, then everything runs on your phone — no internet, no account, your texts never leave the device.",
                        style = MaterialTheme.typography.bodyMedium, color = HolosColors.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    GradientButton("Download model", onClick = { ModelManager.download() })
                }
            }
        }
    }
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    Box(
        modifier
            .height(52.dp)
            .gradientBackground(enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            if (leading != null) Spacer(Modifier.width(8.dp))
            Text(text, color = if (enabled) Color.White else HolosColors.Faint, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Filled gradient background for a button-like container. */
fun Modifier.gradientBackground(enabled: Boolean = true, shape: RoundedCornerShape = RoundedCornerShape(16.dp)) =
    this.clip(shape).background(if (enabled) HolosColors.accentBrush else Brush.linearGradient(listOf(HolosColors.SurfaceHigh, HolosColors.SurfaceHigh)))

/**
 * Seek bar that also shows which parts of the text already have audio (like YouTube's
 * buffered bar). Tap or drag anywhere; [onScrub] reports the preview position while dragging.
 */
@Composable
fun GenerationSeekBar(
    positionMs: Long,
    durationMs: Long,
    readyRanges: List<ClosedFloatingPointRange<Float>>,
    accent: Color,
    onSeek: (Long) -> Unit,
    onScrub: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val thumb by animateFloatAsState(if (drag != null) 1f else 0f, label = "thumb")
    val frac = drag ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f).times(dur).toLong()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        drag = (o.x / size.width).coerceIn(0f, 1f); scrub((drag!! * dur).toLong())
                    },
                    onDragEnd = {
                        drag?.let { seek((it * dur).toLong()) }
                        drag = null; scrub(null)
                    },
                    onDragCancel = { drag = null; scrub(null) },
                ) { change, _ ->
                    drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    scrub((drag!! * dur).toLong())
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val h = (4 + 3 * thumb).dp.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(0f, y), Size(size.width, h), r)
            for (rg in readyRanges) {
                val x0 = rg.start * size.width
                val x1 = rg.endInclusive * size.width
                if (x1 > x0) drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(x0, y), Size(x1 - x0, h), r)
            }
            val px = frac * size.width
            drawRoundRect(
                Brush.horizontalGradient(listOf(accent.copy(alpha = 0.8f), accent)),
                Offset(0f, y), Size(px.coerceAtLeast(h), h), r,
            )
            val tr = (6 + 3 * thumb).dp.toPx()
            drawCircle(Color.White, tr, Offset(px.coerceIn(tr, size.width - tr), size.height / 2))
        }
    }
}

@Composable
fun MiniPlayer(
    title: String,
    subtitle: String,
    progress: Float,
    playing: Boolean,
    buffering: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        color = HolosColors.SurfaceHigh,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(accent, HolosColors.VioletDeep))),
                    contentAlignment = Alignment.Center,
                ) { Waveform(Color.White, Modifier.size(22.dp), animate = playing) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HolosColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onToggle) {
                    if (buffering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Color.White)
                    else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(accent))
            }
        }
    }
}

/** Five rounded bars; gently animated while playing. */
@Composable
fun Waveform(color: Color, modifier: Modifier = Modifier, animate: Boolean = false) {
    val t = if (animate) {
        val inf = rememberInfiniteTransition(label = "wave")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "wave-t",
        ).value
    } else 0.5f
    Canvas(modifier) {
        val base = floatArrayOf(0.35f, 0.65f, 1f, 0.6f, 0.3f)
        val w = size.width / 9f
        for (i in base.indices) {
            val phase = if (animate) (0.55f + 0.45f * kotlin.math.sin((t * 6.28f) + i * 1.3f)) else 1f
            val h = size.height * base[i] * phase.coerceIn(0.25f, 1f)
            val x = w * (1 + i * 1.75f)
            drawLine(color, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
fun SelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) HolosColors.SurfaceHigh else HolosColors.Surface)
            .border(
                if (selected) BorderStroke(1.5.dp, HolosColors.accentBrush) else BorderStroke(1.dp, HolosColors.Outline),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = HolosColors.Muted,
        modifier = modifier.padding(bottom = 8.dp),
        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = color) }
}

@Composable
fun RowSpaced(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

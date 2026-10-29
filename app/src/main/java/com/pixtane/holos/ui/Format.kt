package com.pixtane.holos.ui

import com.pixtane.holos.UiLang
import com.pixtane.holos.t
import java.util.Locale

fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatHuman(ms: Long): String {
    val min = ms / 60000
    return when {
        ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)} " + t("s", "с")
        min < 60 -> "$min " + t("min", "хв")
        else -> "${min / 60} " + t("h", "год") + " ${min % 60} " + t("min", "хв")
    }
}

fun formatBytes(b: Long): String = formatBytesEn(b).let { if (UiLang.isUk) it.replace(" GB", " ГБ").replace(" MB", " МБ").replace(" KB", " КБ").replace(" B", " Б") else it }

private fun formatBytesEn(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}

/** Average speaking rate used before any audio exists (characters per second of speech). */
const val SPEECH_CHARS_PER_SEC = 15.0
/** Characters in a "standard page" (1800 incl. spaces). */
const val CHARS_PER_PAGE = 1800.0
/** Fallback generation speed before the device was measured. */
const val DEFAULT_GEN_CPS = 150f

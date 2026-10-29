package com.pixtane.holos

import androidx.compose.ui.graphics.Color

enum class Lang(
    val code: String,
    val english: String,
    val native: String,
    val flag: String,
    val sample: String,
    val accent: Color,
    val deep: Color,
) {
    UK(
        "uk", "Ukrainian", "Українська", "🇺🇦",
        "Привіт! Я прочитаю ваш текст уголос — повністю на телефоні, без інтернету.",
        Color(0xFFFACC15), Color(0xFF1D3B8F),
    ),
    DE(
        "de", "German", "Deutsch", "🇩🇪",
        "Hallo! Ich lese Ihnen jeden Text vor – direkt auf dem Handy, ganz ohne Internet.",
        Color(0xFFFB923C), Color(0xFF6B1D1D),
    ),
    EN(
        "en", "English", "English", "🇬🇧",
        "Hello! I can read any text aloud, entirely on your phone, with no internet needed.",
        Color(0xFF5EEAD4), Color(0xFF134E4A),
    );

    companion object {
        fun of(code: String): Lang = entries.firstOrNull { it.code == code } ?: EN

        /** Cheap heuristic language guess used to pre-select the language chip. */
        fun detect(text: String): Lang? {
            val sample = text.take(4000)
            if (sample.isBlank()) return null
            var cyr = 0; var lat = 0
            for (c in sample) {
                if (c in 'Ѐ'..'ӿ') cyr++ else if (c in 'a'..'z' || c in 'A'..'Z') lat++
            }
            if (cyr > lat) return UK
            if (lat == 0) return null
            val lower = " " + sample.lowercase() + " "
            val deHits = listOf(" der ", " die ", " und ", " ist ", " nicht ", " ich ", " das ", " ein ", " mit ", "ä", "ö", "ü", "ß")
                .sumOf { lower.windowedCount(it) }
            val enHits = listOf(" the ", " and ", " is ", " of ", " to ", " you ", " that ", " with ", " this ")
                .sumOf { lower.windowedCount(it) }
            return if (deHits > enHits) DE else EN
        }

        private fun String.windowedCount(s: String): Int {
            var count = 0; var i = indexOf(s)
            while (i >= 0) { count++; i = indexOf(s, i + s.length) }
            return count
        }
    }
}

/** The ten preset Supertonic 3 voice styles; sid order is alphabetical (F1..F5, M1..M5). */
data class Voice(val sid: Int, val code: String, val label: String)

val VOICES: List<Voice> = (1..5).map { Voice(it - 1, "F$it", "Female $it") } +
    (1..5).map { Voice(it + 4, "M$it", "Male $it") }

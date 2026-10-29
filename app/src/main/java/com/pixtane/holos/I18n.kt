package com.pixtane.holos

import androidx.compose.runtime.mutableStateOf

/**
 * Tiny two-language UI layer (Ukrainian / English) that switches instantly: [t] reads a
 * Compose state, so every screen recomposes when the language toggle in Settings changes.
 */
object UiLang {
    val state = mutableStateOf(Prefs.uiLang)
    val isUk get() = state.value == "uk"

    fun set(code: String) {
        Prefs.uiLang = code
        state.value = code
    }
}

fun t(en: String, uk: String): String = if (UiLang.isUk) uk else en

/** Ukrainian plural: 1 розділ, 2 розділи, 5 розділів. */
fun ukPlural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

fun count(n: Int, enOne: String, enMany: String, ukOne: String, ukFew: String, ukMany: String): String =
    if (UiLang.isUk) "$n ${ukPlural(n, ukOne, ukFew, ukMany)}" else "$n ${if (n == 1) enOne else enMany}"

fun chaptersCount(n: Int) = count(n, "chapter", "chapters", "розділ", "розділи", "розділів")
fun languagesCount(n: Int) = count(n, "language", "languages", "мова", "мови", "мов")
fun voicesCount(n: Int) = count(n, "voice", "voices", "голос", "голоси", "голосів")
fun sentencesCount(n: Int) = count(n, "sentence", "sentences", "речення", "речення", "речень")
fun recordingsCount(n: Int) = count(n, "recording", "recordings", "запис", "записи", "записів")
fun stepsCount(n: Int) = count(n, "step", "steps", "крок", "кроки", "кроків")

private val UK_NAMES = mapOf(
    "uk" to "Українська", "en" to "Англійська", "de" to "Німецька", "la" to "Латина (церковна)",
    "cu" to "Церковнослов'янська", "ar" to "Арабська", "bg" to "Болгарська", "cs" to "Чеська",
    "da" to "Данська", "el" to "Грецька", "es" to "Іспанська", "et" to "Естонська", "fi" to "Фінська",
    "fr" to "Французька", "ga" to "Ірландська", "hi" to "Гінді", "hr" to "Хорватська", "hu" to "Угорська",
    "id" to "Індонезійська", "it" to "Італійська", "ja" to "Японська", "ko" to "Корейська",
    "lt" to "Литовська", "lv" to "Латиська", "mt" to "Мальтійська", "nl" to "Нідерландська",
    "pl" to "Польська", "pt" to "Португальська", "ro" to "Румунська", "ru" to "Російська",
    "sk" to "Словацька", "sl" to "Словенська", "sv" to "Шведська", "tr" to "Турецька", "vi" to "В'єтнамська",
)

/** Name of a language in the current UI language. */
fun Lang.uiName(): String = if (UiLang.isUk) UK_NAMES[code] ?: english else english

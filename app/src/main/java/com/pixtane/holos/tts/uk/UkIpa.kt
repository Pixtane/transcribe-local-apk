package com.pixtane.holos.tts.uk

/**
 * Ukrainian text → IPA, a line-by-line Kotlin port of patriotyk/ipa-uk (MIT), itself an
 * implementation of Wiktionary's Module:uk-pronunciation. The «Денис» StyleTTS2 voice was trained
 * on exactly this transcription, so the rules must stay identical to the Python original.
 * Stress is given as U+0301 after the stressed vowel.
 */
object UkIpa {
    const val ACUTE = '́'
    const val GRAVE = '̀'

    private const val VOWEL_NO_I = "aɛɪuɔɐoʊe"
    private const val VOWEL = VOWEL_NO_I + "i"
    private const val VOWEL_C = "[$VOWEL]"
    private const val CONSONANT_NO_W = "bdzʒɡɦmnlrpftskxʃj"
    private const val CONSONANT_NO_W_C = "[$CONSONANT_NO_W]"
    private const val CONSONANT = CONSONANT_NO_W + "ʋβ̞wʍ"
    private const val CONSONANT_C = "[$CONSONANT]"
    private const val PALATALIZABLE_C = "[tdsznlrbpʋfɡmkɦxʃʒ]"
    private const val VOICED_OBSTRUENT = "[bdzʒɡɦ]"

    private val VOICING = linkedMapOf(
        "p" to "b", "f" to "v", "t" to "d", "tʲ" to "dʲ", "s" to "z", "sʲ" to "zʲ", "ʃ" to "ʒ",
        "k" to "ɡ", "x" to "ɦ", "t͡s" to "d͡z", "t͡sʲ" to "d͡zʲ", "t͡ʃ" to "d͡ʒ", "ʃt͡ʃ" to "ʒd͡ʒ",
    )

    private val PERM_SYL_ONSET = setOf(
        "spr", "str", "skr", "spl", "skl", "sp", "st", "sk", "sf", "sx", "pr", "br", "tr", "dr", "kr",
        "gr", "ɦr", "fr", "xr", "pl", "bl", "kl", "gl", "ɦl", "fl", "xl",
    )

    private val GRAVE_DECOMPOSER = mapOf('ѐ' to "е$GRAVE", 'Ѐ' to "Е$GRAVE", 'ѝ' to "и$GRAVE", 'Ѝ' to "И$GRAVE")

    private val CHAR_MAPS: List<List<Pair<String, String>>> = listOf(
        listOf("дзь" to "d͡zʲ", "тьс" to "t͡sʲː"),
        listOf(
            "дж" to "d͡ʒ", "дз" to "d͡z", "дс" to "d͡zs", "дш" to "d͡ʒʃ", "дч" to "d͡ʒt͡ʃ", "дц" to "d͡zt͡s",
            "тс" to "t͡s", "тш" to "t͡ʃʃ", "тч" to "t͡ʃː", "тц" to "t͡sː",
        ),
        listOf(
            "а" to "a", "б" to "b", "в" to "ʋ", "г" to "ɦ", "ґ" to "ɡ", "д" to "d", "е" to "ɛ", "є" to "jɛ",
            "ж" to "ʒ", "з" to "z", "и" to "ɪ", "і" to "i", "ї" to "ji", "й" to "j", "к" to "k", "л" to "l",
            "м" to "m", "н" to "n", "о" to "ɔ", "п" to "p", "р" to "r", "с" to "s", "т" to "t", "у" to "u",
            "ф" to "f", "х" to "x", "ц" to "t͡s", "ч" to "t͡ʃ", "ш" to "ʃ", "щ" to "ʃt͡ʃ", "ь" to "ʲ",
            "ю" to "ju", "я" to "ja", "’" to "j", ACUTE.toString() to "ˈ", GRAVE.toString() to "ˌ",
        ),
    )

    private class Rule(pattern: String, val replacement: String) {
        val regex = Regex(pattern)
        fun apply(s: String) = regex.replace(s, replacement)
    }

    private fun r(p: String, rep: String) = Rule(p, rep)

    private val ORTHOGRAPHIC = listOf(
        r("нтськ", "ньськ"), r("стськ", "ськ"), r("нтст", "нст"), r("стч", "шч"), r("стд", "зд"),
        r("стс", "сː"), r("#зш", "#шː"), r("зш", "жш"), r("#зч", "#шч"), r("зч", "жч"),
        r("([бвгґд])\\1", "$1ː"), r("([^д]+)жж", "$1жː"), r("([^д]+)зз", "$1зː"),
        r("([йклмнпрстфхцчшщ])\\1", "$1ː"), r("дждж", "джː"), r("дздз", "дзː"),
    )

    private val VOICING_RULES = VOICING.map { (k, v) -> r(k + "(" + VOICED_OBSTRUENT + "+)", v + "$1") }

    private val ONSET = Regex("(.)(ʲ?)($CONSONANT_C)(ʲ?)([ˈˌ])($CONSONANT_C)")

    fun ipa(input: String): String {
        var text = buildString { for (c in input) append(GRAVE_DECOMPOSER[c] ?: c) }.lowercase()
        text = text.replace(Regex("\\s+"), " ")
        val words = text.split(Regex("[\\s\\-]+"))
        return words.joinToString(" ") { word(it) }.replace("#", "")
    }

    private fun word(w: String): String {
        var p = "#$w#"
        for (rule in ORTHOGRAPHIC) p = rule.apply(p)
        p = p.replace("'", "%")
        for (map in CHAR_MAPS) for ((k, v) in map) p = p.replace(k, v)

        p = r("([aɛiɪuɔ])([ˈˌ])", "$2$1").apply(p)
        // (check_accent is False in inference, so no automatic monosyllable stress)

        p = r("($PALATALIZABLE_C)([ː]?)([ˈˌ⁀]?)i", "$1ʲ$2$3i").apply(p)
        p = r("($PALATALIZABLE_C)([ː]?)j", "$1ʲ$2").apply(p)
        p = r("ʲːj", "ʲː").apply(p)
        p = r("st͡sʲ([ː]?)", "sʲt͡sʲ").apply(p)
        for (rule in VOICING_RULES) p = rule.apply(p)

        p = r("([tdszn])(.)ʲ", "$1ʲ$2ʲ").apply(p)
        p = r("([tdsznl])t͡sʲ", "$1ʲt͡sʲ").apply(p)
        p = r("([tdsznl])d͡zʲ", "$1ʲd͡zʲ").apply(p)
        p = r("t͡s(.)ʲ", "t͡sʲ$1ʲ").apply(p)
        p = r("d͡z(.)ʲ", "d͡zʲ$1ʲ").apply(p)
        p = r("d͡zt͡sʲ", "d͡zʲt͡sʲ").apply(p)
        p = r("t͡sd͡zʲ", "t͡sʲd͡zʲ").apply(p)

        p = r("ʒt͡sʲ", "zʲt͡sʲ").apply(p)
        p = r("t͡ʃt͡sʲ", "t͡sʲː").apply(p)
        p = r("ʃt͡sʲ", "sʲt͡sʲ").apply(p)
        p = r("ʃsʲ", "sʲː").apply(p)

        p = r("zʒ", "ʒː").apply(p)
        p = r("sʃ", "ʃː").apply(p)
        p = r("zt͡ʃ", "ʒt͡ʃ").apply(p)
        p = r("zd͡ʒ", "ʒd͡ʒ").apply(p)
        p = r("t͡ʒ", "d͡ʒ").apply(p)
        p = r("t͡z", "d͡z").apply(p)

        p = r("([^aɛiɪuɔ]+)ʲ([^aɛiɪuɔ]+)ʲ([^aɛiɪuɔ]+)ʲ", "$1$2ʲ$3ʲ").apply(p)

        p = r("([^ˈˌ⁀])a", "$1ɐ").apply(p)
        p = r("([^ˈˌ⁀])u", "$1ʊ").apply(p)
        p = r("ɔ([bdzʒɡɦmnlrpftskxʲʃ͡]+)([ˈˌ⁀][uiʊ])", "o$1$2").apply(p)
        p = r("([^ˈˌ⁀])[ɛ]", "$1e").apply(p)

        p = p.replace("⁀", "")

        p = r("($VOWEL_C)ʋ([$CONSONANT_NO_W#])", "$1u̯$2").apply(p)
        p = r("ʋ([ˈˌ]?[ɔuoʊbdzʒɡɦmnlr])", "w$1").apply(p)
        p = r("ʋ([pftskxʃ])", "ʍ$1").apply(p)

        p = r("($VOWEL_C)j([$CONSONANT_NO_W#])", "$1i̯$2").apply(p)
        p = r("#j($CONSONANT_NO_W_C)", "#i̯$1").apply(p)

        p = p.replace("%", "")
        p = r("([^#$VOWEL]?[ʲː]*)([ˈˌ])", "$2$1").apply(p)
        p = r("([^#$VOWEL]͡)([ˈˌ])", "$2$1").apply(p)

        p = ONSET.replace(p) { m ->
            val (a, aj, b, bj, stress, c) = m.destructured
            when {
                (a + b + c) in PERM_SYL_ONSET -> stress + a + aj + b + bj + c
                (b + c) in PERM_SYL_ONSET || c == "j" -> a + aj + stress + b + bj + c
                else -> a + aj + b + bj + stress + c
            }
        }

        p = r("([^#$VOWEL]͡)([ˈˌ])(.ʲ?j)", "$2$1$3").apply(p)
        p = r("([^#$VOWEL]͡)([ˈˌ])(.ʲ?)", "$1$3$2").apply(p)
        p = r("#([^#$VOWEL]+)([ˈˌ])", "#$2$1").apply(p)
        p = r("#([ui]̯)([ˈˌ])", "#$2$1").apply(p)
        p = r("ʲ?ːʲ", "ʲː").apply(p)
        p = r("l([^ʲ])", "ɫ$1").apply(p)
        return p
    }
}

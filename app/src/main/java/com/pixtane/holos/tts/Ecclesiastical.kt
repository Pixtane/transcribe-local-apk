package com.pixtane.holos.tts

import java.text.Normalizer

/**
 * Ecclesiastical Latin is pronounced essentially like Italian, so Latin spelling is rewritten
 * into Italian spelling that produces that pronunciation, and spoken by the Italian voice.
 */
object Latin {
    private val COMBINING = Regex("\\p{Mn}+")
    private val TI_VOWEL = Regex("(?<![stxSTX])([tT])i(?=[aeiouAEIOU])")
    private val CH_HARD = Regex("([cC])h(?![eiyEIY])")
    private val WORDS = mapOf("mihi" to "michi", "nihil" to "nichil", "nihilum" to "nichilum", "Mihi" to "Michi", "Nihil" to "Nichil")

    fun toItalianReading(text: String): String {
        var t = text
            .replace("æ", "e").replace("Æ", "E").replace("œ", "e").replace("Œ", "E")
            .replace("ǽ", "e").replace("Ǽ", "E")
        t = COMBINING.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        t = Regex("\\b\\w+\\b").replace(t) { m -> WORDS[m.value] ?: m.value }
        t = t.replace("ae", "e").replace("Ae", "E").replace("AE", "E")
            .replace("oe", "e").replace("Oe", "E").replace("OE", "E")
            .replace('j', 'i').replace('J', 'I')
            .replace('y', 'i').replace('Y', 'I')
            .replace("ph", "f").replace("Ph", "F").replace("PH", "F")
            .replace("th", "t").replace("Th", "T").replace("TH", "T")
            .replace("rh", "r").replace("Rh", "R")
        t = CH_HARD.replace(t, "$1")
        t = TI_VOWEL.replace(t) { m -> if (m.groupValues[1] == "T") "Zi" else "zi" }
        return t
    }
}

/**
 * Church Slavonic → modern Cyrillic spelling read with Russian ecclesiastical pronunciation:
 * titlo abbreviations (nomina sacra) are expanded, archaic letters mapped, accents removed.
 */
object ChurchSlavonic {
    private const val TITLO = '҃'
    private val SUPERSCRIPT = mapOf(
        'ⷠ' to 'б', 'ⷡ' to 'в', 'ⷢ' to 'г', 'ⷣ' to 'д', 'ⷤ' to 'ж', 'ⷥ' to 'з',
        'ⷦ' to 'к', 'ⷧ' to 'л', 'ⷨ' to 'м', 'ⷩ' to 'н', 'ⷪ' to 'о', 'ⷫ' to 'п',
        'ⷬ' to 'р', 'ⷭ' to 'с', 'ⷮ' to 'т', 'ⷯ' to 'х', 'ⷰ' to 'ц', 'ⷱ' to 'ч',
        'ⷲ' to 'ш', 'ⷳ' to 'щ', 'ⷴ' to 'ф', 'ⷵ' to 'с', 'ⷶ' to 'а', 'ⷷ' to 'е',
        'ⷸ' to 'д', 'ⷹ' to 'у', 'ⷺ' to 'е', 'ⷻ' to 'ю', 'ⷼ' to 'я', 'ⷽ' to 'я',
        'ⷾ' to 'я', 'ⷿ' to 'я',
    )
    private val LETTERS = mapOf(
        "ѿ" to "от", "Ѿ" to "От", "ѯ" to "кс", "Ѯ" to "Кс", "ѱ" to "пс", "Ѱ" to "Пс",
        "оу" to "у", "Оу" to "У", "ОУ" to "У", "ѹ" to "у", "Ѹ" to "У", "ꙋ" to "у", "Ꙋ" to "У",
        "ѣ" to "е", "Ѣ" to "Е", "є" to "е", "Є" to "Е", "ѥ" to "е",
        "і" to "и", "І" to "И", "ї" to "и", "Ї" to "И", "ѵ" to "и", "Ѵ" to "И",
        "ѡ" to "о", "Ѡ" to "О", "ѻ" to "о", "Ѻ" to "О", "ꙍ" to "о", "Ꙍ" to "О", "ꙩ" to "о", "ꙫ" to "о", "ꙭ" to "о", "ꚙ" to "о",
        "ѳ" to "ф", "Ѳ" to "Ф", "ѕ" to "з", "Ѕ" to "З", "ꙁ" to "з", "Ꙁ" to "З", "ꙃ" to "з", "ꙅ" to "з",
        "ѧ" to "я", "Ѧ" to "Я", "ꙗ" to "я", "Ꙗ" to "Я", "ѩ" to "я", "ѫ" to "у", "Ѫ" to "У", "ѭ" to "ю",
        "ꙑ" to "ы", "Ꙑ" to "Ы", "ꙿ" to "", "ꙇ" to "и",
    )
    /** Abbreviated stems (letters left after removing the titlo) → full stems, longest first. */
    private val NOMINA = listOf(
        "бжств" to "божеств", "бжтв" to "божеств", "бцд" to "богородиц", "бц" to "богородиц", "бгородиц" to "богородиц",
        "бл҃гослов" to "благослов", "блгслв" to "благослов", "блгосл" to "благосл", "блгдт" to "благодат", "блг" to "благ", "бл" to "благ",
        "бг" to "бог", "бж" to "бож",
        "гдс" to "господ", "гд" to "господ", "гсп" to "господ",
        "хрст" to "христ", "хрс" to "христ", "хр" to "христ",
        "иис" to "иисус", "исус" to "иисус",
        "ерслим" to "иерусалим", "иерслим" to "иерусалим",
        "аггл" to "ангел", "агг" to "анг", "мр" to "мар", "апл" to "апостол", "апсл" to "апостол", "апст" to "апостол",
        "двд" to "давид", "дх" to "дух", "дш" to "душ", "дв" to "дев",
        "млсрд" to "милосерд", "млст" to "милост", "млтв" to "молитв", "млт" to "молит",
        "нбс" to "небес", "нб" to "неб",
        "ст" to "свят", "свт" to "свят", "свщ" to "священ",
        "сн" to "сын", "спс" to "спас", "сп" to "спас",
        "црк" to "церк", "цр" to "цар", "црств" to "царств",
        "чл" to "чело", "чст" to "чест", "чт" to "чест",
        "оц" to "отц", "отц" to "отц", "оч" to "отч", "мтр" to "матер", "мт" to "мат",
        "прт" to "прист", "прчст" to "пречист", "прпдб" to "преподоб", "првд" to "правед",
        "пррк" to "пророк", "пррч" to "пророч", "првсл" to "правосл",
        "вл" to "влад", "влдц" to "владыц", "влк" to "велик",
        "слв" to "слав", "сщ" to "священ",
    ).sortedByDescending { it.first.length }

    // Accents, kamora, psili, titlo… but not the breve/diaeresis of й and ё.
    private val COMBINING = Regex("[\\u0300-\\u0305\\u0307\\u0309-\\u036F\\u0483-\\u0489\\uA66F-\\uA67F\\uA69E\\uA69F\\uFE2E\\uFE2F]+")
    private val WORD = Regex("[\\p{L}\\p{M}\\u0483-\\u0489\\u2DE0-\\u2DFF\\uA640-\\uA69F]+")
    private val FINAL_HARD = Regex("(?<=[бвгджзклмнпрстфхцчшщБВГДЖЗКЛМНПРСТФХЦЧШЩ])[ъЪ](?!\\p{L})")

    fun toRussianReading(text: String): String {
        var t = WORD.replace(text) { m -> expandWord(m.value) }
        for ((k, v) in LETTERS) t = t.replace(k, v)
        t = COMBINING.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        t = Normalizer.normalize(t, Normalizer.Form.NFC)
        t = FINAL_HARD.replace(t, "")
        // In Church Slavonic ";" is the question mark.
        t = t.replace(';', '?')
        return t
    }

    private fun expandWord(w: String): String {
        val abbreviated = w.indexOf(TITLO) >= 0 || w.any { it in 'ⷠ'..'ⷿ' }
        if (!abbreviated) return w
        val sb = StringBuilder()
        for (c in w) {
            val sup = SUPERSCRIPT[c]
            when {
                sup != null -> sb.append(sup)
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() || c in '҃'..'҉' -> {}
                else -> sb.append(c)
            }
        }
        var base = sb.toString()
        for ((k, v) in LETTERS) base = base.replace(k, v)
        val lower = base.lowercase()
        val cap = base.firstOrNull()?.isUpperCase() == true
        for ((abbr, full) in NOMINA) {
            if (lower.startsWith(abbr)) {
                val out = full + lower.substring(abbr.length)
                return if (cap) out.replaceFirstChar { it.uppercase() } else out
            }
        }
        return base
    }
}

package com.pixtane.holos.tts.uk

/** Spells out numbers in Ukrainian (nominative), e.g. "2026" → "дві тисячі двадцять шість". */
object UkNumbers {
    private val ONES_M = listOf("", "один", "два", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
    private val ONES_F = listOf("", "одна", "дві", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
    private val TEENS = listOf(
        "десять", "одинадцять", "дванадцять", "тринадцять", "чотирнадцять", "п'ятнадцять",
        "шістнадцять", "сімнадцять", "вісімнадцять", "дев'ятнадцять",
    )
    private val TENS = listOf("", "", "двадцять", "тридцять", "сорок", "п'ятдесят", "шістдесят", "сімдесят", "вісімдесят", "дев'яносто")
    private val HUNDREDS = listOf("", "сто", "двісті", "триста", "чотириста", "п'ятсот", "шістсот", "сімсот", "вісімсот", "дев'ятсот")

    private data class Scale(val one: String, val few: String, val many: String, val feminine: Boolean)
    private val SCALES = listOf(
        Scale("", "", "", false),
        Scale("тисяча", "тисячі", "тисяч", true),
        Scale("мільйон", "мільйони", "мільйонів", false),
        Scale("мільярд", "мільярди", "мільярдів", false),
    )

    private fun triple(n: Int, feminine: Boolean): List<String> {
        val out = ArrayList<String>()
        val h = n / 100; val t = (n / 10) % 10; val o = n % 10
        if (h > 0) out.add(HUNDREDS[h])
        when {
            t == 1 -> out.add(TEENS[o])
            else -> {
                if (t > 1) out.add(TENS[t])
                if (o > 0) out.add(if (feminine) ONES_F[o] else ONES_M[o])
            }
        }
        return out
    }

    fun spell(value: Long): String {
        if (value == 0L) return "нуль"
        if (value < 0) return "мінус " + spell(-value)
        if (value >= 1_000_000_000_000L) return value.toString().map { spell((it - '0').toLong()) }.joinToString(" ")
        val parts = ArrayList<String>()
        var n = value
        var scale = 0
        val groups = ArrayList<Pair<Int, Int>>()
        while (n > 0) { groups.add((n % 1000).toInt() to scale); n /= 1000; scale++ }
        for ((g, s) in groups.reversed()) {
            if (g == 0) continue
            val sc = SCALES[s]
            // "тисяча", "мільйон" rather than "одна тисяча", "один мільйон"
            if (!(s > 0 && g == 1)) parts.addAll(triple(g, sc.feminine))
            if (s > 0) {
                val m10 = g % 10; val m100 = g % 100
                parts.add(when {
                    m10 == 1 && m100 != 11 -> sc.one
                    m10 in 2..4 && m100 !in 12..14 -> sc.few
                    else -> sc.many
                })
            }
        }
        return parts.joinToString(" ")
    }

    private val NUMBER = Regex("(\\d+)(?:[,.](\\d+))?(\\s?%)?")

    /** Replaces digit sequences (with optional decimal part and %) by words. */
    fun expand(text: String): String = NUMBER.replace(text) { m ->
        val whole = m.groupValues[1].toLongOrNull() ?: return@replace m.value
        val frac = m.groupValues[2]
        val sb = StringBuilder(spell(whole))
        if (frac.isNotEmpty()) sb.append(" кома ").append(frac.trimStart('0').let { if (it.isEmpty()) "нуль" else spell(it.toLong()) })
        if (m.groupValues[3].isNotEmpty()) sb.append(" відсотків")
        sb.toString()
    }
}

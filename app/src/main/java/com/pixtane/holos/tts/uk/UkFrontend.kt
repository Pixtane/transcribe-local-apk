package com.pixtane.holos.tts.uk

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.charset.Charset
import java.text.Normalizer

/**
 * Ukrainian text front-end for the «Денис» voice: numbers → words, word stress from the
 * lang-uk dictionary (2.9M word forms), then IPA via [UkIpa].
 */
class UkFrontend(stressDb: File) {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(stressDb.path, null, SQLiteDatabase.OPEN_READONLY)
    private val cp1251: Charset = Charset.forName("windows-1251")
    private val word = Regex("[А-ЩЬЮЯҐЄІЇа-щьюяґєії'’ʼ`]+")

    fun toIpa(text: String): String {
        val withNumbers = UkNumbers.expand(text)
        val stressed = word.replace(withNumbers) { m -> stress(m.value) }
        return UkIpa.ipa(Normalizer.normalize(stressed, Normalizer.Form.NFC))
    }

    /** Adds U+0301 after the stressed vowel(s); words not in the dictionary stay as they are. */
    fun stress(original: String): String {
        val w = original.replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
        // Proper names are stored capitalised ("Київ"); the lowercase form can be another word
        // ("київ", plural of "кий"), so try the word as written first.
        val candidates = linkedSetOf(w, w.lowercase(), w.lowercase().replaceFirstChar { it.uppercase() })
        for (c in candidates) {
            val positions = lookup(c) ?: continue
            if (positions.isEmpty()) return w
            val sb = StringBuilder(w)
            for (p in positions.sortedDescending()) if (p in 1..sb.length) sb.insert(p, UkIpa.ACUTE)
            return sb.toString()
        }
        return w
    }

    private fun lookup(key: String): List<Int>? {
        val bytes = try {
            val enc = cp1251.newEncoder()
            if (!enc.canEncode(key)) return null
            key.toByteArray(cp1251)
        } catch (e: Exception) { return null }
        val hex = bytes.joinToString("") { "%02X".format(it) }
        for (table in arrayOf("prefer", "stress")) {
            db.rawQuery("select stress from $table where word = x'$hex'", null).use { c ->
                if (c.moveToFirst()) {
                    val blob = c.getBlob(0).map { it.toInt() and 0xff }
                    // Heteronyms: records "positions… 0xFE tags… 0xFF"; without context use the first.
                    val cut = blob.indexOf(0xFE)
                    return (if (cut >= 0) blob.subList(0, cut) else blob).distinct()
                }
            }
        }
        return null
    }

    fun close() = db.close()
}

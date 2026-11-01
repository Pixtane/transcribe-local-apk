package com.pixtane.holos.tts.uk

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Read-only access to the stress dictionary (Android SQLite). */
internal class StressDb(file: File) {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    /** The `stress` blob for the cp1251 word given as [hex], or null. */
    fun blob(table: String, hex: String): ByteArray? =
        db.rawQuery("select stress from $table where word = x'$hex'", null).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }

    fun close() = db.close()
}

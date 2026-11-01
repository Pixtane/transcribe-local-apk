package com.pixtane.holos.tts.uk

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Read-only access to the stress dictionary (SQLite through JDBC on the desktop). */
internal class StressDb(file: File) {
    private val db: Connection = run {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath.replace('\\', '/')}?mode=ro")
    }

    @Synchronized
    fun blob(table: String, hex: String): ByteArray? =
        db.createStatement().use { st ->
            st.executeQuery("select stress from $table where word = x'$hex'").use { rs ->
                if (rs.next()) rs.getBytes(1) else null
            }
        }

    fun close() = db.close()
}

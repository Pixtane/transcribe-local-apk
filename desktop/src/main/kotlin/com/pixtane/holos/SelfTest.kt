package com.pixtane.holos

import de.sciss.jump3r.lowlevel.LameEncoder
import de.sciss.jump3r.mp3.MPEGMode
import java.io.File
import java.sql.DriverManager
import javax.sound.sampled.AudioFormat

/**
 * `Holos.exe --selftest <report.txt>`: checks that the packaged app can load its native engine,
 * SQLite and the MP3 encoder (run by CI on the built Windows app). A model directory in
 * HOLOS_SELFTEST_MODEL (Coqui VITS layout) also gets a real synthesis test.
 */
object SelfTest {
    fun run(out: String?) {
        val sb = StringBuilder()
        fun step(name: String, block: () -> String) {
            val r = runCatching(block)
            sb.appendLine(if (r.isSuccess) "OK   $name: ${r.getOrNull()}" else "FAIL $name: ${r.exceptionOrNull()}")
        }
        step("version") { BuildInfo.VERSION }
        step("resources") { NativeLibs.dir()?.absolutePath ?: "none" }
        step("java.library.path") { System.getProperty("java.library.path") }
        step("native libs") { NativeLibs.load(); "loaded" }
        step("sherpa-onnx class") { Class.forName("com.k2fsa.sherpa.onnx.OfflineTts"); "initialized" }
        step("sqlite") {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
                c.createStatement().use { st -> st.executeQuery("select x'C0FF'").use { rs -> rs.next(); rs.getBytes(1).size.toString() } }
            }
        }
        step("windows-1251") {
            val b = "Київ".toByteArray(java.nio.charset.Charset.forName("windows-1251"))
            check(b.size == 4) { "bad encoding" }
            String(b, java.nio.charset.Charset.forName("windows-1251"))
        }
        step("ukrainian dates") {
            java.text.SimpleDateFormat("d MMMM", java.util.Locale.forLanguageTag("uk")).format(java.util.Date(0))
        }
        if (System.getenv("HOLOS_SELFTEST_NET") != null) step("https") {
            val c = java.net.URL("https://huggingface.co/").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            "HTTP ${c.responseCode}".also { c.disconnect() }
        }
        step("mp3") {
            val enc = LameEncoder(AudioFormat(24000f, 16, 1, true, false), 64, MPEGMode.MONO.ordinal, LameEncoder.QUALITY_MIDDLE, false)
            val pcm = ByteArray(enc.pcmBufferSize)
            val buf = ByteArray(enc.mP3BufferSize)
            var n = 0
            repeat(20) { n += enc.encodeBuffer(pcm, 0, pcm.size, buf) }
            n += enc.encodeFinish(buf)
            enc.close()
            "$n bytes"
        }
        step("sonic") {
            val s = sonic.Sonic(24000, 1).apply { speed = 1.5f }
            s.writeShortToStream(ShortArray(24000), 24000); s.flushStream()
            "${s.samplesAvailable()} samples"
        }
        System.getenv("HOLOS_SELFTEST_MODEL")?.let { dir ->
            step("synthesis") {
                val cfg = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                    model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                        vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(model = "$dir/model.onnx", tokens = "$dir/tokens.txt"),
                        numThreads = 2, debug = false, provider = "cpu",
                    ),
                )
                val tts = com.k2fsa.sherpa.onnx.OfflineTts(config = cfg)
                val a = tts.generate("Hello from Holos on Windows.", 0, 1f)
                tts.release()
                "${a.samples.size} samples at ${a.sampleRate} Hz"
            }
        }
        val text = sb.toString()
        if (out != null) File(out).writeText(text) else print(text)
    }
}

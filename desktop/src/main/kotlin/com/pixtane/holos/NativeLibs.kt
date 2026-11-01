package com.pixtane.holos

import java.io.File

/**
 * Loads ONNX Runtime and the sherpa-onnx JNI library from the app's resources folder.
 * ONNX Runtime goes first so Windows finds it when the JNI library is loaded.
 */
object NativeLibs {
    fun dir(): File? = System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }

    fun load() {
        val d = dir() ?: return
        val names = if (HolosApp.isWindows) listOf("onnxruntime.dll", "sherpa-onnx-jni.dll")
        else listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")
        for (n in names) {
            val f = File(d, n)
            if (f.exists()) System.load(f.absolutePath)
        }
    }
}

package android.content

import java.io.File

/** Desktop stand-in so shared code that mentions Context compiles; never instantiated. */
abstract class Context {
    abstract val filesDir: File
    abstract val cacheDir: File
}

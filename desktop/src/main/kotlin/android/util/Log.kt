package android.util

/** Desktop stand-in for android.util.Log: prints to stderr and the crash breadcrumbs. */
object Log {
    @JvmStatic fun d(tag: String, msg: String): Int = p("D", tag, msg, null)
    @JvmStatic fun i(tag: String, msg: String): Int = p("I", tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String): Int = p("W", tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String, t: Throwable?): Int = p("W", tag, msg, t)
    @JvmStatic fun e(tag: String, msg: String): Int = p("E", tag, msg, null)
    @JvmStatic fun e(tag: String, msg: String, t: Throwable?): Int = p("E", tag, msg, t)

    private fun p(level: String, tag: String, msg: String, t: Throwable?): Int {
        System.err.println("$level/$tag: $msg")
        t?.printStackTrace()
        return 0
    }
}

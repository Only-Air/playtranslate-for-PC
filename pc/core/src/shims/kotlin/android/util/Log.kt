@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

/**
 * The `android.util.Log` shim.
 *
 * PORTING.md §2.1's second bucket is "只用 `android.util.Log` / 注解 — 加一个
 * shim 即可". This is that shim, and it is the whole of it: eighteen files
 * import `Log` and call `Log.d/i/w/e/v` with a tag and a message, some with a
 * `Throwable`, and none of them ask anything else of it.
 *
 * Two design points:
 *
 *  - **It writes to stderr, not to a logging framework.** `core` is the module
 *    with no dependencies (that is what makes it the module upstream code can
 *    be extracted into), and a logging framework is a dependency. If the
 *    desktop shell wants structured logs it installs its own `LogSink`; the
 *    default stays a one-line-per-event print so a test run shows the same
 *    output on every platform.
 *  - **It does not drop the calls.** A shim that swallowed everything would let
 *    eighteen files' worth of diagnostics vanish silently, and the `TranslateDiag`
 *    waterfall forensics are load-bearing for support (they are the only record
 *    of *which* backend produced a degraded pass).
 *
 * `Log.wtf` is included because upstream uses it in two places and a missing
 * overload would be a compile error rather than a decision.
 */
object Log {
    /** Install a sink to route these lines into the shell's own logging. */
    @Volatile
    var sink: ((level: Char, tag: String, message: String, t: Throwable?) -> Unit)? = null

    @Volatile
    var minimumLevel: Char = 'V'

    private val order = "VDIWEF"

    private fun emit(level: Char, tag: String, message: String, t: Throwable?) {
        if (order.indexOf(level) < order.indexOf(minimumLevel)) return
        sink?.invoke(level, tag, message, t) ?: run {
            System.err.println("$level/$tag: $message")
            t?.printStackTrace(System.err)
        }
    }

    @JvmStatic fun v(tag: String, msg: String): Int = level('V', tag, msg)
    @JvmStatic fun v(tag: String, msg: String, t: Throwable): Int = level('V', tag, msg, t)
    @JvmStatic fun d(tag: String, msg: String): Int = level('D', tag, msg)
    @JvmStatic fun d(tag: String, msg: String, t: Throwable): Int = level('D', tag, msg, t)
    @JvmStatic fun i(tag: String, msg: String): Int = level('I', tag, msg)
    @JvmStatic fun i(tag: String, msg: String, t: Throwable): Int = level('I', tag, msg, t)
    @JvmStatic fun w(tag: String, msg: String): Int = level('W', tag, msg)
    @JvmStatic fun w(tag: String, msg: String, t: Throwable): Int = level('W', tag, msg, t)
    @JvmStatic fun w(tag: String, msg: Throwable): Int = level('W', tag, msg.toString(), msg)
    @JvmStatic fun e(tag: String, msg: String): Int = level('E', tag, msg)
    @JvmStatic fun e(tag: String, msg: String, t: Throwable): Int = level('E', tag, msg, t)
    @JvmStatic fun wtf(tag: String, msg: String): Int = level('F', tag, msg)
    @JvmStatic fun wtf(tag: String, msg: String, t: Throwable): Int = level('F', tag, msg, t)

    @JvmStatic fun isLoggable(tag: String, level: Int): Boolean = true

    @JvmStatic fun getStackTraceString(t: Throwable?): String =
        t?.stackTraceToString() ?: ""

    private fun level(level: Char, tag: String, msg: String, t: Throwable? = null): Int {
        emit(level, tag, msg, t)
        return 0
    }
}

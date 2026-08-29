package com.liux.android.debugger_adapter.protocol

import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object Logging {
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    // Force UTF-8 explicitly so Chinese log lines render correctly even on
    // Windows JVMs that default the console encoding to GBK.
    private val errWriter: PrintWriter = PrintWriter(
        OutputStreamWriter(System.err, StandardCharsets.UTF_8),
        true
    )

    /**
     * Master switch for DEBUG-level output. DEBUG calls early-return when
     * false — no SimpleDateFormat, no StringBuilder, no I/O — because at
     * startup the class-prepare handler can log thousands of these per
     * second, and each flush is a synchronous disk hit that visibly slows
     * attach.
     *
     * Flip on via the `debug` DAP argument, environment variable
     * `DEBUGGER_ANDROID_DEBUG=1`, or programmatically via
     * `Logging.setDebugEnabled(true)` from tests.
     */
    private val debugEnabled = AtomicBoolean(
        System.getenv("DEBUGGER_ANDROID_DEBUG")?.equals("1") == true
    )

    fun isDebugEnabled(): Boolean = debugEnabled.get()

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled.set(enabled)
        info("DEBUG logging ${if (enabled) "enabled" else "disabled"}")
    }

    @Synchronized
    fun info(msg: String) = log("INFO", msg, null)

    @Synchronized
    fun warn(msg: String) = log("WARN", msg, null)

    @Synchronized
    fun error(msg: String, t: Throwable? = null) = log("ERROR", msg, t)

    /**
     * DEBUG-level log. Short-circuits when debug output is disabled so the
     * format / flush cost is paid only by the call sites that actually
     * produce output. The argument is built lazily via a lambda so the
     * caller's string concat also doesn't run when disabled.
     */
    @Synchronized
    fun debug(msg: String) {
        if (!debugEnabled.get()) return
        log("DEBUG", msg, null)
    }

    fun debug(provide: () -> String) {
        if (!debugEnabled.get()) return
        log("DEBUG", provide(), null)
    }

    private fun log(level: String, msg: String, t: Throwable?) {
        val tsStr = ts.format(Date())
        val line = buildString {
            append("[$tsStr] [$level] $msg")
            if (t != null) {
                append('\n')
                append(t.stackTraceToString())
            }
        }
        errWriter.println(line)
        errWriter.flush()
    }
}
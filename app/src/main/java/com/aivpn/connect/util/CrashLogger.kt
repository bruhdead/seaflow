package com.aivpn.connect.util

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

object CrashLogger {
    /**
     * Install a global uncaught-exception handler that persists the trace to
     * SharedPreferences (via DiagnosticsPrefs) before delegating to the previously
     * installed handler. Idempotent — safe to call every process start.
     */
    fun install(context: Context) {
        val appCtx = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val msg = "thread=${thread.name} exc=${throwable.javaClass.simpleName}: ${throwable.message}\n$sw"
                DiagnosticsPrefs.recordCrash(appCtx, msg)
                LogBuffer.append('E', "Crash", "Uncaught ${throwable.javaClass.simpleName}: ${throwable.message}")
            } catch (_: Throwable) {
                // Never let crash logger itself abort the abort.
            }
            prev?.uncaughtException(thread, throwable)
        }
    }
}

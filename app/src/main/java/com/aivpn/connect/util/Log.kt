package com.aivpn.connect.util

/**
 * Drop-in replacement for android.util.Log that mirrors every call to
 * LogBuffer so the in-app Logs screen can display recent events.
 * Signatures match android.util.Log — replace the import to enable.
 */
object Log {
    fun d(tag: String, msg: String): Int {
        LogBuffer.append('D', tag, msg)
        return android.util.Log.d(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        LogBuffer.append('W', tag, msg)
        return android.util.Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable): Int {
        LogBuffer.append('W', tag, "$msg (${tr.javaClass.simpleName}: ${tr.message})")
        return android.util.Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String): Int {
        LogBuffer.append('E', tag, msg)
        return android.util.Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable): Int {
        LogBuffer.append('E', tag, "$msg (${tr.javaClass.simpleName}: ${tr.message})")
        return android.util.Log.e(tag, msg, tr)
    }

    fun i(tag: String, msg: String): Int {
        LogBuffer.append('I', tag, msg)
        return android.util.Log.i(tag, msg)
    }

    fun v(tag: String, msg: String): Int {
        LogBuffer.append('V', tag, msg)
        return android.util.Log.v(tag, msg)
    }
}

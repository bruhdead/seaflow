package com.aivpn.connect.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Small key-value store for diagnostic breadcrumbs that must survive process death:
 *   - timestamp of last clean tunnel shutdown
 *   - last uncaught exception trace
 *   - last known underlying transport label
 *
 * Used on next startup to print a "what happened before I died" summary line
 * so log readers can correlate an abrupt silence in the previous session with
 * OS kill / OOM / crash.
 */
object DiagnosticsPrefs {
    private const val PREF_NAME = "seaflow_diag"

    private const val KEY_LAST_SESSION_END = "last_session_end_ms"
    private const val KEY_LAST_SESSION_REASON = "last_session_reason"
    private const val KEY_LAST_CRASH_MS = "last_crash_ms"
    private const val KEY_LAST_CRASH_TRACE = "last_crash_trace"
    private const val KEY_LAST_TRANSPORT = "last_transport"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun recordSessionEnd(ctx: Context, reason: String) {
        prefs(ctx).edit()
            .putLong(KEY_LAST_SESSION_END, System.currentTimeMillis())
            .putString(KEY_LAST_SESSION_REASON, reason)
            .apply()
    }

    fun readLastSessionEnd(ctx: Context): Pair<Long, String?> {
        val p = prefs(ctx)
        return p.getLong(KEY_LAST_SESSION_END, 0L) to p.getString(KEY_LAST_SESSION_REASON, null)
    }

    fun recordCrash(ctx: Context, trace: String) {
        prefs(ctx).edit()
            .putLong(KEY_LAST_CRASH_MS, System.currentTimeMillis())
            .putString(KEY_LAST_CRASH_TRACE, trace.take(4_000))
            .apply()
    }

    fun consumeLastCrash(ctx: Context): Pair<Long, String>? {
        val p = prefs(ctx)
        val ts = p.getLong(KEY_LAST_CRASH_MS, 0L)
        val tr = p.getString(KEY_LAST_CRASH_TRACE, null)
        if (ts == 0L || tr == null) return null
        p.edit().remove(KEY_LAST_CRASH_MS).remove(KEY_LAST_CRASH_TRACE).apply()
        return ts to tr
    }

    fun recordTransport(ctx: Context, label: String) {
        prefs(ctx).edit().putString(KEY_LAST_TRANSPORT, label).apply()
    }

    fun lastTransport(ctx: Context): String? =
        prefs(ctx).getString(KEY_LAST_TRANSPORT, null)
}

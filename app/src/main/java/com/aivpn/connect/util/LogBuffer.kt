package com.aivpn.connect.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton in-memory ring buffer mirroring app log messages so the
 * built-in Logs screen can show them without READ_LOGS permission.
 * Thread-safe; backed by a Compose/StateFlow-observable list.
 */
object LogBuffer {
    private const val MAX_LINES = 800

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>(MAX_LINES + 8)
    private val lock = Any()

    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()

    fun append(level: Char, tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} $level/$tag: $msg"
        val snapshot = synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            lines.toList()
        }
        _flow.value = snapshot
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        _flow.value = emptyList()
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }
}

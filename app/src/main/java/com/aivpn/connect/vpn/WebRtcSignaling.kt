package com.aivpn.connect.vpn

import com.aivpn.connect.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for the seaflow-signal Cloudflare Worker. Opens a connection
 * to `wss://<signaling>/ws?role=client&room=<ROOM_ID>`, forwards offer/answer/ICE
 * JSON messages as-is, and invokes the callbacks on the OkHttp dispatcher thread.
 *
 * Lifecycle:
 *   start()  → opens the socket, invokes onOpen/onMessage/onError/onClosed.
 *   send(..) → text frame to the peer side.
 *   stop()   → normal close; callbacks stop after onClosed.
 */
class WebRtcSignaling(
    private val signalingBaseUrl: String,   // "https://seaflow-signal.xxx.workers.dev"
    private val roomId: String,             // 16..128 chars
    private val onMessage: (JSONObject) -> Unit,
    private val onOpen: () -> Unit,
    private val onClosed: (code: Int, reason: String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    companion object {
        private const val TAG = "Signaling"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)      // streaming read
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var ws: WebSocket? = null
    private val stopped = AtomicBoolean(false)

    fun start() {
        val wsUrl = buildWsUrl()
        Log.d(TAG, "dialing $wsUrl")
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "open (http ${response.code})")
                if (!stopped.get()) onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get()) return
                try {
                    onMessage(JSONObject(text))
                } catch (e: Exception) {
                    Log.w(TAG, "bad json: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped.get()) return
                Log.e(TAG, "failure: ${t.javaClass.simpleName}: ${t.message}")
                onError(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closing $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closed $code $reason")
                if (!stopped.get()) onClosed(code, reason)
            }
        })
    }

    fun send(msg: JSONObject) {
        val w = ws ?: return
        val ok = w.send(msg.toString())
        if (!ok) Log.w(TAG, "enqueue failed: ${msg.optString("type")}")
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    private fun buildWsUrl(): String {
        val scheme = when {
            signalingBaseUrl.startsWith("https://") -> "wss://"
            signalingBaseUrl.startsWith("http://")  -> "ws://"
            else -> "wss://"
        }
        val host = signalingBaseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        return "$scheme$host/ws?role=client&room=$roomId"
    }
}

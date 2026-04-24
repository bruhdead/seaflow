package com.aivpn.connect.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import com.aivpn.connect.util.DiagnosticsPrefs
import com.aivpn.connect.util.Log
import com.aivpn.connect.MainActivity
import kotlinx.coroutines.*
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Minimal VPN service using pure-Kotlin tunnel.
 * No Rust, no JNI, no native libraries.
 */
class VpnService : android.net.VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.aivpn.connect.CONNECT"
        const val ACTION_DISCONNECT = "com.aivpn.connect.DISCONNECT"
        private const val TAG = "VpnService"
        private const val CHANNEL_ID = "aivpn_connect"
        private const val NOTIFICATION_ID = 1
        private const val TUN_MTU = 1346
        private const val NETWORK_EVENT_DEBOUNCE_MS = 3000L
        private const val ROAM_SOCKET_BUF_SIZE = 4 * 1024 * 1024

        // Observable state for UI
        @Volatile var isRunning = false
        @Volatile var statusText = ""
        @Volatile var uploadBytes = 0L
        @Volatile var downloadBytes = 0L
        var statusCallback: ((Boolean, String) -> Unit)? = null
        var trafficCallback: ((Long, Long) -> Unit)? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnel: Tunnel? = null
    private var tunnelJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var manualDisconnect = false

    // Network change detection
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentUnderlyingNetwork: Network? = null
    @Volatile private var networkTrigger: Boolean = false
    @Volatile private var lastNetworkEventAtMs: Long = 0L

    // Saved params for reconnect — common
    private var server: String? = null
    private var serverKey: ByteArray? = null
    private var psk: ByteArray? = null
    private var vpnIp: String? = null

    // Saved params for reconnect — WebRTC transport. When non-null, runOnce
    // builds a WebRtcTunnel instead of a plain UDP KotlinTunnel.
    private var wrtcSignalingUrl: String? = null
    private var wrtcRoomId: String? = null
    private var wrtcIceJson: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val s = intent.getStringExtra("server") ?: return START_NOT_STICKY
                val k = intent.getByteArrayExtra("server_key") ?: return START_NOT_STICKY

                // Ignore duplicate connect to same server while already running
                if (s == server && tunnelJob?.isActive == true) {
                    Log.d(TAG, "Ignoring duplicate CONNECT to $s")
                    return START_STICKY
                }

                server = s
                serverKey = k
                psk = intent.getByteArrayExtra("psk")
                vpnIp = intent.getStringExtra("vpn_ip")
                // Optional WebRTC transport params (present for aivpn-wrtc:// keys)
                wrtcSignalingUrl = intent.getStringExtra("wrtc_signaling")
                wrtcRoomId = intent.getStringExtra("wrtc_room")
                wrtcIceJson = intent.getStringExtra("wrtc_ice_json")
                startTunnel()
            }
            ACTION_DISCONNECT -> stopTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        // Stop any existing tunnel first
        tunnel?.stop()
        tunnelJob?.cancel()
        manualDisconnect = false

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        // Keep CPU awake while tunnel is active
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aivpn:tunnel")
            wakeLock?.acquire()
        }

        // Register NetworkCallback to detect WiFi ↔ LTE switches
        registerNetworkCallback()

        Log.d(TAG, "startTunnel: server=$server")

        val sessionStart = System.currentTimeMillis()
        tunnelJob = scope.launch {
            var retryDelay = 500L
            var attempt = 0
            while (isActive && !manualDisconnect) {
                attempt++
                val attemptStart = System.currentTimeMillis()
                val wasNetworkTriggered = networkTrigger
                networkTrigger = false
                try {
                    Log.d(TAG, "Tunnel attempt #$attempt (session uptime ${(attemptStart - sessionStart) / 1000}s, netTrigger=$wasNetworkTriggered)")
                    runOnce()
                    val dur = (System.currentTimeMillis() - attemptStart) / 1000
                    Log.d(TAG, "Tunnel attempt #$attempt ended cleanly after ${dur}s (rekey or network change)")
                    // Network-triggered or clean exit — reconnect immediately
                    retryDelay = if (wasNetworkTriggered || networkTrigger) 0L else 500L
                } catch (e: CancellationException) {
                    val dur = (System.currentTimeMillis() - attemptStart) / 1000
                    Log.w(TAG, "Tunnel attempt #$attempt cancelled after ${dur}s")
                    throw e
                } catch (e: Exception) {
                    val dur = (System.currentTimeMillis() - attemptStart) / 1000
                    Log.e(TAG, "Tunnel error #$attempt after ${dur}s: ${e.javaClass.simpleName}: ${e.message}")
                    isRunning = false
                    statusText = "Reconnecting..."
                    statusCallback?.invoke(false, statusText)
                    updateNotification(statusText)
                    if (manualDisconnect) break

                    // Network-triggered errors → reconnect immediately; otherwise exponential backoff
                    val delayMs = if (networkTrigger) 0L else retryDelay
                    if (delayMs > 0) delay(delayMs)
                    retryDelay = if (networkTrigger) 500L else (retryDelay * 2).coerceAtMost(8000L)
                }
            }
            // Exited loop
            val totalSec = (System.currentTimeMillis() - sessionStart) / 1000
            Log.w(TAG, "Tunnel loop exited after ${totalSec}s (attempts=$attempt, manualDisconnect=$manualDisconnect)")
            isRunning = false
            if (!manualDisconnect) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runOnce() {
        val host = parseHost(server!!)
        val port = parsePort(server!!)
        val key = serverKey!!
        val tunAddr = vpnIp ?: "10.0.0.2"

        // Build TUN
        val builder = Builder()
            .setSession("AIVPN")
            .addAddress(tunAddr, 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.4.4")
            .addDnsServer("1.0.0.1")
            .setMtu(TUN_MTU)
            .setMetered(false)

        val pfd = builder.establish()
            ?: throw Exception("VPN permission revoked")

        val tunFd = pfd.detachFd()
        setUnderlyingNetworks(null)

        isRunning = true
        statusText = "Connecting..."
        statusCallback?.invoke(false, statusText)
        updateNotification(statusText)

        val t: Tunnel = if (wrtcSignalingUrl != null && wrtcRoomId != null) {
            Log.d(TAG, "runOnce: WebRTC transport (signaling=${wrtcSignalingUrl}, room=${wrtcRoomId})")
            val ice = try {
                IceServerSpec.parseJsonArray(org.json.JSONArray(wrtcIceJson ?: "[]"))
            } catch (e: Exception) {
                Log.e(TAG, "runOnce: bad ICE JSON: ${e.message}"); emptyList()
            }
            WebRtcTunnel(
                vpnService = this,
                tunFd = tunFd,
                serverKey = key,
                psk = psk,
                signalingUrl = wrtcSignalingUrl!!,
                roomId = wrtcRoomId!!,
                iceServers = ice,
            )
        } else {
            Log.d(TAG, "runOnce: UDP transport ($host:$port)")
            KotlinTunnel(this, tunFd, host, port, key, psk)
        }
        t.onTunnelReady = { h ->
            isRunning = true
            statusText = "Connected to $h"
            statusCallback?.invoke(true, statusText)
            updateNotification(statusText)
        }
        tunnel = t

        // Poll traffic stats
        val statsJob = scope.launch {
            while (isActive) {
                delay(1000L)
                uploadBytes = t.uploadBytes.get()
                downloadBytes = t.downloadBytes.get()
                trafficCallback?.invoke(uploadBytes, downloadBytes)
            }
        }

        val runStart = System.currentTimeMillis()
        try {
            val error = t.run()
            val dur = (System.currentTimeMillis() - runStart) / 1000
            val transportName = if (t is WebRtcTunnel) "WebRtcTunnel" else "KotlinTunnel"
            if (error.isNotEmpty()) {
                Log.e(TAG, "$transportName.run() returned error after ${dur}s: $error")
                throw RuntimeException(error)
            }
            Log.d(TAG, "$transportName.run() returned cleanly after ${dur}s")
        } finally {
            tunnel = null
            statsJob.cancel()
            isRunning = false
        }
    }

    private fun stopTunnel() {
        Log.d(TAG, "stopTunnel (manual)")
        DiagnosticsPrefs.recordSessionEnd(this, "user stopTunnel")
        manualDisconnect = true
        unregisterNetworkCallback()
        tunnel?.stop()
        tunnelJob?.cancel()
        tunnelJob = null
        isRunning = false
        uploadBytes = 0L
        downloadBytes = 0L
        statusText = "Disconnected"
        statusCallback?.invoke(false, statusText)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Fires when another VPN app takes over or the user disables our VPN via the
        // system toggle. Default VpnService.onRevoke() would stopSelf() silently.
        Log.w(TAG, "onRevoke() — VPN permission revoked (another VPN or system toggle)")
        DiagnosticsPrefs.recordSessionEnd(this, "onRevoke")
        tunnel?.stop()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        DiagnosticsPrefs.recordSessionEnd(this, "onDestroy")
        manualDisconnect = true
        unregisterNetworkCallback()
        tunnel?.stop()
        tunnelJob?.cancel()
        scope.cancel()
        isRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ──────────── Network change detection ────────────

    /**
     * Detects when the default underlying network changes (WiFi ↔ LTE, loss/gain).
     * On change, stops the current tunnel socket — the retry loop then creates
     * a fresh UDP socket bound to the new network.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        currentUnderlyingNetwork = findUsableUnderlyingNetwork(cm)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (!isUsableUnderlyingNetwork(caps)) return

                val transport = transportLabel(cm, network)
                DiagnosticsPrefs.recordTransport(this@VpnService, transport)

                // Only react to changes in the *default* (active) network.
                // Android also fires onAvailable for secondary nets (e.g. WiFi appears
                // while LTE stays default) — those must not tear down a working tunnel.
                val activeDefault = cm.activeNetwork
                if (activeDefault == null || activeDefault != network) {
                    Log.d(TAG, "Underlying network available: $network [$transport] (not current default $activeDefault) — ignoring")
                    return
                }

                val previous = currentUnderlyingNetwork
                val prevTransport = transportLabel(cm, previous)
                currentUnderlyingNetwork = network
                Log.d(TAG, "Underlying network available: $network [$transport] (previous=$previous [$prevTransport])")

                if (previous != null && previous != network && isRunning) {
                    val now = System.currentTimeMillis()
                    if (now - lastNetworkEventAtMs >= NETWORK_EVENT_DEBOUNCE_MS) {
                        lastNetworkEventAtMs = now
                        handleNetworkChange(network, "switched [$prevTransport]→[$transport]")
                    }
                }
            }

            override fun onLost(network: Network) {
                val transport = transportLabel(cm, network)
                Log.d(TAG, "Underlying network lost: $network [$transport]")
                if (network == currentUnderlyingNetwork) {
                    currentUnderlyingNetwork = findUsableUnderlyingNetwork(cm)
                }
                val replacement = currentUnderlyingNetwork
                val replTransport = transportLabel(cm, replacement)
                if (replacement != null && isRunning) {
                    val now = System.currentTimeMillis()
                    if (now - lastNetworkEventAtMs >= NETWORK_EVENT_DEBOUNCE_MS) {
                        lastNetworkEventAtMs = now
                        DiagnosticsPrefs.recordTransport(this@VpnService, replTransport)
                        handleNetworkChange(replacement, "lost [$transport]→[$replTransport]")
                    }
                } else if (replacement == null && isRunning) {
                    Log.d(TAG, "No usable network after loss [$transport] — stopping tunnel for fast reconnect")
                    networkTrigger = true
                    tunnel?.stop()
                }
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.d(TAG, "NetworkCallback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback: ${e.message}", e)
        }
    }

    /**
     * Soft-roam to a new underlying network without rehandshake.
     * If the tunnel is past handshake phase and we can bind a fresh UDP socket
     * to `newNetwork`, we swap the socket in-place; crypto keys/counters/ratchet
     * are preserved and the server accepts the new endpoint automatically
     * on the first valid packet it decrypts from the new (src_ip, src_port).
     *
     * Falls back to full restart when the tunnel isn't ready yet or
     * the new network refuses to bind the socket.
     */
    private fun handleNetworkChange(newNetwork: Network, reason: String) {
        val t = tunnel
        if (t == null) {
            Log.d(TAG, "Network change ($reason): no tunnel instance, ignoring")
            return
        }
        // Soft-roam is a UDP-only optimization (rebinds the DatagramSocket to the new
        // underlying network). WebRTC handles roaming internally via ICE restart, so
        // for WebRtcTunnel we just do a full restart which is cheap (PC reconnects
        // in 1–3 s through TURN).
        if (t !is KotlinTunnel) {
            Log.d(TAG, "Network change ($reason) for non-UDP tunnel — full restart (ICE will reselect)")
            networkTrigger = true
            t.stop()
            return
        }
        if (!t.tunnelReady) {
            // Still handshaking — safer to do a full restart on the new path
            Log.d(TAG, "Network change ($reason) during handshake — full restart")
            networkTrigger = true
            t.stop()
            return
        }
        val ip = t.serverAddress
        val srv = server
        if (ip == null || srv == null) {
            Log.d(TAG, "Network change ($reason): missing cached server IP — full restart")
            networkTrigger = true
            t.stop()
            return
        }
        val port = parsePort(srv)
        var newSocket: DatagramSocket? = null
        try {
            newSocket = DatagramSocket()
            newNetwork.bindSocket(newSocket)
            protect(newSocket)
            newSocket.connect(InetSocketAddress(ip, port))
            newSocket.sendBufferSize = ROAM_SOCKET_BUF_SIZE
            newSocket.receiveBufferSize = ROAM_SOCKET_BUF_SIZE
            if (t.rebindSocket(newSocket)) {
                Log.d(TAG, "Soft-roam ($reason) → $newNetwork: socket rebound to $ip:$port")
                setUnderlyingNetworks(arrayOf(newNetwork))
                return
            }
            // Tunnel rejected rebind (stopped / not ready) — clean up and fall back
            try { newSocket.close() } catch (_: Exception) {}
            Log.d(TAG, "Soft-roam ($reason) rejected by tunnel — full restart")
            networkTrigger = true
            t.stop()
        } catch (e: Exception) {
            try { newSocket?.close() } catch (_: Exception) {}
            Log.w(TAG, "Soft-roam ($reason) to $newNetwork failed: ${e.javaClass.simpleName}: ${e.message}")
            networkTrigger = true
            t.stop()
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
        currentUnderlyingNetwork = null
    }

    private fun findUsableUnderlyingNetwork(cm: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            isUsableUnderlyingNetwork(caps)
        }
    }

    private fun isUsableUnderlyingNetwork(caps: NetworkCapabilities): Boolean {
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Human-readable transport label (WIFI/CELLULAR/ETHERNET/…) for logging &
     * post-kill diagnostics. Returns "unknown" when caps are null.
     */
    private fun transportLabel(cm: ConnectivityManager, network: Network?): String {
        if (network == null) return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BT"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
    }

    // ──────────── Helpers ────────────

    private fun parseHost(addr: String): String {
        val last = addr.lastIndexOf(':')
        return if (last > 0) addr.substring(0, last) else addr
    }

    private fun parsePort(addr: String): Int {
        val last = addr.lastIndexOf(':')
        return if (last > 0) addr.substring(last + 1).toIntOrNull() ?: 443 else 443
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AIVPN Connect")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}

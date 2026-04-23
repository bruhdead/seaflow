package com.aivpn.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.aivpn.connect.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.aivpn.connect.crypto.CryptoSelfTest
import com.aivpn.connect.ui.AivpnConnectTheme
import com.aivpn.connect.ui.LogsScreen
import com.aivpn.connect.ui.VpnScreen
import com.aivpn.connect.ui.VpnUiState
import com.aivpn.connect.vpn.VpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var uiState by mutableStateOf(VpnUiState())
    private var logsOpen by mutableStateOf(false)
    private var connectionStartTime = 0L

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "VPN permission result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Log.w(TAG, "VPN permission denied")
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            uiState = uiState.copy(connecting = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crypto self-test
        CryptoSelfTest.runAll()

        // Load saved key
        val prefs = getSharedPreferences("aivpn_connect", MODE_PRIVATE)
        val savedKey = prefs.getString("connection_key", "") ?: ""
        uiState = uiState.copy(connectionKey = savedKey)

        // Restore state if service is running
        if (VpnService.isRunning) {
            uiState = uiState.copy(
                connected = true,
                statusText = VpnService.statusText,
            )
            connectionStartTime = System.currentTimeMillis()
        }

        setContent {
            AivpnConnectTheme {
                if (logsOpen) {
                    LogsScreen(onClose = { logsOpen = false })
                } else {
                    VpnScreen(
                        state = uiState,
                        onKeyChanged = { key ->
                            uiState = uiState.copy(connectionKey = key)
                            prefs.edit().putString("connection_key", key).apply()
                        },
                        onConnect = { connect() },
                        onDisconnect = { disconnect() },
                        onOpenLogs = { logsOpen = true },
                    )
                }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (logsOpen) {
            logsOpen = false
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()

        VpnService.statusCallback = { connected, text ->
            runOnUiThread {
                val wasConnected = uiState.connected
                uiState = uiState.copy(
                    connected = connected,
                    connecting = false,
                    statusText = text,
                )
                if (connected && !wasConnected) {
                    connectionStartTime = System.currentTimeMillis()
                    startTimer()
                }
                if (!connected) connectionStartTime = 0L
            }
        }

        VpnService.trafficCallback = { up, down ->
            runOnUiThread {
                uiState = uiState.copy(uploadBytes = up, downloadBytes = down)
            }
        }

        if (VpnService.isRunning && connectionStartTime == 0L) {
            connectionStartTime = System.currentTimeMillis()
            startTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            VpnService.statusCallback = null
            VpnService.trafficCallback = null
        }
    }

    private fun startTimer() {
        lifecycleScope.launch {
            while (uiState.connected && connectionStartTime > 0L) {
                val elapsed = (System.currentTimeMillis() - connectionStartTime) / 1000
                uiState = uiState.copy(timerSeconds = elapsed)
                delay(1000L)
            }
        }
    }

    private var lastConnectAt = 0L

    private fun connect() {
        val now = System.currentTimeMillis()
        if (now - lastConnectAt < 2000L) {
            Log.d(TAG, "connect: debounce")
            return
        }
        lastConnectAt = now

        val key = uiState.connectionKey.trim()
        Log.d(TAG, "connect: key length=${key.length}")
        if (key.isEmpty()) {
            Toast.makeText(this, "Enter connection key", Toast.LENGTH_SHORT).show()
            return
        }

        val parsed = parseConnectionKey(key)
        if (parsed == null) {
            Log.w(TAG, "connect: failed to parse key")
            Toast.makeText(this, "Invalid connection key", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "connect: server=${parsed.server}, keySize=${parsed.serverKey.size}, hasPsk=${parsed.psk != null}, vpnIp=${parsed.vpnIp}")

        uiState = uiState.copy(connecting = true, statusText = "Preparing...")

        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            Log.d(TAG, "connect: requesting VPN permission")
            vpnPermissionLauncher.launch(intent)
        } else {
            Log.d(TAG, "connect: VPN permission already granted")
            startVpnService()
        }
    }

    private fun disconnect() {
        val intent = Intent(this, VpnService::class.java).apply {
            action = VpnService.ACTION_DISCONNECT
        }
        startService(intent)
        uiState = uiState.copy(
            connected = false, connecting = false,
            statusText = "Disconnected",
            uploadBytes = 0L, downloadBytes = 0L, timerSeconds = 0L,
        )
        connectionStartTime = 0L
    }

    private fun startVpnService() {
        Log.d(TAG, "startVpnService called")
        val key = uiState.connectionKey.trim()
        val parsed = parseConnectionKey(key) ?: return

        val intent = Intent(this, VpnService::class.java).apply {
            action = VpnService.ACTION_CONNECT
            putExtra("server", parsed.server)
            putExtra("server_key", parsed.serverKey)
            putExtra("psk", parsed.psk)
            putExtra("vpn_ip", parsed.vpnIp)
        }
        startForegroundService(intent)
        uiState = uiState.copy(connecting = true, statusText = "Connecting...")
    }

    // ──────────── Key parsing ────────────

    data class ParsedKey(
        val server: String,
        val serverKey: ByteArray,
        val psk: ByteArray?,
        val vpnIp: String?,
    )

    private fun parseConnectionKey(key: String): ParsedKey? {
        val raw = key.trim()
        val payload = if (raw.startsWith("aivpn://")) raw.removePrefix("aivpn://") else raw
        Log.d(TAG, "parseKey: payload length=${payload.length}, starts='${payload.take(20)}...'")

        val jsonBytes = flexBase64Decode(payload)
        if (jsonBytes == null) {
            Log.w(TAG, "parseKey: base64 decode of outer payload failed")
            return null
        }

        val jsonStr = String(jsonBytes)
        Log.d(TAG, "parseKey: json=$jsonStr")

        return try {
            val json = JSONObject(jsonStr)
            val server = json.getString("s")
            val serverKeyB64 = json.getString("k")
            val pskB64 = json.optString("p", "")
            val vpnIp = json.optString("i", "")
            Log.d(TAG, "parseKey: server=$server, k_len=${serverKeyB64.length}, p_len=${pskB64.length}")

            val serverKey = flexBase64Decode(serverKeyB64)
            if (serverKey == null) {
                Log.w(TAG, "parseKey: base64 decode of server key failed: '$serverKeyB64'")
                return null
            }
            val psk = if (pskB64.isNotEmpty()) {
                val p = flexBase64Decode(pskB64)
                if (p == null) Log.w(TAG, "parseKey: base64 decode of psk failed: '$pskB64'")
                p
            } else null

            Log.d(TAG, "parseKey: serverKey.size=${serverKey.size}, psk.size=${psk?.size}")
            if (serverKey.size != 32) {
                Log.w(TAG, "parseKey: serverKey size ${serverKey.size} != 32")
                return null
            }
            if (psk != null && psk.size != 32) {
                Log.w(TAG, "parseKey: psk size ${psk.size} != 32")
                return null
            }

            ParsedKey(server, serverKey, psk, vpnIp.ifEmpty { null })
        } catch (e: Exception) {
            Log.w(TAG, "parseKey: exception: ${e.message}")
            null
        }
    }

    private fun flexBase64Decode(input: String): ByteArray? {
        // Try URL-safe no-padding first, then standard, then default
        for (flags in intArrayOf(
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP,
            android.util.Base64.DEFAULT,
        )) {
            try {
                return android.util.Base64.decode(input, flags)
            } catch (_: Exception) {}
        }
        return null
    }
}

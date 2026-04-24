package com.aivpn.connect.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * Common surface for tunnel transports. Implemented by [KotlinTunnel] (raw UDP) and
 * [WebRtcTunnel] (DataChannel via STUN/TURN). VpnService holds one at a time and
 * doesn't care about the transport specifics beyond this interface.
 *
 * Transport-specific features (UDP soft-roam via rebindSocket, WebRTC ICE-restart,
 * …) are accessed via `is` checks where actually needed — not via the interface.
 */
interface Tunnel {
    val uploadBytes: AtomicLong
    val downloadBytes: AtomicLong
    val tunnelReady: Boolean

    var onTunnelReady: ((String) -> Unit)?

    fun stop()
    suspend fun run(): String
}

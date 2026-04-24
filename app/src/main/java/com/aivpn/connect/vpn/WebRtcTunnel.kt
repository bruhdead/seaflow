package com.aivpn.connect.vpn

import android.content.Context
import android.net.VpnService
import android.system.Os
import com.aivpn.connect.crypto.AivpnCrypto
import com.aivpn.connect.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * WebRTC-based alternative to [KotlinTunnel]. All aivpn crypto (X25519 +
 * ChaCha20-Poly1305 + PFS ratchet) is reused verbatim — only the transport
 * is swapped from a raw UDP socket to a DataChannel terminated at
 * `seaflow-peer` on the VPS.
 *
 * Use when the underlying network blocks UDP to the aivpn-server's IP
 * (corporate DPI, UserGate blacklists, etc.). WebRTC traffic is disguised
 * as a Zoom/Meet-style call to public TURN relays and Cloudflare Edge IPs,
 * neither of which can be blocked without breaking business video calls.
 */
class WebRtcTunnel(
    private val vpnService: VpnService,
    private val tunFd: Int,
    private val serverKey: ByteArray,
    private val psk: ByteArray?,
    private val signalingUrl: String,
    private val roomId: String,
    private val iceServers: List<IceServerSpec>,
) : Tunnel {
    companion object {
        private const val TAG = "WebRtcTunnel"
        private const val BUF_SIZE = 1500
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
        private const val REKEY_INTERVAL_MS = 1_800_000L

        @Volatile private var factoryInitialized = false
        private lateinit var factory: PeerConnectionFactory
        private lateinit var eglBase: EglBase

        @Synchronized
        fun ensureFactory(context: Context) {
            if (factoryInitialized) return
            val opts = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(opts)
            eglBase = EglBase.create()

            // Ignore VPN-typed interfaces when enumerating local ICE candidates.
            // Once our own VpnService.establish() adds tun0, libwebrtc's NetworkMonitor
            // otherwise tries to include it in the candidate pool — which sends
            // traffic back into our own tunnel, creating a recursive loop that
            // kills STUN consent freshness checks after ~5-6 s.
            //
            // ADAPTER_TYPE bitmask values (from //rtc_base/network_constants.h):
            //   UNKNOWN=0, ETHERNET=1, WIFI=2, CELLULAR=4, VPN=8, LOOPBACK=16,
            //   ANY=32, CELLULAR_2G=64, CELLULAR_3G=128, CELLULAR_4G=256,
            //   CELLULAR_5G=512
            // Ignore VPN (8) + LOOPBACK (16).
            val factoryOptions = PeerConnectionFactory.Options().apply {
                networkIgnoreMask = 8 or 16
            }

            factory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            factoryInitialized = true
            Log.d(TAG, "PeerConnectionFactory initialized (networkIgnoreMask=VPN+LOOPBACK)")
        }
    }

    override val uploadBytes = AtomicLong(0L)
    override val downloadBytes = AtomicLong(0L)

    @Volatile override var tunnelReady: Boolean = false
        private set

    @Volatile var serverAddress: java.net.InetAddress? = null
        private set  // not applicable for WebRTC, always null

    override var onTunnelReady: ((String) -> Unit)? = null

    private val stopFlag = AtomicBoolean(false)
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var signaling: WebRtcSignaling? = null
    private var handshakeDone = CompletableDeferred<Unit>()
    private var dcOpen = CompletableDeferred<Unit>()
    private val crypto = AivpnCrypto(serverKey, psk)
    private val rxChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override fun stop() {
        if (!stopFlag.compareAndSet(false, true)) return
        try { signaling?.stop() } catch (_: Exception) {}
        try { dc?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        if (!handshakeDone.isCompleted) handshakeDone.completeExceptionally(RuntimeException("stopped"))
        if (!dcOpen.isCompleted) dcOpen.completeExceptionally(RuntimeException("stopped"))
    }

    /**
     * Not applicable for WebRTC — network roaming is handled inside libwebrtc
     * via ICE restart automatically. Returning false so VpnService falls back to
     * a full restart on underlying-network changes (cheap since ICE reselects
     * TURN relay in a second or two).
     */
    fun rebindSocket(@Suppress("UNUSED_PARAMETER") ignored: Any?): Boolean = false

    override suspend fun run(): String {
        val tunFile = makeTunFileDescriptor(tunFd)
        try {
            ensureFactory(vpnService)

            // 1. Start signaling
            val sigHandshake = CompletableDeferred<Unit>()
            signaling = WebRtcSignaling(
                signalingBaseUrl = signalingUrl,
                roomId = roomId,
                onOpen = {
                    Log.d(TAG, "signaling: open")
                    if (!sigHandshake.isCompleted) sigHandshake.complete(Unit)
                },
                onMessage = { obj -> handleSignalingMessage(obj) },
                onClosed = { code, reason ->
                    Log.w(TAG, "signaling: closed $code $reason")
                    if (!dcOpen.isCompleted) dcOpen.completeExceptionally(RuntimeException("signaling closed before DC opened"))
                },
                onError = { err ->
                    Log.e(TAG, "signaling: error ${err.message}")
                    if (!dcOpen.isCompleted) dcOpen.completeExceptionally(err)
                },
            ).also { it.start() }

            withTimeout(10_000L) { sigHandshake.await() }

            // 2. PeerConnection + DataChannel
            val iceConfig = PeerConnection.RTCConfiguration(iceServers.map { it.toWebRtc() }).apply {
                bundlePolicy = PeerConnection.BundlePolicy.BALANCED
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            val pcObserver = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "pc: signaling=$state")
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "pc: ice=$state")
                    if (state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        if (!dcOpen.isCompleted) dcOpen.completeExceptionally(RuntimeException("ice $state"))
                    }
                }
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "pc: connection=$state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "pc: ice-gathering=$state")
                }
                override fun onIceCandidate(cand: IceCandidate) {
                    sendIceCandidate(cand)
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel) {
                    // We are the offerer — DC is created locally. Unexpected inbound.
                    Log.w(TAG, "pc: unexpected inbound DataChannel '${channel.label()}'")
                }
                override fun onRenegotiationNeeded() {}
                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {}
            }

            pc = factory.createPeerConnection(iceConfig, pcObserver)
                ?: throw RuntimeException("createPeerConnection returned null")

            val dcInit = DataChannel.Init().apply {
                ordered = true
                // Leave maxRetransmits/maxPacketLifeTime unset → reliable, ordered
            }
            dc = pc!!.createDataChannel("seaflow", dcInit)
            dc!!.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    val st = dc?.state()
                    Log.d(TAG, "dc: state=$st")
                    if (st == DataChannel.State.OPEN && !dcOpen.isCompleted) dcOpen.complete(Unit)
                    if (st == DataChannel.State.CLOSED) {
                        if (!dcOpen.isCompleted) dcOpen.completeExceptionally(RuntimeException("dc closed before open"))
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    if (!handshakeDone.isCompleted) {
                        // Still handshaking — try processServerHello
                        try {
                            if (crypto.processServerHello(bytes)) {
                                Log.d(TAG, "handshake: ServerHello → PFS ratchet complete (${bytes.size} bytes)")
                                handshakeDone.complete(Unit)
                                return
                            } else {
                                Log.d(TAG, "handshake: ignored message (not ServerHello, ${bytes.size} bytes)")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "handshake: processServerHello exception: ${e.message}")
                        }
                        return
                    }
                    // Data phase — decrypt & enqueue for TUN writer
                    val ip = try { crypto.decryptDataPacket(bytes) } catch (_: Exception) { null }
                    if (ip != null && ip.isNotEmpty()) {
                        rxChannel.trySend(ip)
                        downloadBytes.addAndGet(ip.size.toLong())
                    }
                }
            })

            // 3. Create offer → signaling
            val offer = createOfferSuspend(pc!!)
            setLocalDescriptionSuspend(pc!!, offer)
            val offerMsg = JSONObject().apply {
                put("type", "offer")
                put("sdp", offer.description)
            }
            signaling!!.send(offerMsg)
            Log.d(TAG, "offer sent (${offer.description.length} bytes SDP)")

            // 4. Wait for DataChannel to open (ICE + answer + connectivity checks)
            withTimeout(30_000L) { dcOpen.await() }
            Log.d(TAG, "DataChannel OPEN")

            // 5. Handshake: send init packet, wait for ServerHello
            val init = crypto.buildInitPacket()
            sendDataChannel(init)
            Log.d(TAG, "handshake: init sent (${init.size} bytes)")
            withTimeout(HANDSHAKE_TIMEOUT_MS) { handshakeDone.await() }

            // 6. Notify ready
            tunnelReady = true
            Log.d(TAG, "tunnel ready")
            onTunnelReady?.invoke("webrtc:$roomId")

            // 7. Forward loop on DataChannel
            return runForwardingLoop(tunFile)
        } catch (e: TimeoutCancellationException) {
            // Timeouts aren't deliberate stops — propagate as tunnel error so
            // VpnService retries with backoff instead of reporting a clean exit.
            Log.e(TAG, "WebRtcTunnel timeout: ${e.message}")
            return e.message ?: "timeout"
        } catch (e: CancellationException) {
            Log.d(TAG, "WebRtcTunnel cancelled (deliberate stop)")
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "WebRtcTunnel error: ${e.javaClass.simpleName}: ${e.message}", e)
            return e.message ?: "Unknown error"
        } finally {
            tunnelReady = false
            stop()
            try { Os.close(tunFile) } catch (_: Exception) {}
        }
    }

    private suspend fun runForwardingLoop(tunFd: FileDescriptor): String = coroutineScope {
        val lastRxTime = AtomicLong(System.currentTimeMillis())
        val tunStart = System.currentTimeMillis()
        val keepaliveSent = AtomicLong(0L)

        // TUN → encrypt → DC
        val tunReaderJob = launch(Dispatchers.IO) {
            val buf = ByteArray(BUF_SIZE)
            Log.d(TAG, "tun reader started")
            try {
                while (isActive && !stopFlag.get()) {
                    val n = Os.read(tunFd, buf, 0, buf.size)
                    if (n <= 0) continue
                    if (buf[0].toInt().ushr(4) != 4) continue
                    val encrypted = crypto.encryptDataPacket(buf.copyOf(n))
                    if (!sendDataChannel(encrypted)) {
                        Log.w(TAG, "tun reader: dc send failed, stopping")
                        break
                    }
                    uploadBytes.addAndGet(n.toLong())
                }
            } catch (e: Exception) {
                Log.e(TAG, "tun reader error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // DC-rx queue → TUN
        val tunWriterJob = launch(Dispatchers.IO) {
            Log.d(TAG, "tun writer started")
            try {
                for (pkt in rxChannel) {
                    if (!isActive || stopFlag.get()) break
                    Os.write(tunFd, pkt, 0, pkt.size)
                    lastRxTime.set(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e(TAG, "tun writer error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Keepalive + health
        val healthJob = launch {
            val rekeyDeadline = System.currentTimeMillis() + REKEY_INTERVAL_MS
            while (isActive && !stopFlag.get()) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (stopFlag.get()) break
                if (System.currentTimeMillis() >= rekeyDeadline) {
                    Log.d(TAG, "rekey interval — reconnect")
                    break
                }
                val ka = crypto.buildKeepalivePacket()
                if (!sendDataChannel(ka)) {
                    Log.w(TAG, "keepalive: dc send failed")
                    break
                }
                val n = keepaliveSent.incrementAndGet()
                if (n % 4 == 0L) {
                    val ms = System.currentTimeMillis() - tunStart
                    Log.d(TAG, "keepalive #$n (uptime ${ms / 1000}s, up=${uploadBytes.get()}B down=${downloadBytes.get()}B)")
                }
            }
        }

        val exit = kotlinx.coroutines.selects.select<String> {
            tunReaderJob.onJoin { "tunReader" }
            tunWriterJob.onJoin { "tunWriter" }
            healthJob.onJoin { "health" }
        }
        val uptime = (System.currentTimeMillis() - tunStart) / 1000
        Log.w(TAG, "WebRtcTunnel exit after ${uptime}s: $exit finished first (keepalives=${keepaliveSent.get()}, up=${uploadBytes.get()}B down=${downloadBytes.get()}B)")
        tunReaderJob.cancel(); tunWriterJob.cancel(); healthJob.cancel()
        return@coroutineScope if (stopFlag.get()) "Tunnel stopped by request" else ""
    }

    private fun handleSignalingMessage(obj: JSONObject) {
        when (obj.optString("type")) {
            "answer" -> {
                val sdp = obj.optString("sdp")
                Log.d(TAG, "answer received (${sdp.length} bytes SDP)")
                val p = pc ?: return
                val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                p.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { Log.d(TAG, "remote description set") }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(err: String?) { Log.e(TAG, "set remote failure: $err") }
                }, desc)
            }
            "ice" -> {
                val c = obj.optJSONObject("candidate") ?: return
                val cand = IceCandidate(
                    c.optString("sdpMid", ""),
                    c.optInt("sdpMLineIndex", 0),
                    c.optString("candidate", ""),
                )
                pc?.addIceCandidate(cand)
            }
            "bye" -> {
                Log.w(TAG, "signaling: bye from peer")
                stop()
            }
        }
    }

    private fun sendIceCandidate(cand: IceCandidate) {
        val obj = JSONObject().apply {
            put("type", "ice")
            put("candidate", JSONObject().apply {
                put("candidate", cand.sdp)
                put("sdpMid", cand.sdpMid)
                put("sdpMLineIndex", cand.sdpMLineIndex)
            })
        }
        signaling?.send(obj)
    }

    private fun sendDataChannel(bytes: ByteArray): Boolean {
        val channel = dc ?: return false
        return try {
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
        } catch (e: Exception) {
            Log.w(TAG, "dc send exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private suspend fun createOfferSuspend(pc: PeerConnection): SessionDescription =
        suspendCoroutine { cont ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) { cont.resume(desc) }
                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) { cont.resumeWithException(RuntimeException("create offer: $err")) }
                override fun onSetFailure(err: String?) {}
            }, MediaConstraints())
        }

    private suspend fun setLocalDescriptionSuspend(pc: PeerConnection, desc: SessionDescription): Unit =
        suspendCoroutine { cont ->
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(err: String?) { cont.resumeWithException(RuntimeException("set local: $err")) }
            }, desc)
        }

    private fun makeTunFileDescriptor(fd: Int): FileDescriptor {
        val f = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(f, fd)
        val flags = Os.fcntlInt(f, android.system.OsConstants.F_GETFL, 0)
        Os.fcntlInt(f, android.system.OsConstants.F_SETFL, flags and android.system.OsConstants.O_NONBLOCK.inv())
        Log.d(TAG, "TUN fd=$fd set to blocking mode")
        return f
    }
}

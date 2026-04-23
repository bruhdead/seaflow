package com.aivpn.connect.vpn

import com.aivpn.connect.crypto.AivpnCrypto
import android.net.VpnService
import android.system.Os
import com.aivpn.connect.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-Kotlin VPN tunnel — replaces the Rust JNI core (libaivpn_core.so).
 *
 * Performs X25519 handshake, ChaCha20-Poly1305 encryption, keepalive,
 * and bidirectional TUN ↔ UDP forwarding using coroutines.
 */
class KotlinTunnel(
    private val vpnService: VpnService,
    private val tunFd: Int,
    private val serverHost: String,
    private val serverPort: Int,
    private val serverKey: ByteArray,
    private val psk: ByteArray?,
) {
    companion object {
        private const val TAG = "KotlinTunnel"
        private const val BUF_SIZE = 1500
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_RETRY_MS = 750
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
        private const val RX_SILENCE_MS = 120_000L
        private const val RX_CHECK_INTERVAL_MS = 2_000L
        private const val TX_WITHOUT_RX_TIMEOUT_MS = 20_000L
        private const val TX_WITHOUT_RX_MIN_BYTES = 64L * 1024L
        private const val REKEY_INTERVAL_MS = 1_800_000L // 30 min
        private const val CHANNEL_SIZE = 8192
        private const val SOCKET_BUF_SIZE = 4 * 1024 * 1024
    }

    val uploadBytes = AtomicLong(0L)
    val downloadBytes = AtomicLong(0L)

    private val stopFlag = AtomicBoolean(false)
    private val udpSocketRef = AtomicReference<DatagramSocket?>(null)

    @Volatile var serverAddress: InetAddress? = null
        private set
    @Volatile var tunnelReady: Boolean = false
        private set

    var onTunnelReady: ((String) -> Unit)? = null

    /**
     * Atomically swap the active UDP socket (soft-roam / WiFi↔LTE without rehandshake).
     * The old socket is closed, which triggers SocketException in sender/receiver loops;
     * those loops detect the swap and continue with the new socket. Crypto state
     * (session keys, counters, ratchet) is preserved — server accepts the new endpoint
     * automatically since a valid encrypted packet implicitly updates session's (ip, port).
     *
     * Returns true if swap was performed, false if tunnel is not ready / stopped.
     */
    fun rebindSocket(newSocket: DatagramSocket): Boolean {
        if (!tunnelReady || stopFlag.get()) return false
        val old = udpSocketRef.getAndSet(newSocket)
        try { old?.close() } catch (_: Exception) {}
        Log.d(TAG, "rebindSocket: old socket closed, new socket active")
        return true
    }

    /**
     * Blocking suspend function — runs the full tunnel session.
     * Returns "" on clean rekey exit, error message on failure.
     */
    suspend fun run(): String {
        val tunFd = makeTunFileDescriptor(this.tunFd)
        var socket: DatagramSocket? = null

        try {
            // 1. Init crypto (generates ephemeral keypair, derives Zero-RTT keys)
            val crypto = AivpnCrypto(serverKey, psk)

            // 2. DNS resolution (IPv4 only)
            val resolved = withContext(Dispatchers.IO) {
                InetAddress.getAllByName(serverHost)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
            } ?: return "Cannot resolve $serverHost to IPv4"
            serverAddress = resolved

            // 3. Create & protect UDP socket
            socket = DatagramSocket()
            udpSocketRef.set(socket)
            vpnService.protect(socket)
            socket.connect(InetSocketAddress(resolved, serverPort))
            socket.sendBufferSize = SOCKET_BUF_SIZE
            socket.receiveBufferSize = SOCKET_BUF_SIZE
            Log.d(TAG, "UDP socket created, protected, connected to $resolved:$serverPort")

            // 4. Handshake
            val handshakeResult = doHandshake(socket, crypto)
            if (handshakeResult != null) return handshakeResult

            // 5. Notify ready — after this point soft-roam is allowed
            tunnelReady = true
            Log.d(TAG, "Tunnel ready: host=$serverHost")
            onTunnelReady?.invoke(serverHost)

            // 6. Main forwarding loop (socket is read from udpSocketRef on each iteration)
            socket.soTimeout = 0 // blocking mode for main loop
            return runForwardingLoop(crypto, tunFd)

        } catch (e: CancellationException) {
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error: ${e.message}", e)
            return e.message ?: "Unknown error"
        } finally {
            tunnelReady = false
            try { udpSocketRef.getAndSet(null)?.close() } catch (_: Exception) {}
            try { Os.close(tunFd) } catch (_: Exception) {}
        }
    }

    fun stop() {
        stopFlag.set(true)
        try { udpSocketRef.get()?.close() } catch (_: Exception) {}
    }

    // ──────────── Handshake ────────────

    private suspend fun doHandshake(
        socket: DatagramSocket,
        crypto: AivpnCrypto
    ): String? = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        socket.soTimeout = HANDSHAKE_RETRY_MS

        // Send first init packet
        var initPkt = crypto.buildInitPacket()
        socket.send(DatagramPacket(initPkt, initPkt.size))
        Log.d(TAG, "Handshake: sent init packet (${initPkt.size} bytes)")
        Log.d(TAG, "Handshake: pkt hex = ${initPkt.take(48).joinToString("") { "%02x".format(it) }}...")

        val buf = ByteArray(BUF_SIZE)

        while (System.currentTimeMillis() - startMs < HANDSHAKE_TIMEOUT_MS) {
            if (stopFlag.get()) return@withContext "Stopped during handshake"

            try {
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val data = buf.copyOf(pkt.length)

                if (crypto.processServerHello(data)) {
                    Log.d(TAG, "Handshake: PFS ratchet complete")
                    return@withContext null // success
                }
                // Not a ServerHello — ignore (could be stale packet)
            } catch (_: SocketTimeoutException) {
                // Retry
                initPkt = crypto.buildInitPacket()
                socket.send(DatagramPacket(initPkt, initPkt.size))
                Log.d(TAG, "Handshake: retry (${System.currentTimeMillis() - startMs}ms elapsed)")
            }
        }

        return@withContext "Handshake timeout (${HANDSHAKE_TIMEOUT_MS / 1000} s)"
    }

    // ──────────── Main forwarding loop ────────────

    private suspend fun runForwardingLoop(
        crypto: AivpnCrypto,
        tunFd: FileDescriptor
    ): String = coroutineScope {
        val tunChannel = Channel<ByteArray>(2048)
        val lastRxTime = AtomicLong(System.currentTimeMillis())
        val uploadAtLastRx = AtomicLong(0L)
        val keepaliveSent = AtomicLong(0L)
        val rebindCount = AtomicLong(0L)
        val tunStart = System.currentTimeMillis()

        // TUN reader
        val tunReaderJob = launch(Dispatchers.IO) {
            val buf = ByteArray(BUF_SIZE)
            Log.d(TAG, "TUN reader started")
            try {
                while (isActive && !stopFlag.get()) {
                    val n = Os.read(tunFd, buf, 0, buf.size)
                    if (n <= 0) continue
                    if (buf[0].toInt().ushr(4) != 4) continue
                    tunChannel.send(buf.copyOf(n))
                }
                Log.w(TAG, "TUN reader loop exited: active=$isActive stop=${stopFlag.get()}")
            } catch (e: Exception) {
                Log.e(TAG, "TUN reader error after ${(System.currentTimeMillis() - tunStart) / 1000}s: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Encrypt + UDP send + keepalive. Reads current socket from udpSocketRef each
        // iteration — tolerates soft-roam (socket swap) via SocketException retry.
        val senderJob = launch(Dispatchers.IO) {
            Log.d(TAG, "Sender started")
            while (isActive && !stopFlag.get()) {
                val sock = udpSocketRef.get()
                if (sock == null) {
                    Log.w(TAG, "Sender: no socket, exiting")
                    break
                }
                val ipPacket = withTimeoutOrNull(KEEPALIVE_INTERVAL_MS) {
                    tunChannel.receive()
                }
                try {
                    if (ipPacket != null) {
                        val encrypted = crypto.encryptDataPacket(ipPacket)
                        sock.send(DatagramPacket(encrypted, encrypted.size))
                        uploadBytes.addAndGet(ipPacket.size.toLong())

                        var burst = 0
                        while (burst < 256) {
                            val next = tunChannel.tryReceive().getOrNull() ?: break
                            val enc = crypto.encryptDataPacket(next)
                            val curSock = udpSocketRef.get() ?: break
                            curSock.send(DatagramPacket(enc, enc.size))
                            uploadBytes.addAndGet(next.size.toLong())
                            burst++
                        }
                    } else {
                        val ka = crypto.buildKeepalivePacket()
                        sock.send(DatagramPacket(ka, ka.size))
                        val kaCount = keepaliveSent.incrementAndGet()
                        if (kaCount % 4 == 0L) {
                            val upMs = System.currentTimeMillis() - tunStart
                            Log.d(TAG, "Keepalive #$kaCount (uptime ${upMs / 1000}s, up=${uploadBytes.get()}B down=${downloadBytes.get()}B)")
                        }
                    }
                } catch (e: SocketException) {
                    if (stopFlag.get()) break
                    val current = udpSocketRef.get()
                    if (current != null && current !== sock) {
                        rebindCount.incrementAndGet()
                        Log.d(TAG, "Sender: socket rebound (#${rebindCount.get()}) — continuing without rehandshake")
                        continue
                    }
                    Log.e(TAG, "Sender SocketException after ${(System.currentTimeMillis() - tunStart) / 1000}s: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Sender error after ${(System.currentTimeMillis() - tunStart) / 1000}s: ${e.javaClass.simpleName}: ${e.message}", e)
                    break
                }
            }
            Log.w(TAG, "Sender loop exited: active=$isActive stop=${stopFlag.get()}")
        }

        // UDP recv → decrypt → TUN write. Soft-roam tolerant (reads socket each iteration).
        val receiverJob = launch(Dispatchers.IO) {
            val buf = ByteArray(BUF_SIZE)
            val pkt = DatagramPacket(buf, buf.size)
            var rxOk = 0L
            var rxDrop = 0L
            Log.d(TAG, "Receiver started")
            while (isActive && !stopFlag.get()) {
                val sock = udpSocketRef.get()
                if (sock == null) {
                    Log.w(TAG, "Receiver: no socket, exiting")
                    break
                }
                try {
                    pkt.setData(buf, 0, buf.size)
                    sock.receive(pkt)
                    lastRxTime.set(System.currentTimeMillis())
                    uploadAtLastRx.set(uploadBytes.get())

                    val raw = buf.copyOf(pkt.length)
                    val ipPacket = crypto.decryptDataPacket(raw)
                    if (ipPacket != null && ipPacket.isNotEmpty()) {
                        Os.write(tunFd, ipPacket, 0, ipPacket.size)
                        downloadBytes.addAndGet(ipPacket.size.toLong())
                        rxOk++
                    } else {
                        rxDrop++
                        if (rxDrop % 50 == 1L) Log.w(TAG, "Decrypt failed: rxOk=$rxOk rxDrop=$rxDrop (${pkt.length} bytes)")
                    }
                } catch (e: SocketException) {
                    if (stopFlag.get()) break
                    val current = udpSocketRef.get()
                    if (current != null && current !== sock) {
                        Log.d(TAG, "Receiver: socket rebound — continuing without rehandshake (rxOk=$rxOk rxDrop=$rxDrop)")
                        continue
                    }
                    Log.e(TAG, "Receiver SocketException after ${(System.currentTimeMillis() - tunStart) / 1000}s: ${e.message} (rxOk=$rxOk rxDrop=$rxDrop)")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Receiver error after ${(System.currentTimeMillis() - tunStart) / 1000}s: ${e.javaClass.simpleName}: ${e.message} (rxOk=$rxOk rxDrop=$rxDrop)", e)
                    break
                }
            }
            Log.w(TAG, "Receiver loop exited: active=$isActive stop=${stopFlag.get()} rxOk=$rxOk rxDrop=$rxDrop")
        }

        // Health monitor + rekey + periodic stats dump
        val healthJob = launch {
            val rekeyDeadline = System.currentTimeMillis() + REKEY_INTERVAL_MS
            var lastStatsMs = System.currentTimeMillis()
            var lastUpBytes = 0L
            var lastDownBytes = 0L
            Log.d(TAG, "Health monitor started")
            while (isActive && !stopFlag.get()) {
                delay(RX_CHECK_INTERVAL_MS)
                if (stopFlag.get()) break
                if (System.currentTimeMillis() >= rekeyDeadline) {
                    Log.d(TAG, "Rekey interval — reconnect")
                    break
                }
                val silenceMs = System.currentTimeMillis() - lastRxTime.get()
                val uploadedSinceRx = uploadBytes.get() - uploadAtLastRx.get()
                val upSec = (System.currentTimeMillis() - tunStart) / 1000

                // Stats dump every 10 seconds
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastStatsMs >= 10_000L) {
                    val upDelta = uploadBytes.get() - lastUpBytes
                    val downDelta = downloadBytes.get() - lastDownBytes
                    val gapMs = nowMs - lastStatsMs
                    Log.d(TAG, "STATS t=${upSec}s: up=${upDelta}B (${upDelta * 1000 / gapMs}B/s) down=${downDelta}B (${downDelta * 1000 / gapMs}B/s) rxSilence=${silenceMs}ms")
                    lastStatsMs = nowMs
                    lastUpBytes = uploadBytes.get()
                    lastDownBytes = downloadBytes.get()
                }

                if (silenceMs > TX_WITHOUT_RX_TIMEOUT_MS && uploadedSinceRx >= TX_WITHOUT_RX_MIN_BYTES) {
                    Log.w(TAG, "TX without RX after ${upSec}s: ${uploadedSinceRx}B in ${silenceMs}ms")
                    break
                }
                if (silenceMs > RX_SILENCE_MS) {
                    Log.w(TAG, "No RX for ${silenceMs}ms after ${upSec}s uptime")
                    break
                }
            }
        }

        val exitReason = select<String> {
            tunReaderJob.onJoin { "tunReader" }
            senderJob.onJoin { "sender" }
            receiverJob.onJoin { "receiver" }
            healthJob.onJoin { "health" }
        }
        val uptimeSec = (System.currentTimeMillis() - tunStart) / 1000
        Log.w(TAG, "Tunnel exit after ${uptimeSec}s: $exitReason finished first (keepalives=${keepaliveSent.get()}, rebinds=${rebindCount.get()}, up=${uploadBytes.get()}B down=${downloadBytes.get()}B)")

        tunReaderJob.cancel()
        senderJob.cancel()
        receiverJob.cancel()
        healthJob.cancel()

        return@coroutineScope if (stopFlag.get()) "Tunnel stopped by request" else ""
    }

    // ──────────── Helpers ────────────

    private fun makeTunFileDescriptor(fd: Int): FileDescriptor {
        val fileDescriptor = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(fileDescriptor, fd)

        // Ensure blocking mode (Android TUN defaults to non-blocking)
        val flags = Os.fcntlInt(fileDescriptor, android.system.OsConstants.F_GETFL, 0)
        Os.fcntlInt(fileDescriptor, android.system.OsConstants.F_SETFL,
            flags and android.system.OsConstants.O_NONBLOCK.inv())
        Log.d(TAG, "TUN fd=$fd set to blocking mode")

        return fileDescriptor
    }
}

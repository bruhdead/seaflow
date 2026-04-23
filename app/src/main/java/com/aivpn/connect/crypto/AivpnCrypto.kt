package com.aivpn.connect.crypto

import java.math.BigInteger  // used by rawToX25519PublicKey
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AIVPN protocol crypto engine (pure Kotlin, no JNI).
 *
 * Implements a simplified version of the AIVPN wire protocol:
 *   - X25519 key exchange (via platform KeyAgreement or bundled Curve25519)
 *   - ChaCha20-Poly1305 AEAD encryption
 *   - Resonance tag generation for session identification
 *   - PFS key ratchet on ServerHello
 *
 * Wire format: TAG(8) | MDH(4) | encrypt(pad_len_u16 || inner_payload || padding)
 */
class AivpnCrypto(private val serverStaticPub: ByteArray, private val psk: ByteArray? = null) {

    companion object {
        const val TAG_SIZE = 8
        const val MDH_SIZE = 4
        const val NONCE_SIZE = 12
        const val KEY_SIZE = 32
        const val INNER_HEADER_SIZE = 4
    }

    private val rng = SecureRandom()
    private val fastRng = java.util.Random()  // Non-crypto PRNG for padding only

    // Client ephemeral keypair (X25519 via platform API)
    private val clientKeyPair: java.security.KeyPair
    private val clientPublic: ByteArray

    // Session keys — volatile for safe reads across lock boundaries
    @Volatile private var sessionKey = ByteArray(KEY_SIZE)
    @Volatile private var tagSecret = ByteArray(KEY_SIZE)

    // Send-only state (guarded by sendLock)
    private val sendLock = Any()
    private var sendCounter: Long = 0
    private var sendSeq: Int = 0

    // Recv-only state (guarded by recvLock)
    private val recvLock = Any()
    private var recvHighest: Long = -1L
    private var recvWindow: Long = 0L

    // Pooled cipher instances per thread
    private val encryptCipher: ThreadLocal<Cipher> = ThreadLocal.withInitial {
        Cipher.getInstance("ChaCha20-Poly1305")
    }
    private val decryptCipher: ThreadLocal<Cipher> = ThreadLocal.withInitial {
        Cipher.getInstance("ChaCha20-Poly1305")
    }

    // Grace period for PFS ratchet
    private var oldSessionKey: ByteArray? = null
    private var oldTagSecret: ByteArray? = null
    private var oldRecvHighest: Long = -1L
    private var oldRecvWindow: Long = 0L
    private var graceDeadlineMs: Long = 0L

    // Throttle expensive wide forward-recovery search (4096 tag computations) —
    // only run it at most once per 500ms to prevent CPU stalls during packet-loss bursts.
    @Volatile private var lastWideSearchMs: Long = 0L

    init {
        // Generate ephemeral X25519 keypair using platform API
        val kpg = KeyPairGenerator.getInstance("X25519")
        clientKeyPair = kpg.generateKeyPair()

        // Extract raw 32-byte public key
        clientPublic = extractRawPublicKey(clientKeyPair.public)

        // DH1 = clientPrivate * serverStaticPub → initial session keys
        val sharedSecret = x25519DH(clientKeyPair.private, serverStaticPub)
        android.util.Log.d("AivpnCrypto", "clientPub=${clientPublic.joinToString("") { "%02x".format(it) }}")
        android.util.Log.d("AivpnCrypto", "serverPub=${serverStaticPub.joinToString("") { "%02x".format(it) }}")
        android.util.Log.d("AivpnCrypto", "DH shared=${sharedSecret.joinToString("") { "%02x".format(it) }}")
        deriveKeys(sharedSecret, clientPublic, psk)
        android.util.Log.d("AivpnCrypto", "sessionKey=${sessionKey.joinToString("") { "%02x".format(it) }}")
        android.util.Log.d("AivpnCrypto", "tagSecret=${tagSecret.joinToString("") { "%02x".format(it) }}")
    }

    /**
     * Build the initial handshake packet with obfuscated eph_pub.
     */
    fun buildInitPacket(): ByteArray = synchronized(sendLock) {
        // Obfuscate eph_pub by XORing with BLAKE3-derived mask (matches Rust obfuscate_eph_pub)
        val mask = Blake3.deriveKey("aivpn-eph-obfuscation-v1", serverStaticPub)
        val obfEphPub = ByteArray(32)
        for (i in 0 until 32) {
            obfEphPub[i] = (clientPublic[i].toInt() xor mask[i].toInt()).toByte()
        }

        // Inner payload: Control Keepalive
        val innerHeader = buildInnerHeader(0x02, sendSeq++) // 0x02 = Control
        val controlPayload = byteArrayOf(0x03) // Keepalive subtype
        val innerPayload = innerHeader + controlPayload

        // Build AIVPN packet with eph_pub included.
        // Use large padding (>120 bytes) so the total packet exceeds 160 bytes.
        // This bypasses the server's duplicate-endpoint filter which drops
        // small init packets (<=160B) from an IP that already has a session.
        return buildPacket(innerPayload, obfEphPub, minPadding = 120)
    }

    /**
     * Build a post-handshake keepalive control packet.
     */
    fun buildKeepalivePacket(): ByteArray = synchronized(sendLock) {
        val innerHeader = buildInnerHeader(0x02, sendSeq++) // 0x02 = Control
        val controlPayload = byteArrayOf(0x03) // Keepalive subtype
        val innerPayload = innerHeader + controlPayload
        return@synchronized buildPacket(innerPayload, null)
    }

    /**
     * Process ServerHello — complete the PFS ratchet.
     * Returns true if the ratchet succeeded.
     */
    fun processServerHello(packet: ByteArray): Boolean = synchronized(sendLock) { synchronized(recvLock) {
        return try {
            // Validate tag (try range of counters)
            val tag = packet.copyOfRange(0, TAG_SIZE)
            var validCounter: Long? = null
            val timeWindow = System.currentTimeMillis() / 10_000L  // Optimized: 10s window (was 5s)

            for (offset in longArrayOf(0, -1, 1)) {
                val tw = timeWindow + offset
                val searchStart = if (recvHighest < 0L) 0L else maxOf(0L, recvHighest - 63L)
                val searchEnd = maxOf(256L, recvHighest + 257L)
                for (c in searchStart until searchEnd) {
                    if (!isCounterNew(c)) continue
                    val expected = generateTag(tagSecret, c, tw)
                    if (expected.contentEquals(tag)) {
                        validCounter = c
                        break
                    }
                }
                if (validCounter != null) break
            }

            if (validCounter == null) return false

            // Decrypt payload
            val ciphertext = packet.copyOfRange(TAG_SIZE + MDH_SIZE, packet.size)
            val nonce = counterToNonce(validCounter)
            val plaintext = decrypt(sessionKey, nonce, ciphertext) ?: return false

            // Strip padding
            if (plaintext.size < 2) return false
            val padLen = (plaintext[0].toInt() and 0xFF) or ((plaintext[1].toInt() and 0xFF) shl 8)
            val inner = plaintext.copyOfRange(2, plaintext.size - padLen)

            // Parse inner header
            if (inner.size < INNER_HEADER_SIZE) return false
            val innerType = inner[0].toInt() and 0xFF
            if (innerType != 0x02) return false // Must be Control

            val controlData = inner.copyOfRange(INNER_HEADER_SIZE, inner.size)
            if (controlData.isEmpty()) return false
            val subtype = controlData[0].toInt() and 0xFF
            if (subtype != 0x09) return false // ServerHello subtype

            // Extract server_eph_pub (32 bytes) + signature (64 bytes)
            if (controlData.size < 1 + 32) return false
            val serverEphPub = controlData.copyOfRange(1, 33)

            // Compute DH2 = clientPrivate * serverEphPub
            val dh2 = x25519DH(clientKeyPair.private, serverEphPub)

            // Save old keys for grace period (2s) so in-flight packets can be decrypted
            oldSessionKey = sessionKey.copyOf()
            oldTagSecret = tagSecret.copyOf()
            oldRecvHighest = recvHighest
            oldRecvWindow = recvWindow
            graceDeadlineMs = System.currentTimeMillis() + 2000L

            // Ratchet keys: derive new keys using DH2 + current session key as PSK
            deriveKeys(dh2, clientPublic, sessionKey)

            // Reset counters for the new epoch
            sendCounter = 0
            recvHighest = -1L
            recvWindow = 0L

            true
        } catch (e: Exception) {
            false
        }
    }}

    /**
     * Encrypt an outbound IP packet into the AIVPN wire format.
     */
    fun encryptDataPacket(ipPacket: ByteArray): ByteArray = synchronized(sendLock) {
        val innerHeader = buildInnerHeader(0x01, sendSeq++)
        val innerPayload = innerHeader + ipPacket
        return@synchronized buildPacket(innerPayload, null)
    }

    /**
     * Decrypt an inbound AIVPN packet and return the inner IP packet, or null.
     */
    fun decryptDataPacket(packet: ByteArray): ByteArray? = synchronized(recvLock) {
        if (packet.size < TAG_SIZE + MDH_SIZE + 16) return null

        // Try current keys first
        val result = tryDecryptPacket(packet, sessionKey, tagSecret, recvHighest, recvWindow, true)
        if (result != null) return result

        // Fallback to old keys during grace period
        val oldKey = oldSessionKey
        val oldTag = oldTagSecret
        if (oldKey != null && oldTag != null && System.currentTimeMillis() < graceDeadlineMs) {
            return tryDecryptPacket(packet, oldKey, oldTag, oldRecvHighest, oldRecvWindow, false)
        }

        return null
    }

    private fun tryDecryptPacket(
        packet: ByteArray, key: ByteArray, tagSec: ByteArray,
        highest: Long, window: Long, updateWindow: Boolean
    ): ByteArray? {
        val tag = packet.copyOfRange(0, TAG_SIZE)
        var validCounter: Long? = null
        val timeWindow = System.currentTimeMillis() / 10_000L

        // Phase 1: narrow fast-path search around the known window
        val narrowStart = if (highest < 0L) 0L else maxOf(0L, highest - 63L)
        val narrowEnd = maxOf(256L, highest + 257L)
        for (offset in longArrayOf(0, -1, 1)) {
            val tw = timeWindow + offset
            for (c in narrowStart until narrowEnd) {
                val expected = generateTag(tagSec, c, tw)
                if (expected.contentEquals(tag)) {
                    validCounter = c
                    break
                }
            }
            if (validCounter != null) break
        }

        // Phase 2: wide forward-recovery search (THROTTLED) for catch-up after
        // packet-loss bursts. This is CPU-expensive (~12k BLAKE3 computations),
        // so we only run it once per 500ms when narrow search fails. Old/replay
        // decryption path (updateWindow=false) never runs wide search.
        if (validCounter == null && highest >= 0L && updateWindow) {
            val now = System.currentTimeMillis()
            if (now - lastWideSearchMs >= 500L) {
                lastWideSearchMs = now
                val wideStart = System.nanoTime()
                val wideEnd = highest + 4097L
                for (offset in longArrayOf(0, -1, 1)) {
                    val tw = timeWindow + offset
                    for (c in narrowEnd until wideEnd) {
                        val expected = generateTag(tagSec, c, tw)
                        if (expected.contentEquals(tag)) {
                            validCounter = c
                            break
                        }
                    }
                    if (validCounter != null) break
                }
                val wideMs = (System.nanoTime() - wideStart) / 1_000_000
                if (validCounter != null) {
                    android.util.Log.w("AivpnCrypto",
                        "Forward recovery: counter $highest → $validCounter (gap ${validCounter!! - highest}, scan ${wideMs}ms)")
                } else if (wideMs > 50) {
                    android.util.Log.d("AivpnCrypto", "Wide search miss (${wideMs}ms)")
                }
            }
        }

        if (validCounter == null) return null

        val ciphertext = packet.copyOfRange(TAG_SIZE + MDH_SIZE, packet.size)
        val nonce = counterToNonce(validCounter)
        val plaintext = decrypt(key, nonce, ciphertext) ?: return null

        if (plaintext.size < 2) return null
        val padLen = (plaintext[0].toInt() and 0xFF) or ((plaintext[1].toInt() and 0xFF) shl 8)
        if (2 + padLen > plaintext.size) return null
        val inner = plaintext.copyOfRange(2, plaintext.size - padLen)

        if (inner.size < INNER_HEADER_SIZE) return null
        val innerType = inner[0].toInt() and 0xFF

        if (updateWindow) markCounter(validCounter)

        return when (innerType) {
            0x01 -> inner.copyOfRange(INNER_HEADER_SIZE, inner.size)
            else -> null
        }
    }

    // ──────────── Sliding-window anti-replay ────────────

    /** Returns true if counter c has not yet been seen and is within the window. */
    private fun isCounterNew(c: Long): Boolean {
        if (c > recvHighest) return true
        val diff = recvHighest - c
        if (diff >= 64L) return false
        return (recvWindow ushr diff.toInt()) and 1L == 0L
    }

    /** Marks counter c as received in the sliding window. */
    private fun markCounter(c: Long) {
        if (c > recvHighest) {
            val shift = c - recvHighest
            recvWindow = if (shift >= 64L) 0L else recvWindow shl shift.toInt()
            recvWindow = recvWindow or 1L
            recvHighest = c
        } else {
            val diff = (recvHighest - c).toInt()
            if (diff < 64) recvWindow = recvWindow or (1L shl diff)
        }
    }

    // ──────────── Internal helpers ────────────

    private fun buildPacket(innerPayload: ByteArray, ephPub: ByteArray?, minPadding: Int = 8): ByteArray {
        // Padding — use fast PRNG (padding is not security-critical, just noise)
        val padLen = minPadding + fastRng.nextInt(16)
        val padding = ByteArray(padLen).also { fastRng.nextBytes(it) }

        // Padded plaintext: pad_len(u16 LE) || inner_payload || random_padding
        val padded = ByteBuffer.allocate(2 + innerPayload.size + padLen)
        padded.put((padLen and 0xFF).toByte())
        padded.put(((padLen shr 8) and 0xFF).toByte())
        padded.put(innerPayload)
        padded.put(padding)
        val paddedBytes = padded.array()

        // Encrypt
        val counter = sendCounter++
        val nonce = counterToNonce(counter)
        val ciphertext = encrypt(sessionKey, nonce, paddedBytes)

        // Generate resonance tag
        val timeWindow = System.currentTimeMillis() / 10_000L  // Optimized: 10s window (was 5s)
        val tag = generateTag(tagSecret, counter, timeWindow)

        // MDH (4 zero bytes for MVP)
        val mdh = ByteArray(MDH_SIZE)

        // Assemble: TAG | MDH | [eph_pub] | ciphertext
        val totalSize = TAG_SIZE + MDH_SIZE + (ephPub?.size ?: 0) + ciphertext.size
        val result = ByteBuffer.allocate(totalSize)
        result.put(tag)
        result.put(mdh)
        if (ephPub != null) result.put(ephPub)
        result.put(ciphertext)

        return result.array()
    }

    private fun buildInnerHeader(type: Int, seq: Int): ByteArray {
        return byteArrayOf(
            type.toByte(),
            0x00, // reserved
            (seq and 0xFF).toByte(),
            ((seq shr 8) and 0xFF).toByte()
        )
    }

    private fun counterToNonce(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        for (i in 0 until 8) {
            nonce[i] = ((counter shr (i * 8)) and 0xFF).toByte()
        }
        return nonce
    }

    private fun generateTag(secret: ByteArray, counter: Long, timeWindow: Long): ByteArray {
        // BLAKE3 keyed hash: matches Rust generate_resonance_tag
        val counterBytes = ByteArray(8)
        val windowBytes = ByteArray(8)
        for (i in 0 until 8) {
            counterBytes[i] = ((counter shr (i * 8)) and 0xFF).toByte()
            windowBytes[i] = ((timeWindow shr (i * 8)) and 0xFF).toByte()
        }
        val data = counterBytes + windowBytes
        return Blake3.keyedHash(secret, data).copyOf(TAG_SIZE)
    }

    private fun deriveKeys(sharedSecret: ByteArray, clientPub: ByteArray, psk: ByteArray? = null) {
        // Matches Rust derive_session_keys: BLAKE3 derive_key with context strings
        val ikm = if (psk != null) sharedSecret + psk else sharedSecret
        val input = ikm + clientPub
        sessionKey = Blake3.deriveKey("aivpn-session-key-v1", input)
        tagSecret = Blake3.deriveKey("aivpn-tag-secret-v1", input)
    }

    // ──────────── ChaCha20-Poly1305 via Android API ────────────

    private fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = encryptCipher.get()!!
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        return try {
            val cipher = decryptCipher.get()!!
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val ivSpec = IvParameterSpec(nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    // ──────────── X25519 via platform KeyAgreement (Android 33+) ────────────

    /**
     * Perform X25519 DH: ourPrivate * theirPublicRaw → 32-byte shared secret.
     */
    private fun x25519DH(privateKey: java.security.PrivateKey, theirPublicRaw: ByteArray): ByteArray {
        val pubKey = rawToX25519PublicKey(theirPublicRaw)
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privateKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    /**
     * Convert raw 32-byte X25519 public key to java.security.PublicKey.
     * Uses X.509 DER encoding for maximum provider compatibility (Conscrypt + JCA).
     */
    private fun rawToX25519PublicKey(raw: ByteArray): java.security.PublicKey {
        // X25519 SubjectPublicKeyInfo DER: 30 2a 30 05 06 03 2b 65 6e 03 21 00 <32 bytes>
        val derPrefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
        )
        val encoded = derPrefix + raw
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        return KeyFactory.getInstance("X25519").generatePublic(spec)
    }

    /**
     * Extract raw 32-byte public key from java.security.PublicKey.
     * Handles both standard XECPublicKey and Conscrypt's OpenSSLX25519PublicKey.
     */
    private fun extractRawPublicKey(publicKey: java.security.PublicKey): ByteArray {
        // Try XECPublicKey (standard JCA, available on some devices)
        if (publicKey is java.security.interfaces.XECPublicKey) {
            val u = publicKey.u
            val be = u.toByteArray()
            val out = ByteArray(32)
            val len = minOf(be.size, 32)
            for (i in 0 until len) out[i] = be[be.size - 1 - i]
            return out
        }

        // Fallback: extract from X.509 DER encoding
        // X25519 SubjectPublicKeyInfo: 30 2a 30 05 06 03 2b 65 6e 03 21 00 <32 bytes>
        // The raw key is the last 32 bytes of the 44-byte encoded form.
        val encoded = publicKey.encoded
        if (encoded != null && encoded.size == 44) {
            return encoded.copyOfRange(12, 44)
        }

        // Last resort: try getEncoded reflection for Conscrypt
        try {
            val method = publicKey.javaClass.getMethod("getU")
            val raw = method.invoke(publicKey) as ByteArray
            return raw.copyOf(32)
        } catch (_: Exception) {}

        throw IllegalArgumentException(
            "Cannot extract raw X25519 key from ${publicKey.javaClass.name}")
    }
}

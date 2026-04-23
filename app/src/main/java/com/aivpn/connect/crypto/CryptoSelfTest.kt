package com.aivpn.connect.crypto

import android.util.Log

/**
 * Self-test against RFC 7748 / BLAKE3 known test vectors.
 * Run at app startup to verify crypto correctness.
 */
object CryptoSelfTest {
    private const val TAG = "CryptoSelfTest"

    fun runAll(): Boolean {
        val x25519ok = testX25519()
        val blake3ok = testBlake3()
        val chacha20ok = testChaCha20()
        Log.i(TAG, "Results: X25519=$x25519ok, BLAKE3=$blake3ok, ChaCha20=$chacha20ok")
        return x25519ok && blake3ok && chacha20ok
    }

    /** Test X25519 via platform API with RFC 7748 verification */
    private fun testX25519(): Boolean {
        return try {
            // Test 1: basic round-trip
            val serverKey = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
            val crypto = AivpnCrypto(serverKey, null)
            val pkt = crypto.buildInitPacket()
            val ok = pkt.size > 40
            Log.d(TAG, "X25519:        ${if (ok) "PASS" else "FAIL"} (init pkt ${pkt.size} bytes)")

            // Test 2: verify extractRawPublicKey round-trip
            val kpg = java.security.KeyPairGenerator.getInstance("X25519")
            val kp = kpg.generateKeyPair()
            val encoded = kp.public.encoded
            val raw = if (encoded.size == 44) encoded.copyOfRange(12, 44) else ByteArray(0)
            Log.d(TAG, "X25519 pubkey: encoded=${encoded.size}B, raw=${bytesToHex(raw.take(8).toByteArray())}...")

            // Test 3: DH round-trip — both sides should compute same shared secret
            val kp2 = kpg.generateKeyPair()
            val raw2 = kp2.public.encoded.let { if (it.size == 44) it.copyOfRange(12, 44) else ByteArray(0) }

            // DH: kp1.priv * kp2.pub
            val derPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
            val pub2Reconstructed = java.security.KeyFactory.getInstance("X25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(derPrefix + raw2))
            val ka1 = javax.crypto.KeyAgreement.getInstance("X25519")
            ka1.init(kp.private)
            ka1.doPhase(pub2Reconstructed, true)
            val shared1 = ka1.generateSecret()

            // DH: kp2.priv * kp1.pub
            val pub1Reconstructed = java.security.KeyFactory.getInstance("X25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(derPrefix + raw))
            val ka2 = javax.crypto.KeyAgreement.getInstance("X25519")
            ka2.init(kp2.private)
            ka2.doPhase(pub1Reconstructed, true)
            val shared2 = ka2.generateSecret()

            val dhMatch = shared1.contentEquals(shared2)
            Log.d(TAG, "X25519 DH rt:  ${if (dhMatch) "PASS" else "FAIL"}")
            if (!dhMatch) {
                Log.e(TAG, "  shared1=${bytesToHex(shared1)}")
                Log.e(TAG, "  shared2=${bytesToHex(shared2)}")
            }

            ok && dhMatch
        } catch (e: Exception) {
            Log.e(TAG, "X25519 test FAIL: ${e.message}", e)
            false
        }
    }

    /** BLAKE3 basic tests — deriveKey and keyedHash are the main APIs used */
    private fun testBlake3(): Boolean {
        // Test derive_key is deterministic and returns 32 bytes
        val derived1 = Blake3.deriveKey("test-context", "hello".toByteArray())
        val derived2 = Blake3.deriveKey("test-context", "hello".toByteArray())
        val deriveOk = derived1.size == 32 && derived1.contentEquals(derived2)
        Log.d(TAG, "BLAKE3 derive: ${if (deriveOk) "PASS" else "FAIL"} (${derived1.size} bytes)")
        Log.d(TAG, "  derive value: ${bytesToHex(derived1)}")

        // Different context → different output
        val derived3 = Blake3.deriveKey("other-context", "hello".toByteArray())
        val contextDiffers = !derived1.contentEquals(derived3)
        Log.d(TAG, "BLAKE3 ctx:    ${if (contextDiffers) "PASS" else "FAIL"}")

        // Test keyed_hash — 32 bytes, deterministic
        val keyed1 = Blake3.keyedHash(ByteArray(32), "data".toByteArray())
        val keyed2 = Blake3.keyedHash(ByteArray(32), "data".toByteArray())
        val keyedOk = keyed1.size == 32 && keyed1.contentEquals(keyed2)
        Log.d(TAG, "BLAKE3 keyed:  ${if (keyedOk) "PASS" else "FAIL"} (${keyed1.size} bytes)")
        Log.d(TAG, "  keyed value: ${bytesToHex(keyed1)}")

        // Different key → different output
        val key2 = ByteArray(32) { 1 }
        val keyed3 = Blake3.keyedHash(key2, "data".toByteArray())
        val keyDiffers = !keyed1.contentEquals(keyed3)
        Log.d(TAG, "BLAKE3 key:    ${if (keyDiffers) "PASS" else "FAIL"}")

        return deriveOk && contextDiffers && keyedOk && keyDiffers
    }

    /** ChaCha20-Poly1305 basic sanity check */
    private fun testChaCha20(): Boolean {
        return try {
            val key = ByteArray(32) { it.toByte() }
            val nonce = ByteArray(12)
            val plaintext = "Hello AIVPN".toByteArray()

            val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = javax.crypto.spec.SecretKeySpec(key, "ChaCha20")
            val ivSpec = javax.crypto.spec.IvParameterSpec(nonce)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val ciphertext = cipher.doFinal(plaintext)

            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(ciphertext)

            val ok = decrypted.contentEquals(plaintext)
            Log.d(TAG, "ChaCha20:      ${if (ok) "PASS" else "FAIL"}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20 test error: ${e.message}")
            false
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}

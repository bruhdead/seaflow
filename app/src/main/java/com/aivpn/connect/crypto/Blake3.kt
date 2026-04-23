package com.aivpn.connect.crypto

/**
 * Pure-Kotlin BLAKE3 implementation (single-chunk, ≤1024 bytes input).
 *
 * Supports:
 *  - deriveKey(context, material) – key derivation mode
 *  - keyedHash(key, data)         – keyed hash mode
 *
 * This minimal implementation handles only inputs that fit in a single BLAKE3 chunk
 * (up to 1024 bytes), which is sufficient for the AIVPN protocol.
 */
object Blake3 {

    private val IV = intArrayOf(
        0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt()
    )

    private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val ROOT = 8
    private const val KEYED_HASH = 16
    private const val DERIVE_KEY_CONTEXT = 32
    private const val DERIVE_KEY_MATERIAL = 64
    private const val BLOCK_LEN = 64

    /**
     * BLAKE3 derive_key: derives a 32-byte key from a context string and key material.
     * Matches Rust blake3::derive_key(context, material).
     */
    fun deriveKey(context: String, material: ByteArray): ByteArray {
        // Step 1: Hash context string with DERIVE_KEY_CONTEXT flag → context key
        val contextOutput = hashChunk(IV, context.toByteArray(Charsets.UTF_8), DERIVE_KEY_CONTEXT)
        val contextKey = IntArray(8) { contextOutput[it] }

        // Step 2: Hash key material with context key and DERIVE_KEY_MATERIAL flag
        val output = hashChunk(contextKey, material, DERIVE_KEY_MATERIAL)
        return wordsToBytes(output, 8)
    }

    /**
     * BLAKE3 keyed hash: computes a 32-byte keyed hash.
     * Matches Rust Hasher::new_keyed(key).update(data).finalize().
     */
    fun keyedHash(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        val keyWords = bytesToWords8(key)
        val output = hashChunk(keyWords, data, KEYED_HASH)
        return wordsToBytes(output, 8)
    }

    /**
     * Process a single BLAKE3 chunk (up to 1024 bytes) with given chaining value and mode flags.
     * Returns the 16-word compression output of the last (root) block.
     */
    private fun hashChunk(cv: IntArray, data: ByteArray, modeFlags: Int): IntArray {
        val numBlocks = maxOf(1, (data.size + BLOCK_LEN - 1) / BLOCK_LEN)
        var chainingValue = cv.copyOf()
        var lastOutput = IntArray(16)

        for (i in 0 until numBlocks) {
            val start = i * BLOCK_LEN
            val end = minOf(start + BLOCK_LEN, data.size)
            val blockLen = end - start

            val block = ByteArray(BLOCK_LEN)
            if (blockLen > 0) {
                System.arraycopy(data, start, block, 0, blockLen)
            }

            var flags = modeFlags
            if (i == 0) flags = flags or CHUNK_START
            if (i == numBlocks - 1) flags = flags or CHUNK_END or ROOT

            val blockWords = bytesToWords16(block)
            lastOutput = compress(chainingValue, blockWords, 0L, blockLen, flags)

            if (i < numBlocks - 1) {
                // Chain: first 8 words become the new chaining value
                chainingValue = IntArray(8) { lastOutput[it] }
            }
        }
        return lastOutput
    }

    /**
     * BLAKE3 compression function.
     */
    private fun compress(
        cv: IntArray, blockWords: IntArray,
        counter: Long, blockLen: Int, flags: Int
    ): IntArray {
        val state = intArrayOf(
            cv[0], cv[1], cv[2], cv[3],
            cv[4], cv[5], cv[6], cv[7],
            IV[0], IV[1], IV[2], IV[3],
            counter.toInt(), (counter shr 32).toInt(), blockLen, flags
        )

        val m = blockWords.copyOf()

        // 7 rounds
        for (round in 0 until 7) {
            // Column step
            g(state, 0, 4, 8, 12, m[0], m[1])
            g(state, 1, 5, 9, 13, m[2], m[3])
            g(state, 2, 6, 10, 14, m[4], m[5])
            g(state, 3, 7, 11, 15, m[6], m[7])
            // Diagonal step
            g(state, 0, 5, 10, 15, m[8], m[9])
            g(state, 1, 6, 11, 12, m[10], m[11])
            g(state, 2, 7, 8, 13, m[12], m[13])
            g(state, 3, 4, 9, 14, m[14], m[15])

            // Permute message words for next round
            if (round < 6) {
                val p = IntArray(16) { m[MSG_PERMUTATION[it]] }
                System.arraycopy(p, 0, m, 0, 16)
            }
        }

        // Finalization: XOR upper and lower halves
        for (i in 0 until 8) {
            state[i] = state[i] xor state[i + 8]
            state[i + 8] = state[i + 8] xor cv[i]
        }
        return state
    }

    /**
     * BLAKE3 quarter round (same mixing function as ChaCha).
     */
    private fun g(state: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        state[a] = state[a] + state[b] + mx
        state[d] = ror(state[d] xor state[a], 16)
        state[c] = state[c] + state[d]
        state[b] = ror(state[b] xor state[c], 12)
        state[a] = state[a] + state[b] + my
        state[d] = ror(state[d] xor state[a], 8)
        state[c] = state[c] + state[d]
        state[b] = ror(state[b] xor state[c], 7)
    }

    /** Unsigned right rotation for 32-bit Int. */
    private fun ror(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    // ── Byte ↔ word conversions (little-endian) ──

    private fun bytesToWords8(bytes: ByteArray): IntArray {
        val w = IntArray(8)
        for (i in 0 until 8) {
            val off = i * 4
            w[i] = (bytes[off].toInt() and 0xFF) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 3].toInt() and 0xFF) shl 24)
        }
        return w
    }

    private fun bytesToWords16(bytes: ByteArray): IntArray {
        val w = IntArray(16)
        for (i in 0 until 16) {
            val off = i * 4
            w[i] = (bytes[off].toInt() and 0xFF) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 3].toInt() and 0xFF) shl 24)
        }
        return w
    }

    private fun wordsToBytes(words: IntArray, count: Int): ByteArray {
        val b = ByteArray(count * 4)
        for (i in 0 until count) {
            b[i * 4] = (words[i] and 0xFF).toByte()
            b[i * 4 + 1] = ((words[i] shr 8) and 0xFF).toByte()
            b[i * 4 + 2] = ((words[i] shr 16) and 0xFF).toByte()
            b[i * 4 + 3] = ((words[i] shr 24) and 0xFF).toByte()
        }
        return b
    }
}

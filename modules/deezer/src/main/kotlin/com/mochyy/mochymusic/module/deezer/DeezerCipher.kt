package com.mochyy.mochymusic.module.deezer

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DeezerCipher {

    const val BLOCK_SIZE = 2048
    private const val SECRET = "g4el58wc0zvf9na1"
    private val ivSpec = IvParameterSpec(ByteArray(8) { it.toByte() })
    private val keyCache = ConcurrentHashMap<String, SecretKeySpec>()
    private val md5Digest = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }
    private val charset = Charsets.ISO_8859_1

    private fun bitwiseXor(vararg values: Char): Char =
        values.fold(0) { acc, c -> acc xor c.code }.toChar()

    private fun md5Hex(input: String): String {
        val digest = md5Digest.get()
        digest.reset()
        return digest.digest(input.toByteArray(charset)).joinToString("") { "%02x".format(it) }
    }

    /** generates the 16-character blowfish key for a given track id */
    fun createBlowfishKey(trackId: String): String {
        val hash = md5Hex(trackId)
        return buildString {
            for (i in 0 until 16) {
                append(bitwiseXor(hash[i], hash[i + 16], SECRET[i]))
            }
        }
    }

    private fun getSecretKeySpec(key: String): SecretKeySpec =
        keyCache.computeIfAbsent(key) { SecretKeySpec(key.toByteArray(charset), "Blowfish") }

    /** decrypts a 2048-byte chunk using blowfish cbc with no padding */
    fun decryptBlowfish(chunk: ByteArray, key: String): ByteArray {
        val spec = getSecretKeySpec(key)
        val cipher = Cipher.getInstance("BLOWFISH/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, spec, ivSpec)
        }
        return cipher.doFinal(chunk)
    }

    /** creates a decrypted inputstream handling deezer's stripe encryption and range requests */
    fun createDecryptedStream(
        source: InputStream,
        blowfishKey: String,
        startByte: Long = 0,
        endByte: Long = -1,
        onClose: () -> Unit = {}
    ): InputStream {
        require(startByte >= 0) { "startByte must not be negative" }
        require(endByte == -1L || endByte >= startByte) { "endByte must be >= startByte or -1" }

        val alignedStart = startByte - (startByte % BLOCK_SIZE)
        val initialChunkIndex = alignedStart / BLOCK_SIZE

        val decrypted = StripeDecryptingInputStream(
            source = source,
            key = blowfishKey,
            initialChunkIndex = initialChunkIndex,
            onClose = onClose
        )

        val outputLength = if (endByte < 0) -1L else (endByte - startByte + 1)
        val skip = startByte - alignedStart

        return RangeSlicingInputStream(
            source = decrypted,
            skipBytes = skip,
            outputLength = outputLength
        )
    }

    /** decrypts every 3rd 2048-byte chunk (when chunkIndex % 3 == 0) */
    class StripeDecryptingInputStream(
        private val source: InputStream,
        private val key: String,
        initialChunkIndex: Long,
        private val onClose: () -> Unit = {}
    ) : InputStream() {

        private var chunkIndex = initialChunkIndex
        private val inputBuffer = ByteArray(BLOCK_SIZE)
        private var outputBuffer: ByteArray? = null
        private var outputPosition = 0
        private var outputLimit = 0
        private var eof = false
        private var closed = false

        private fun checkOpen() {
            if (closed) throw IOException("Stream closed")
        }

        private fun loadNextStripe(): Boolean {
            var total = 0
            while (total < BLOCK_SIZE) {
                val count = source.read(inputBuffer, total, BLOCK_SIZE - total)
                if (count < 0) break
                if (count == 0) {
                    val single = source.read()
                    if (single < 0) break
                    inputBuffer[total++] = single.toByte()
                } else {
                    total += count
                }
            }

            if (total == 0) {
                eof = true
                return false
            }

            outputPosition = 0
            outputLimit = total

            if (total == BLOCK_SIZE) {
                outputBuffer = if (chunkIndex % 3L == 0L) {
                    decryptBlowfish(inputBuffer, key)
                } else {
                    inputBuffer
                }
                chunkIndex++
            } else {
                outputBuffer = inputBuffer
                eof = true
            }

            return true
        }

        override fun read(): Int {
            checkOpen()
            if (outputPosition >= outputLimit) {
                if (eof || !loadNextStripe()) return -1
            }
            val buf = outputBuffer ?: return -1
            return buf[outputPosition++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkOpen()
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0

            if (outputPosition >= outputLimit) {
                if (eof || !loadNextStripe()) return -1
            }

            val buf = outputBuffer ?: return -1
            val available = outputLimit - outputPosition
            val toCopy = minOf(len, available)
            System.arraycopy(buf, outputPosition, b, off, toCopy)
            outputPosition += toCopy
            return toCopy
        }

        override fun close() {
            if (!closed) {
                closed = true
                try {
                    source.close()
                } finally {
                    onClose()
                }
            }
        }
    }

    /** trims skipBytes from the start and caps the output stream to outputLength */
    class RangeSlicingInputStream(
        private val source: InputStream,
        private var skipBytes: Long,
        private val outputLength: Long
    ) : InputStream() {

        private var bytesProduced = 0L
        private var closed = false

        private fun ensureSkipped() {
            while (skipBytes > 0) {
                val skipped = source.skip(skipBytes)
                if (skipped <= 0) {
                    if (source.read() == -1) {
                        skipBytes = 0
                        break
                    }
                    skipBytes--
                } else {
                    skipBytes -= skipped
                }
            }
        }

        override fun read(): Int {
            if (closed) throw IOException("Stream closed")
            if (outputLength in 0..bytesProduced) return -1
            ensureSkipped()

            val b = source.read()
            if (b != -1) bytesProduced++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")
            if (outputLength in 0..bytesProduced) return -1
            ensureSkipped()

            val maxToRead = if (outputLength >= 0) {
                val remaining = outputLength - bytesProduced
                minOf(len.toLong(), remaining).toInt()
            } else {
                len
            }

            if (maxToRead <= 0) return -1

            val count = source.read(b, off, maxToRead)
            if (count > 0) bytesProduced += count
            return count
        }

        override fun close() {
            if (!closed) {
                closed = true
                source.close()
            }
        }
    }
}

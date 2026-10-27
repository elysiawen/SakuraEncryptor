package com.sakura.encryptor.core.crypto

/**
 * Parsed `.ske` header, options block and geometry helpers for random access.
 *
 * The header stores salt + master IV so any client can derive the key and
 * seek directly to an arbitrary block; the options block carries the key
 * check and the KDF work factor.
 */
class SkeHeader(
    val raw: ByteArray,
    val version: ByteArray,
    val salt: ByteArray,
    val masterIv: ByteArray,
    /** Truncated HMAC over prefix + options + envelope + body. */
    val shmac: ByteArray,
    /** Options block: key check | KDF iterations | flags. */
    val options: ByteArray,
    val keyCheck: ByteArray,
    val kdfIterations: Int,
    /**
     * Offset of the first body block: header + options + the encrypted
     * thumbnail envelope, which every container carries.
     */
    val bodyOffset: Int = SkeFormat.PROBE_SIZE,
    /** Nonce of the thumbnail envelope. */
    val thumbnailNonce: ByteArray = ByteArray(0),
    /** Ciphertext length of the thumbnail, GCM tag included; 0 when absent. */
    val thumbnailLength: Int = 0,
) {

    /** True when a preview is actually stored (some containers carry none). */
    val hasEmbeddedThumbnail: Boolean get() = thumbnailLength > 0

    /**
     * AAD for every body block: the 39-byte prefix (MAGIC|VERSION|SALT|MASTER_IV)
     * followed by the options block, so neither can be swapped underneath.
     */
    val aad: ByteArray
        get() = ByteArray(SkeFormat.AAD_SIZE).also {
            raw.copyInto(it, 0, 0, SkeFormat.AAD_PREFIX_SIZE)
            options.copyInto(it, SkeFormat.AAD_PREFIX_SIZE)
        }

    companion object {
        /**
         * Parse a header from [bytes].
         *
         * Must be at least [SkeFormat.PROBE_SIZE] long, so the options block
         * and the thumbnail envelope can be decoded.
         *
         * @throws InvalidSkeFileException when magic/version/options/envelope
         *   are invalid, or when the KDF work factor is out of range.
         */
        fun parse(bytes: ByteArray): SkeHeader {
            if (bytes.size < SkeFormat.HEADER_SIZE) throw InvalidSkeFileException("File too small to be a valid .ske")

            val raw = bytes.copyOf(SkeFormat.HEADER_SIZE)
            val magic = raw.copyOfRange(0, SkeFormat.MAGIC_SIZE)
            val version = raw.copyOfRange(SkeFormat.MAGIC_SIZE, SkeFormat.MAGIC_SIZE + SkeFormat.VERSION_SIZE)
            val saltAt = SkeFormat.MAGIC_SIZE + SkeFormat.VERSION_SIZE
            val salt = raw.copyOfRange(saltAt, saltAt + SkeFormat.SALT_SIZE)
            val masterIv = raw.copyOfRange(
                SkeFormat.AAD_PREFIX_SIZE - SkeFormat.IV_SIZE,
                SkeFormat.AAD_PREFIX_SIZE,
            )
            val shmac = raw.copyOfRange(SkeFormat.AAD_PREFIX_SIZE, SkeFormat.HEADER_SIZE)

            if (!magic.contentEquals(SkeFormat.MAGIC)) {
                throw InvalidSkeFileException("Invalid magic number: not a Sakura Encryptor file")
            }
            if (!SkeFormat.isSupportedVersion(version)) {
                throw InvalidSkeFileException("Unsupported .ske version: ${version.toString(Charsets.US_ASCII)}")
            }

            val options = parseOptions(bytes)
            val envelope = parseThumbnailEnvelope(bytes)
            return SkeHeader(
                raw = raw,
                version = version,
                salt = salt,
                masterIv = masterIv,
                shmac = shmac,
                options = options.bytes,
                keyCheck = options.keyCheck,
                kdfIterations = options.iterations,
                bodyOffset = envelope.bodyOffset,
                thumbnailNonce = envelope.nonce,
                thumbnailLength = envelope.length,
            )
        }

        /** Decoded options block. */
        private class Options(
            val bytes: ByteArray,
            val keyCheck: ByteArray,
            val iterations: Int,
        )

        /**
         * Decode the options block that follows the header.
         *
         * @throws InvalidSkeFileException on reserved flags or an out-of-range
         *   work factor (a hostile file must not be able to stall the client).
         */
        private fun parseOptions(bytes: ByteArray): Options {
            val start = SkeFormat.HEADER_SIZE
            if (bytes.size < start + SkeFormat.OPTIONS_SIZE) {
                throw InvalidSkeFileException("Corrupted .ske options")
            }
            val block = bytes.copyOfRange(start, start + SkeFormat.OPTIONS_SIZE)
            val keyCheck = block.copyOfRange(0, SkeFormat.KEY_CHECK_SIZE)
            val flagsAt = SkeFormat.KEY_CHECK_SIZE + SkeFormat.KDF_ITERATIONS_SIZE
            for (i in flagsAt until SkeFormat.OPTIONS_SIZE) {
                if (block[i].toInt() != 0) throw InvalidSkeFileException("Unsupported .ske options")
            }
            var iterations = 0L
            for (i in 0 until SkeFormat.KDF_ITERATIONS_SIZE) {
                iterations = (iterations shl 8) or (block[SkeFormat.KEY_CHECK_SIZE + i].toLong() and 0xFF)
            }
            if (iterations < 1 || iterations > SkeFormat.MAX_KDF_ITERATIONS) {
                throw InvalidSkeFileException("Unsupported KDF iteration count: $iterations")
            }
            return Options(block, keyCheck, iterations.toInt())
        }

        /** Decoded envelope: where the body starts plus the thumbnail geometry. */
        private class ThumbEnvelope(val bodyOffset: Int, val nonce: ByteArray, val length: Int)

        /**
         * Decode the envelope that sits between the options and the body.
         *
         * @throws InvalidSkeFileException when the probe is too short or the
         *   envelope magic is wrong.
         */
        private fun parseThumbnailEnvelope(bytes: ByteArray): ThumbEnvelope {
            if (bytes.size < SkeFormat.PROBE_SIZE) {
                throw InvalidSkeFileException("Corrupted thumbnail envelope")
            }
            val magicStart = SkeFormat.HEADER_SIZE + SkeFormat.OPTIONS_SIZE
            val magic = bytes.copyOfRange(magicStart, magicStart + SkeFormat.THUMB_MAGIC_SIZE)
            if (!magic.contentEquals(SkeFormat.THUMB_MAGIC)) {
                throw InvalidSkeFileException("Corrupted thumbnail envelope")
            }
            val nonceAt = magicStart + SkeFormat.THUMB_MAGIC_SIZE
            val nonce = bytes.copyOfRange(nonceAt, nonceAt + SkeFormat.THUMB_NONCE_SIZE)
            val lengthAt = nonceAt + SkeFormat.THUMB_NONCE_SIZE
            var parsed = 0L
            for (i in 0 until SkeFormat.THUMB_LEN_SIZE) {
                parsed = (parsed shl 8) or (bytes[lengthAt + i].toLong() and 0xFF)
            }
            if (parsed > Int.MAX_VALUE) {
                throw InvalidSkeFileException("Corrupted thumbnail envelope")
            }
            val length = parsed.toInt()
            return ThumbEnvelope(
                bodyOffset = SkeFormat.PROBE_SIZE + length,
                nonce = nonce,
                length = length,
            )
        }

        /** Number of encrypted blocks for a ciphertext of [cipherSize] bytes. */
        fun blockCount(cipherSize: Long, bodyOffset: Int = SkeFormat.PROBE_SIZE): Long {
            val body = cipherSize - bodyOffset
            if (body <= 0) return 0
            return (body + SkeFormat.ENC_BLOCK_SIZE - 1) / SkeFormat.ENC_BLOCK_SIZE
        }

        /**
         * Plaintext size implied by the ciphertext size.
         *
         * Every block carries a 16-byte GCM tag that is not part of the payload,
         * so the plaintext is shorter than the ciphertext body by TAG_SIZE * blocks.
         */
        fun plaintextSize(cipherSize: Long, bodyOffset: Int = SkeFormat.PROBE_SIZE): Long {
            val blocks = blockCount(cipherSize, bodyOffset)
            if (blocks <= 0) return 0
            val body = cipherSize - bodyOffset
            return body - SkeFormat.TAG_SIZE * blocks
        }
    }
}

/** Thrown when a file is not a valid / supported `.ske` container. */
class InvalidSkeFileException(message: String) : Exception(message)

/** Thrown when decryption fails (wrong password or corrupted payload). */
class SkeDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

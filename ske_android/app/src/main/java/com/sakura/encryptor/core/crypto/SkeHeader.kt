package com.sakura.encryptor.core.crypto

/**
 * Parsed 50-byte `.ske` header plus geometry helpers for random access.
 *
 * The header stores salt + master IV so any client can derive the key and
 * seek directly to an arbitrary block.
 */
class SkeHeader(
    val raw: ByteArray,
    val version: ByteArray,
    val salt: ByteArray,
    val masterIv: ByteArray,
    val headerTag: ByteArray,
    /**
     * Offset of the first body block: [SkeFormat.HEADER_SIZE], plus the
     * encrypted thumbnail envelope for v003 containers.
     */
    val bodyOffset: Int = SkeFormat.HEADER_SIZE,
    /** Nonce of the v003 thumbnail envelope; empty when there is no envelope. */
    val thumbnailNonce: ByteArray = ByteArray(0),
    /** Ciphertext length of the thumbnail, GCM tag included; 0 when absent. */
    val thumbnailLength: Int = 0,
) {

    /** True for containers that carry the envelope, even an empty one. */
    val hasThumbnail: Boolean get() = SkeFormat.hasThumbnailEnvelope(version)

    /** True when a preview is actually stored (some v003 files carry none). */
    val hasEmbeddedThumbnail: Boolean get() = hasThumbnail && thumbnailLength > 0

    /**
     * The 38-byte header prefix (MAGIC|VERSION|SALT|MASTER_IV), authenticated as
     * AES-GCM additional data by every supported container version.
     */
    val aad: ByteArray get() = raw.copyOf(SkeFormat.AAD_SIZE)

    companion object {
        /**
         * Parse a header from [bytes].
         *
         * Must be at least [SkeFormat.HEADER_SIZE] long; v003 containers need
         * [SkeFormat.PROBE_SIZE] bytes so the thumbnail envelope can be skipped.
         *
         * @throws InvalidSkeFileException when magic/version/envelope are invalid.
         */
        fun parse(bytes: ByteArray): SkeHeader {
            if (bytes.size < SkeFormat.HEADER_SIZE) throw InvalidSkeFileException("File too small to be a valid .ske")

            val raw = bytes.copyOf(SkeFormat.HEADER_SIZE)
            val magic = raw.copyOfRange(0, SkeFormat.MAGIC_SIZE)
            val version = raw.copyOfRange(SkeFormat.MAGIC_SIZE, SkeFormat.MAGIC_SIZE + SkeFormat.VERSION_SIZE)
            val salt = raw.copyOfRange(10, 26)
            val masterIv = raw.copyOfRange(26, 38)
            val headerTag = raw.copyOfRange(38, 50)

            if (!magic.contentEquals(SkeFormat.MAGIC)) {
                throw InvalidSkeFileException("Invalid magic number: not a Sakura Encryptor file")
            }
            if (!SkeFormat.isSupportedVersion(version)) {
                throw InvalidSkeFileException("Unsupported .ske version: ${version.toString(Charsets.US_ASCII)}")
            }

            val envelope = if (SkeFormat.hasThumbnailEnvelope(version)) {
                parseThumbnailEnvelope(bytes)
            } else {
                null
            }
            return SkeHeader(
                raw = raw,
                version = version,
                salt = salt,
                masterIv = masterIv,
                headerTag = headerTag,
                bodyOffset = envelope?.bodyOffset ?: SkeFormat.HEADER_SIZE,
                thumbnailNonce = envelope?.nonce ?: ByteArray(0),
                thumbnailLength = envelope?.length ?: 0,
            )
        }

        /** Decoded v003 envelope: where the body starts plus the thumbnail geometry. */
        private class ThumbEnvelope(val bodyOffset: Int, val nonce: ByteArray, val length: Int)

        /**
         * Decode the v003 envelope that sits between the header and the body.
         *
         * @throws InvalidSkeFileException when the probe is too short or the
         *   envelope magic is wrong.
         */
        private fun parseThumbnailEnvelope(bytes: ByteArray): ThumbEnvelope {
            if (bytes.size < SkeFormat.PROBE_SIZE) {
                throw InvalidSkeFileException("Corrupted thumbnail envelope")
            }
            val magicStart = SkeFormat.HEADER_SIZE
            val magic = bytes.copyOfRange(magicStart, magicStart + SkeFormat.THUMB_MAGIC_SIZE)
            if (!magic.contentEquals(SkeFormat.THUMB_MAGIC)) {
                throw InvalidSkeFileException("Corrupted thumbnail envelope")
            }
            val nonceAt = magicStart + SkeFormat.THUMB_MAGIC_SIZE
            val nonce = bytes.copyOfRange(nonceAt, nonceAt + SkeFormat.THUMB_NONCE_SIZE)
            val lengthAt = nonceAt + SkeFormat.THUMB_NONCE_SIZE
            val length = ((bytes[lengthAt].toInt() and 0xFF) shl 24) or
                ((bytes[lengthAt + 1].toInt() and 0xFF) shl 16) or
                ((bytes[lengthAt + 2].toInt() and 0xFF) shl 8) or
                (bytes[lengthAt + 3].toInt() and 0xFF)
            return ThumbEnvelope(
                bodyOffset = SkeFormat.HEADER_SIZE + SkeFormat.THUMB_ENVELOPE_SIZE + length,
                nonce = nonce,
                length = length,
            )
        }

        /** Number of encrypted blocks for a ciphertext of [cipherSize] bytes. */
        fun blockCount(cipherSize: Long, bodyOffset: Int = SkeFormat.HEADER_SIZE): Long {
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
        fun plaintextSize(cipherSize: Long, bodyOffset: Int = SkeFormat.HEADER_SIZE): Long {
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

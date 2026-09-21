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
) {
    val isLegacy: Boolean get() = version.contentEquals(SkeFormat.LEGACY_VERSION)

    /**
     * v002 authenticates the 38-byte header prefix (MAGIC|VERSION|SALT|MASTER_IV)
     * as AES-GCM additional authenticated data; v001 used no AAD.
     */
    val aad: ByteArray? get() = if (isLegacy) null else raw.copyOf(SkeFormat.AAD_SIZE)

    companion object {
        /**
         * Parse a header from [bytes] (must be at least [SkeFormat.HEADER_SIZE] long).
         * @throws InvalidSkeFileException when magic/version are not recognized.
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
            return SkeHeader(raw, version, salt, masterIv, headerTag)
        }

        /** Number of encrypted blocks for a ciphertext of [cipherSize] bytes. */
        fun blockCount(cipherSize: Long): Long {
            val body = cipherSize - SkeFormat.HEADER_SIZE
            if (body <= 0) return 0
            return (body + SkeFormat.ENC_BLOCK_SIZE - 1) / SkeFormat.ENC_BLOCK_SIZE
        }

        /**
         * Plaintext size implied by the ciphertext size.
         *
         * Every block carries a 16-byte GCM tag that is not part of the payload,
         * so the plaintext is shorter than the ciphertext body by TAG_SIZE * blocks.
         */
        fun plaintextSize(cipherSize: Long): Long {
            val blocks = blockCount(cipherSize)
            if (blocks <= 0) return 0
            val body = cipherSize - SkeFormat.HEADER_SIZE
            return body - SkeFormat.TAG_SIZE * blocks
        }
    }
}

/** Thrown when a file is not a valid / supported `.ske` container. */
class InvalidSkeFileException(message: String) : Exception(message)

/** Thrown when decryption fails (wrong password or corrupted payload). */
class SkeDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

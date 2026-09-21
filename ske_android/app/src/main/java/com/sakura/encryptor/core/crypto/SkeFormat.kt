package com.sakura.encryptor.core.crypto

/**
 * On-disk `.ske` layout constants.
 *
 * Mirrors `ske_cli/crypto.py` byte-for-byte. Do not change any value here
 * without updating the Python and Web implementations in lock-step.
 *
 * File format (.ske):
 *     Header (50 bytes):
 *         MAGIC(7) | VERSION(3) | SALT(16) | MASTER_IV(12) | HEADER_TAG(12)
 *     Body (N blocks):
 *         each block = AES-256-GCM(chunk) | GCM_TAG(16)
 *         The last block may be shorter than CHUNK_SIZE.
 */
object SkeFormat {

    // --- Magic / versions ---------------------------------------------------
    const val MAGIC_STR = "SakuraE"
    const val VERSION_STR = "002"
    const val LEGACY_VERSION_STR = "001"

    val MAGIC: ByteArray = MAGIC_STR.toByteArray(Charsets.US_ASCII)
    val VERSION: ByteArray = VERSION_STR.toByteArray(Charsets.US_ASCII)
    val LEGACY_VERSION: ByteArray = LEGACY_VERSION_STR.toByteArray(Charsets.US_ASCII)

    // --- Sizes --------------------------------------------------------------
    const val MAGIC_SIZE = 7
    const val VERSION_SIZE = 3
    const val SALT_SIZE = 16
    const val IV_SIZE = 12
    const val TAG_SIZE = 16
    const val HEADER_TAG_SIZE = 12

    /** 7 + 3 + 16 + 12 + 12 = 50 */
    const val HEADER_SIZE = MAGIC_SIZE + VERSION_SIZE + SALT_SIZE + IV_SIZE + HEADER_TAG_SIZE

    /** 38 — the header prefix authenticated as AAD in v002. */
    const val AAD_SIZE = HEADER_SIZE - HEADER_TAG_SIZE

    /** 1 MiB per encrypted block. */
    const val CHUNK_SIZE = 1 shl 20

    /** Encrypted block size on disk, tag included. */
    const val ENC_BLOCK_SIZE = CHUNK_SIZE + TAG_SIZE

    const val KDF_ITERATIONS = 100_000

    // --- Deterministic file-name encryption ---------------------------------
    /**
     * Fixed salt for the deterministic file-name key. MUST stay constant:
     * identical plain names have to encrypt to identical tokens so the
     * encrypted directory tree can be rebuilt without a database.
     */
    val NAME_SALT: ByteArray = "ske-name-salt-00".toByteArray(Charsets.US_ASCII)

    /** HMAC seed used to derive the fixed name-encryption IV. */
    val NAME_IV_SEED: ByteArray = "ske-name-iv".toByteArray(Charsets.US_ASCII)

    const val SKE_EXT = ".ske"

    fun isSupportedVersion(version: ByteArray): Boolean =
        version.contentEquals(VERSION) || version.contentEquals(LEGACY_VERSION)
}

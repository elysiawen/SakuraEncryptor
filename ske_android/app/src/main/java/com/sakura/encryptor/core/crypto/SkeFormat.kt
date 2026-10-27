package com.sakura.encryptor.core.crypto

/**
 * On-disk `.ske` layout constants.
 *
 * Mirrors `ske_cli/crypto.py` byte-for-byte. Do not change any value here
 * without updating the Python and Web implementations in lock-step.
 *
 * File format (.ske):
 *     Header (55 bytes):
 *         MAGIC(8) | VERSION(3) | SALT(16) | MASTER_IV(12) | SHMAC(16)
 *     Options (22 bytes):
 *         KEY_CHECK(16) | KDF_ITERATIONS(5, big-endian) | FLAGS(1)
 *     Thumbnail envelope (22 + N bytes, always present):
 *         THUMB_MAGIC(5) | THUMB_NONCE(12) | THUMB_LEN(5, big-endian) | THUMB_CT(N)
 *     Body (N blocks):
 *         each block = AES-256-GCM(chunk) | GCM_TAG(16)
 *         The last block may be shorter than CHUNK_SIZE.
 */
object SkeFormat {

    // --- Magic / versions ---------------------------------------------------
    const val MAGIC_STR = "SakuraEK"

    /** The only supported version; pre-release 002 / 003 labels are rejected. */
    const val VERSION_STR = "001"

    val MAGIC: ByteArray = MAGIC_STR.toByteArray(Charsets.US_ASCII)
    val VERSION: ByteArray = VERSION_STR.toByteArray(Charsets.US_ASCII)

    // --- Sizes --------------------------------------------------------------
    const val MAGIC_SIZE = 8
    const val VERSION_SIZE = 3
    const val SALT_SIZE = 16
    const val IV_SIZE = 12
    const val TAG_SIZE = 16

    /** Truncated HMAC-SHA256 over prefix + options + envelope + body. */
    const val SHMAC_SIZE = 16

    /** Truncated HMAC of a fixed label; tells a wrong password apart at once. */
    const val KEY_CHECK_SIZE = 16
    const val KDF_ITERATIONS_SIZE = 5
    const val OPTIONS_FLAGS_SIZE = 1

    /** 16 + 5 + 1 = 22 */
    const val OPTIONS_SIZE = KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE + OPTIONS_FLAGS_SIZE

    /** 8 + 3 + 16 + 12 + 16 = 55 */
    const val HEADER_SIZE = MAGIC_SIZE + VERSION_SIZE + SALT_SIZE + IV_SIZE + SHMAC_SIZE

    /** 39 — MAGIC | VERSION | SALT | MASTER_IV, authenticated as AAD. */
    const val AAD_PREFIX_SIZE = HEADER_SIZE - SHMAC_SIZE

    /** 61 — prefix + options, bound into every block. */
    const val AAD_SIZE = AAD_PREFIX_SIZE + OPTIONS_SIZE

    // --- Thumbnail envelope --------------------------------------------------
    const val THUMB_MAGIC_SIZE = 5
    const val THUMB_NONCE_SIZE = 12
    const val THUMB_LEN_SIZE = 5

    /** 5 + 12 + 5 = 22 */
    const val THUMB_ENVELOPE_SIZE = THUMB_MAGIC_SIZE + THUMB_NONCE_SIZE + THUMB_LEN_SIZE

    val THUMB_MAGIC: ByteArray = "SKETH".toByteArray(Charsets.US_ASCII)

    /** Header + options + envelope: enough bytes to locate the first body block. */
    const val PROBE_SIZE = HEADER_SIZE + OPTIONS_SIZE + THUMB_ENVELOPE_SIZE

    // --- Preview payload rules (mirrors generate_thumbnail in crypto.py) -----
    /**
     * Longest side of a stored preview. File-browser tiles are ~300 CSS px, so
     * 512 keeps them crisp on a 2x display without inflating the envelope.
     */
    const val THUMB_MAX_SIDE = 512

    const val THUMB_JPEG_QUALITY = 80

    /** Anything shorter than this is not a picture. */
    const val THUMB_MIN_BYTES = 128

    /** A preview may cost at most 1/N of the file it describes. */
    const val THUMB_COST_DIVISOR = 4

    /** Containers a preview can be sampled from; mirrors `VIDEO_SUFFIXES`. */
    val VIDEO_SUFFIXES: Set<String> = setOf(
        ".mp4", ".m4v", ".mkv", ".mov", ".avi", ".webm",
        ".ts", ".m2ts", ".flv", ".wmv", ".mpg", ".mpeg", ".3gp",
    )

    /** 1 MiB per encrypted block. */
    const val CHUNK_SIZE = 1 shl 20

    /** Encrypted block size on disk, tag included. */
    const val ENC_BLOCK_SIZE = CHUNK_SIZE + TAG_SIZE

    const val KDF_ITERATIONS = 100_000

    /** Refuse hostile files whose work factor would stall the client. */
    const val MAX_KDF_ITERATIONS = 2_000_000

    /** HMAC input for the password probe stored in the options block. */
    val KEY_CHECK_LABEL: ByteArray = "ske-keycheck".toByteArray(Charsets.US_ASCII)

    // --- Deterministic file-name encryption ---------------------------------
    /**
     * Fixed salt for the deterministic file-name key. MUST stay constant:
     * identical plain names have to encrypt to identical tokens so the
     * encrypted directory tree can be rebuilt without a database.
     */
    val NAME_SALT: ByteArray = "skenamesalt-00-s".toByteArray(Charsets.US_ASCII)

    /** HMAC seed used to derive the fixed name-encryption IV. */
    val NAME_IV_SEED: ByteArray = "ske-name-iv".toByteArray(Charsets.US_ASCII)

    const val SKE_EXT = ".ske"

    fun isSupportedVersion(version: ByteArray): Boolean = version.contentEquals(VERSION)
}

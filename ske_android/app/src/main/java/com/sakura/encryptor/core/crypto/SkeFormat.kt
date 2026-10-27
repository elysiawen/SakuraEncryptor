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
 *     Thumbnail envelope (20 + N bytes, v003 only):
 *         THUMB_MAGIC(4) | THUMB_NONCE(12) | THUMB_LEN(4, big-endian) | THUMB_CT(N)
 *     Body (N blocks):
 *         each block = AES-256-GCM(chunk) | GCM_TAG(16)
 *         The last block may be shorter than CHUNK_SIZE.
 */
object SkeFormat {

    // --- Magic / versions ---------------------------------------------------
    const val MAGIC_STR = "SakuraE"

    /** AAD-protected body, no thumbnail envelope. Read-only for this client. */
    const val VERSION_002_STR = "002"

    /** Adds a thumbnail envelope between the header and the body. */
    const val VERSION_003_STR = "003"

    /**
     * Version written by this client.
     *
     * v003 is what the CLI and the Web player already write: a container
     * without it carries no preview, so other clients can only show a generic
     * tile for it. Keep this equal to [VERSION_003_STR].
     */
    const val VERSION_STR = VERSION_003_STR

    val MAGIC: ByteArray = MAGIC_STR.toByteArray(Charsets.US_ASCII)
    val VERSION: ByteArray = VERSION_STR.toByteArray(Charsets.US_ASCII)
    val VERSION_002: ByteArray = VERSION_002_STR.toByteArray(Charsets.US_ASCII)
    val VERSION_003: ByteArray = VERSION_003_STR.toByteArray(Charsets.US_ASCII)

    // --- Sizes --------------------------------------------------------------
    const val MAGIC_SIZE = 7
    const val VERSION_SIZE = 3
    const val SALT_SIZE = 16
    const val IV_SIZE = 12
    const val TAG_SIZE = 16
    const val HEADER_TAG_SIZE = 12

    /** 7 + 3 + 16 + 12 + 12 = 50 */
    const val HEADER_SIZE = MAGIC_SIZE + VERSION_SIZE + SALT_SIZE + IV_SIZE + HEADER_TAG_SIZE

    /** 38 — the header prefix authenticated as AAD in v002 / v003. */
    const val AAD_SIZE = HEADER_SIZE - HEADER_TAG_SIZE

    // --- Thumbnail envelope (v003) ------------------------------------------
    const val THUMB_MAGIC_SIZE = 4
    const val THUMB_NONCE_SIZE = 12
    const val THUMB_LEN_SIZE = 4

    /** 4 + 12 + 4 = 20 */
    const val THUMB_ENVELOPE_SIZE = THUMB_MAGIC_SIZE + THUMB_NONCE_SIZE + THUMB_LEN_SIZE

    val THUMB_MAGIC: ByteArray = "SKTH".toByteArray(Charsets.US_ASCII)

    /** Header + envelope: enough bytes to locate the first body block. */
    const val PROBE_SIZE = HEADER_SIZE + THUMB_ENVELOPE_SIZE

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
        version.contentEquals(VERSION_002) || version.contentEquals(VERSION_003)

    fun hasThumbnailEnvelope(version: ByteArray): Boolean = version.contentEquals(VERSION_003)
}

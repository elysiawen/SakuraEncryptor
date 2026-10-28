package com.sakura.encryptor.core.crypto

import com.sakura.encryptor.core.cache.DerivedKeyCache
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Whole-file `.ske` encryption / decryption.
 *
 * The header is written last because SHMAC is an HMAC over the prefix, the
 * options block, the envelope and the whole body. Encryption therefore writes
 * a placeholder header, streams the body while feeding the MAC, then seeks
 * back to fill in the prefix and the tag — the same strategy as `crypto.py`.
 */
object SkeFileCipher {

    /**
     * Encrypt [src] into [dst] (both regular files), writing the version 001 layout.
     *
     * @param thumbnail encrypted JPEG preview to store in the envelope; `null`
     *   writes an empty envelope, exactly like a CLI file whose source was not
     *   worth previewing. Use `ThumbnailGenerator` to build one.
     * @param onProgress invoked as `(bytesProcessed, totalBytes)` of plaintext.
     */
    fun encryptFile(
        src: File,
        dst: File,
        password: String,
        thumbnail: ByteArray? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        dst.parentFile?.mkdirs()

        val salt = SkeCrypto.randomBytes(SkeFormat.SALT_SIZE)
        val masterIv = SkeCrypto.randomBytes(SkeFormat.IV_SIZE)
        val iterations = SkeFormat.KDF_ITERATIONS
        val key = SkeCrypto.deriveKey(password, salt, iterations)

        // MAGIC | VERSION | SALT | MASTER_IV, then the options block: both are
        // bound into every block as AAD, so neither can be swapped underneath.
        val headerPrefix = SkeFormat.MAGIC + SkeFormat.VERSION + salt + masterIv
        val options = optionsBlock(key, iterations)
        val aad = headerPrefix + options
        val total = src.length()

        // The envelope is always written, even when empty: THUMB_LEN == 0 is
        // how "no preview" is expressed, and readers locate the body through it.
        val thumbNonce = SkeCrypto.randomBytes(SkeFormat.THUMB_NONCE_SIZE)
        val thumbCipher = if (thumbnail == null || thumbnail.isEmpty()) {
            ByteArray(0)
        } else {
            SkeCrypto.gcmEncrypt(
                key,
                thumbNonce,
                thumbnail,
                thumbnailAad(aad, thumbnail.size + SkeFormat.TAG_SIZE),
            )
        }
        val envelope = SkeFormat.THUMB_MAGIC +
            thumbNonce +
            encodeBigEndian(thumbCipher.size.toLong(), SkeFormat.THUMB_LEN_SIZE) +
            thumbCipher

        RandomAccessFile(dst, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(SkeFormat.HEADER_SIZE)) // placeholder
            raf.write(options)
            raf.write(envelope)

            val mac = SkeCrypto.newHmac(key)
            mac.update(headerPrefix)
            mac.update(options)
            mac.update(envelope)

            val buffer = ByteArray(SkeFormat.CHUNK_SIZE)
            var blockIndex = 0L
            var processed = 0L

            src.inputStream().buffered().use { input ->
                while (true) {
                    val read = readFully(input, buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)

                    val nonce = SkeCrypto.blockNonce(masterIv, blockIndex)
                    val ct = SkeCrypto.gcmEncrypt(key, nonce, chunk, aad)

                    raf.write(ct)
                    mac.update(ct)

                    processed += read
                    blockIndex++
                    onProgress?.invoke(processed, total)
                }
            }

            raf.seek(0)
            raf.write(headerPrefix)
            raf.write(mac.doFinal().copyOf(SkeFormat.SHMAC_SIZE))
        }
    }

    /**
     * Decrypt [src] into [dst].
     *
     * @throws InvalidSkeFileException for a broken container,
     * @throws SkeDecryptionException  for a wrong password / corrupted payload.
     */
    fun decryptFile(
        src: File,
        dst: File,
        password: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        dst.parentFile?.mkdirs()

        val cipherSize = src.length()
        val header: SkeHeader
        val key: ByteArray

        RandomAccessFile(src, "r").use { raf ->
            // Read past the header so the thumbnail envelope can be skipped.
            val headerBytes = ByteArray(SkeFormat.PROBE_SIZE)
            val readHeader = readFully(raf, headerBytes)
            if (readHeader < SkeFormat.HEADER_SIZE) {
                throw InvalidSkeFileException("File too small to be a valid .ske")
            }
            header = SkeHeader.parse(headerBytes.copyOf(readHeader))
            key = SkeCrypto.deriveKey(password, header.salt, header.kdfIterations)

            // Fail fast: the password probe costs nothing compared to a block.
            if (!SkeCrypto.constantTimeEquals(header.keyCheck, SkeCrypto.keyCheck(key))) {
                throw SkeDecryptionException("Wrong password.")
            }

            if (cipherSize < header.bodyOffset) {
                throw InvalidSkeFileException("Corrupted thumbnail envelope")
            }

            // SHMAC covers the envelope too, so read it back before seeking to
            // the first block (it may extend past PROBE_SIZE).
            val envelopeStart = SkeFormat.HEADER_SIZE + SkeFormat.OPTIONS_SIZE
            raf.seek(envelopeStart.toLong())
            val envelope = ByteArray(header.bodyOffset - envelopeStart)
            if (readFully(raf, envelope) != envelope.size) {
                throw InvalidSkeFileException("Truncated thumbnail envelope")
            }
            raf.seek(header.bodyOffset.toLong())

            val aad = header.aad
            val mac = SkeCrypto.newHmac(key)
            mac.update(header.raw, 0, SkeFormat.AAD_PREFIX_SIZE)
            mac.update(header.options)
            mac.update(envelope)
            val plainTotal = SkeHeader.plaintextSize(cipherSize, header.bodyOffset)

            dst.outputStream().buffered().use { out ->
                val buffer = ByteArray(SkeFormat.ENC_BLOCK_SIZE)
                var blockIndex = 0L
                var processed = 0L

                while (true) {
                    val read = readFully(raf, buffer)
                    if (read <= 0) break
                    val ct = if (read == buffer.size) buffer else buffer.copyOf(read)
                    mac.update(ct)

                    val nonce = SkeCrypto.blockNonce(header.masterIv, blockIndex)
                    val plaintext = try {
                        SkeCrypto.gcmDecrypt(key, nonce, ct, aad)
                    } catch (e: Exception) {
                        throw SkeDecryptionException(
                            "Decryption failed at block $blockIndex. Wrong password or corrupted file.", e
                        )
                    }
                    out.write(plaintext)

                    processed += plaintext.size
                    blockIndex++
                    onProgress?.invoke(processed, plainTotal)
                }
            }

            val computed = mac.doFinal().copyOf(SkeFormat.SHMAC_SIZE)
            if (!SkeCrypto.constantTimeEquals(computed, header.shmac)) {
                throw SkeDecryptionException("Integrity check failed (SHMAC mismatch). File may be corrupted.")
            }
        }
    }

    /**
     * Decrypt the preview stored in the container's thumbnail envelope.
     *
     * Costs one ~40 KB read instead of a whole-file decrypt, which is what
     * makes previews usable in a list.
     *
     * @return the JPEG bytes, or `null` when the file carries no preview
     *   (`THUMB_LEN == 0`).
     * @throws InvalidSkeFileException for a broken envelope,
     * @throws SkeDecryptionException  for a wrong password / corrupted preview.
     */
    fun decryptThumbnail(src: File, password: String): ByteArray? {
        val header: SkeHeader
        val ciphertext: ByteArray

        RandomAccessFile(src, "r").use { raf ->
            val probe = ByteArray(SkeFormat.PROBE_SIZE)
            val readHeader = readFully(raf, probe)
            if (readHeader < SkeFormat.HEADER_SIZE) {
                throw InvalidSkeFileException("File too small to be a valid .ske")
            }

            header = SkeHeader.parse(probe.copyOf(readHeader))
            if (header.thumbnailLength <= 0) return null

            val available = src.length() - SkeFormat.PROBE_SIZE
            if (header.thumbnailLength > available) {
                throw InvalidSkeFileException("Corrupted thumbnail envelope")
            }
            ciphertext = ByteArray(header.thumbnailLength)
            if (readFully(raf, ciphertext) != ciphertext.size) {
                throw InvalidSkeFileException("Truncated thumbnail envelope")
            }
        }

        return decryptThumbnail(header, ciphertext, password)
    }

    /**
     * Decrypt a preview that was read separately from the container — a range
     * request over HTTP, or the first bytes of an SAF stream.
     */
    internal fun decryptThumbnail(
        header: SkeHeader,
        ciphertext: ByteArray,
        password: String,
    ): ByteArray {
        // Grids decrypt one preview per file; the memo keeps a revisit from
        // paying the KDF again (see DerivedKeyCache).
        val key = DerivedKeyCache.getOrDerive(password, header.salt, header.kdfIterations)
        if (!SkeCrypto.constantTimeEquals(header.keyCheck, SkeCrypto.keyCheck(key))) {
            throw SkeDecryptionException("Wrong password.")
        }

        val aad = thumbnailAad(header.aad, ciphertext.size)
        return try {
            SkeCrypto.gcmDecrypt(key, header.thumbnailNonce, ciphertext, aad)
        } catch (e: Exception) {
            throw SkeDecryptionException(
                "Thumbnail decryption failed. Wrong password or corrupted file.", e
            )
        }
    }

    /** Options block: key check | KDF iterations | reserved flags (zero). */
    private fun optionsBlock(key: ByteArray, iterations: Int): ByteArray {
        val block = ByteArray(SkeFormat.OPTIONS_SIZE)
        SkeCrypto.keyCheck(key).copyInto(block, 0)
        encodeBigEndian(iterations.toLong(), SkeFormat.KDF_ITERATIONS_SIZE)
            .copyInto(block, SkeFormat.KEY_CHECK_SIZE)
        return block
    }

    /** AAD for the envelope: header prefix + options + "SKETH" + ciphertext length. */
    private fun thumbnailAad(aad: ByteArray, cipherLength: Int): ByteArray =
        aad + SkeFormat.THUMB_MAGIC + encodeBigEndian(cipherLength.toLong(), SkeFormat.THUMB_LEN_SIZE)

    /** Big-endian fixed-width integer, matching the Python `int.to_bytes` calls. */
    private fun encodeBigEndian(value: Long, size: Int): ByteArray =
        ByteArray(size) { i -> (value ushr (8 * (size - 1 - i))).toByte() }

    internal fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    internal fun readFully(raf: RandomAccessFile, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = raf.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }
}

/**
 * Random-access block decrypter used by the streaming player.
 *
 * Unlike [SkeFileCipher.decryptFile] this never verifies the whole-file header
 * tag (that would require reading the entire file); the per-block GCM tag is
 * enough to detect a wrong password or a tampered block.
 */
class SkeBlockDecryptor(private val header: SkeHeader, private val key: ByteArray) {

    private val aad: ByteArray = header.aad

    /** Decrypt ciphertext block [blockIndex] (tag included) into plaintext. */
    fun decryptBlock(blockIndex: Long, ciphertext: ByteArray): ByteArray {
        val nonce = SkeCrypto.blockNonce(header.masterIv, blockIndex)
        return try {
            SkeCrypto.gcmDecrypt(key, nonce, ciphertext, aad)
        } catch (e: Exception) {
            throw SkeDecryptionException(
                "Decryption failed at block $blockIndex. Wrong password or corrupted file.", e
            )
        }
    }

    companion object {
        fun create(header: SkeHeader, password: String): SkeBlockDecryptor {
            val key = DerivedKeyCache.getOrDerive(password, header.salt, header.kdfIterations)
            if (!SkeCrypto.constantTimeEquals(header.keyCheck, SkeCrypto.keyCheck(key))) {
                throw SkeDecryptionException("Wrong password.")
            }
            return SkeBlockDecryptor(header, key)
        }
    }
}

package com.sakura.encryptor.core.crypto

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Whole-file `.ske` encryption / decryption.
 *
 * The header is written last because its integrity tag is the SHA-256 of the
 * entire ciphertext body. Encryption therefore writes a 50-byte placeholder,
 * streams the body while hashing it, then seeks back to fill the header in —
 * the same strategy as `crypto.py`.
 */
object SkeFileCipher {

    /**
     * Encrypt [src] into [dst] (both regular files).
     *
     * @param onProgress invoked as `(bytesProcessed, totalBytes)` of plaintext.
     */
    fun encryptFile(
        src: File,
        dst: File,
        password: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        dst.parentFile?.mkdirs()

        val salt = SkeCrypto.randomBytes(SkeFormat.SALT_SIZE)
        val masterIv = SkeCrypto.randomBytes(SkeFormat.IV_SIZE)
        val key = SkeCrypto.deriveKey(password, salt)

        // MAGIC | VERSION | SALT | MASTER_IV — bound into every block as AAD (v002).
        val headerPrefix = SkeFormat.MAGIC + SkeFormat.VERSION + salt + masterIv
        val digest = SkeCrypto.newSha256()
        val total = src.length()

        RandomAccessFile(dst, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(SkeFormat.HEADER_SIZE)) // placeholder

            val buffer = ByteArray(SkeFormat.CHUNK_SIZE)
            var blockIndex = 0L
            var processed = 0L

            src.inputStream().buffered().use { input ->
                while (true) {
                    val read = readFully(input, buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)

                    val nonce = SkeCrypto.blockNonce(masterIv, blockIndex)
                    val ct = SkeCrypto.gcmEncrypt(key, nonce, chunk, headerPrefix)

                    raf.write(ct)
                    digest.update(ct)

                    processed += read
                    blockIndex++
                    onProgress?.invoke(processed, total)
                }
            }

            val headerTag = digest.digest().copyOf(SkeFormat.HEADER_TAG_SIZE)
            raf.seek(0)
            raf.write(headerPrefix)
            raf.write(headerTag)
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
            val headerBytes = ByteArray(SkeFormat.HEADER_SIZE)
            val readHeader = readFully(raf, headerBytes)
            if (readHeader < SkeFormat.HEADER_SIZE) {
                throw InvalidSkeFileException("File too small to be a valid .ske")
            }
            header = SkeHeader.parse(headerBytes)
            key = SkeCrypto.deriveKey(password, header.salt)

            val aad = header.aad
            val digest = SkeCrypto.newSha256()
            val plainTotal = SkeHeader.plaintextSize(cipherSize)

            dst.outputStream().buffered().use { out ->
                val buffer = ByteArray(SkeFormat.ENC_BLOCK_SIZE)
                var blockIndex = 0L
                var processed = 0L

                while (true) {
                    val read = readFully(raf, buffer)
                    if (read <= 0) break
                    val ct = if (read == buffer.size) buffer else buffer.copyOf(read)
                    digest.update(ct)

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

            val computed = digest.digest().copyOf(SkeFormat.HEADER_TAG_SIZE)
            if (!SkeCrypto.constantTimeEquals(computed, header.headerTag)) {
                throw SkeDecryptionException("Header integrity check failed. File may be corrupted.")
            }
        }
    }

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

    private val aad: ByteArray? = header.aad

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
        fun create(header: SkeHeader, password: String): SkeBlockDecryptor =
            SkeBlockDecryptor(header, SkeCrypto.deriveKey(password, header.salt))
    }
}

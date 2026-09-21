package com.sakura.encryptor.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

/**
 * Cross-implementation tests.
 *
 * Every hard-coded expectation below was produced by the Python core
 * (`ske_cli/crypto.py`) — see `gen_vectors.py` — so a green run means this
 * client is byte-compatible with the CLI and the Web player.
 */
class SkeCryptoTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun resourceBytes(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)?.readBytes()
            ?: error("Missing test resource: $path")

    // -----------------------------------------------------------------------
    // Key derivation
    // -----------------------------------------------------------------------

    @Test
    fun `deriveKey matches python vector`() {
        val salt = unhex("000102030405060708090a0b0c0d0e0f")
        val key = SkeCrypto.deriveKey("correct horse battery staple", salt)
        assertEquals("49d49c25f597846209f0d92e7770ab64e1c75e94b4ce6c509265ee67175d2a1e", hex(key))
        assertEquals(32, key.size)
    }

    @Test
    fun `deriveKey encodes password as utf8`() {
        val key = SkeCrypto.deriveKey("密码123", ByteArray(16))
        assertEquals("bd8ea80303f52b4d7bf3c994c43bc03c46e3b6a980f4acb8a4f44e2386a235dc", hex(key))
    }

    // -----------------------------------------------------------------------
    // Deterministic name encryption
    // -----------------------------------------------------------------------

    @Test
    fun `name key and tokens match python`() {
        val key = SkeCrypto.deriveNameKey("sakura-pass")
        assertEquals("70d2793173d58260c53a0eb6a80de2eac16d146726bcd8304aa62e922a7b00a6", hex(key))

        assertEquals("kJLHVtK33bNsfXHJaxyHPRj5pQaH7j2qRQ", SkeCrypto.encryptName("hello.mp4", key))
        assertEquals(
            "EFAt0x8IkCXtMAAMA4CgT-4Xn9eJvYp911HWBtRCyld9",
            SkeCrypto.encryptName("视频 测试.mkv", key)
        )
        assertEquals("mWBEuB9EVFDyJJ6t8c4XPBQ", SkeCrypto.encryptName("a", key))
        assertEquals("Hl8a0jcol3uIrRi9_cA6X2JG0WJpXQ", SkeCrypto.encryptName("樱花", key))
    }

    @Test
    fun `name encryption is deterministic and reversible`() {
        val key = SkeCrypto.deriveNameKey("pw")
        val names = listOf("hello.mp4", "视频 测试.mkv", "樱花", "a", "space name.txt", "emoji 🌸.png")
        for (name in names) {
            val first = SkeCrypto.encryptName(name, key)
            val second = SkeCrypto.encryptName(name, key)
            assertEquals("token must be deterministic", first, second)
            assertEquals(name, SkeCrypto.decryptName(first, key))
        }
    }

    @Test
    fun `decryptName rejects a wrong key`() {
        val token = SkeCrypto.encryptName("secret.mkv", SkeCrypto.deriveNameKey("pw"))
        val wrongKey = SkeCrypto.deriveNameKey("not-pw")
        assertThrows(SkeDecryptionException::class.java) {
            SkeCrypto.decryptName(token, wrongKey)
        }
    }

    @Test
    fun `encryptPath and decryptPath round trip`() {
        val key = SkeCrypto.deriveNameKey("pw")
        val plain = "movies/2024/樱花 片段.mp4"
        val enc = SkeCrypto.encryptPath(plain, key)
        assertEquals(3, enc.split("/").size)
        assertEquals(plain, SkeCrypto.decryptPath(enc, key))
    }

    // -----------------------------------------------------------------------
    // Block nonce
    // -----------------------------------------------------------------------

    @Test
    fun `block nonce matches python`() {
        val iv = unhex("00112233445566778899aabb")
        assertEquals("00112233445566778899aabb", hex(SkeCrypto.blockNonce(iv, 0L)))
        assertEquals("00112233445566778899aaba", hex(SkeCrypto.blockNonce(iv, 1L)))
        assertEquals("00112233445566778899aab9", hex(SkeCrypto.blockNonce(iv, 2L)))
        assertEquals("00112233445566778899aa44", hex(SkeCrypto.blockNonce(iv, 255L)))
        assertEquals("00112233445566778899abbb", hex(SkeCrypto.blockNonce(iv, 256L)))
        assertEquals("00112233445566778896e8fb", hex(SkeCrypto.blockNonce(iv, 1_000_000L)))
    }

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    @Test
    fun `size geometry matches the python sample`() {
        // Python: 2,300,000 plaintext bytes -> 3 blocks -> 50 + 2,300,000 + 3*16
        assertEquals(3L, SkeHeader.blockCount(2_300_098L))
        assertEquals(2_300_000L, SkeHeader.plaintextSize(2_300_098L))
        assertEquals(0L, SkeHeader.blockCount(50L))
        assertEquals(0L, SkeHeader.plaintextSize(50L))
    }

    // -----------------------------------------------------------------------
    // Whole-file round trip
    // -----------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("ske-test").toFile()

    @Test
    fun `file round trip across multiple blocks`() {
        val dir = tempDir()
        val plain = ByteArray(2_500_000) { (it % 251).toByte() }
        val src = File(dir, "input.bin").apply { writeBytes(plain) }
        val enc = File(dir, "input.bin.ske")
        val dec = File(dir, "output.bin")

        SkeFileCipher.encryptFile(src, enc, "pw-123")
        assertEquals(
            "cipher size must be header + plaintext + one tag per block",
            SkeFormat.HEADER_SIZE + plain.size + 3 * SkeFormat.TAG_SIZE,
            enc.length().toInt()
        )

        SkeFileCipher.decryptFile(enc, dec, "pw-123")
        assertArrayEquals(plain, dec.readBytes())
    }

    @Test
    fun `empty file round trip`() {
        val dir = tempDir()
        val src = File(dir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        val enc = File(dir, "empty.bin.ske")
        val dec = File(dir, "empty.out")

        SkeFileCipher.encryptFile(src, enc, "pw")
        assertEquals(SkeFormat.HEADER_SIZE.toLong(), enc.length())

        SkeFileCipher.decryptFile(enc, dec, "pw")
        assertEquals(0, dec.length().toInt())
    }

    @Test
    fun `exact single block round trip`() {
        val dir = tempDir()
        val plain = ByteArray(SkeFormat.CHUNK_SIZE) { (it % 253).toByte() }
        val src = File(dir, "one.bin").apply { writeBytes(plain) }
        val enc = File(dir, "one.bin.ske")
        val dec = File(dir, "one.out")

        SkeFileCipher.encryptFile(src, enc, "pw")
        assertEquals(1L, SkeHeader.blockCount(enc.length()))
        SkeFileCipher.decryptFile(enc, dec, "pw")
        assertArrayEquals(plain, dec.readBytes())
    }

    @Test
    fun `wrong password fails`() {
        val dir = tempDir()
        val payload = ByteArray(5000).also { Random(1).nextBytes(it) }
        val src = File(dir, "secret.bin").apply { writeBytes(payload) }
        val enc = File(dir, "secret.bin.ske")
        SkeFileCipher.encryptFile(src, enc, "right")

        assertThrows(SkeDecryptionException::class.java) {
            SkeFileCipher.decryptFile(enc, File(dir, "out.bin"), "wrong")
        }
    }

    @Test
    fun `tampered magic is rejected`() {
        val dir = tempDir()
        val src = File(dir, "a.bin").apply { writeBytes(ByteArray(100) { 7 }) }
        val enc = File(dir, "a.bin.ske")
        SkeFileCipher.encryptFile(src, enc, "pw")

        val bytes = enc.readBytes()
        bytes[0] = 'X'.code.toByte()
        val broken = File(dir, "broken.ske").apply { writeBytes(bytes) }

        assertThrows(InvalidSkeFileException::class.java) {
            SkeFileCipher.decryptFile(broken, File(dir, "out.bin"), "pw")
        }
    }

    @Test
    fun `tampered ciphertext block is rejected`() {
        val dir = tempDir()
        val src = File(dir, "b.bin").apply { writeBytes(ByteArray(200_000) { (it % 97).toByte() }) }
        val enc = File(dir, "b.bin.ske")
        SkeFileCipher.encryptFile(src, enc, "pw")

        val bytes = enc.readBytes()
        bytes[SkeFormat.HEADER_SIZE + 10] = (bytes[SkeFormat.HEADER_SIZE + 10].toInt() xor 0xFF).toByte()
        val broken = File(dir, "broken.ske").apply { writeBytes(bytes) }

        assertThrows(SkeDecryptionException::class.java) {
            SkeFileCipher.decryptFile(broken, File(dir, "out.bin"), "pw")
        }
    }

    // -----------------------------------------------------------------------
    // Interoperability with the CLI
    // -----------------------------------------------------------------------

    @Test
    fun `decrypts a file produced by the python cli`() {
        val dir = tempDir()
        val password = "sakura-test-密码-123"
        val expected = resourceBytes("vectors/cli_sample.txt")

        val enc = File(dir, "cli_sample.ske").apply { writeBytes(resourceBytes("vectors/cli_sample.ske")) }
        val dec = File(dir, "cli_sample.out")

        SkeFileCipher.decryptFile(enc, dec, password)
        assertArrayEquals(expected, dec.readBytes())
    }

    @Test
    fun `decrypts an empty file produced by the python cli`() {
        val dir = tempDir()
        val enc = File(dir, "cli_empty.ske").apply { writeBytes(resourceBytes("vectors/cli_empty.ske")) }
        val dec = File(dir, "cli_empty.out")

        SkeFileCipher.decryptFile(enc, dec, "sakura-test-密码-123")
        assertEquals(0, dec.length().toInt())
    }

    @Test
    fun `block decryptor random access matches sequential decryption`() {
        val dir = tempDir()
        val plain = ByteArray(2_400_000) { (it % 249).toByte() }
        val src = File(dir, "rand.bin").apply { writeBytes(plain) }
        val enc = File(dir, "rand.bin.ske")
        SkeFileCipher.encryptFile(src, enc, "pw")

        val cipher = enc.readBytes()
        val header = SkeHeader.parse(cipher)
        val decryptor = SkeBlockDecryptor.create(header, "pw")

        // Walk blocks in reverse — random access must not depend on order/state.
        val blockCount = SkeHeader.blockCount(enc.length()).toInt()
        for (index in blockCount - 1 downTo 0) {
            val start = SkeFormat.HEADER_SIZE + index * SkeFormat.ENC_BLOCK_SIZE
            val end = minOf(start + SkeFormat.ENC_BLOCK_SIZE, cipher.size)
            val ct = cipher.copyOfRange(start, end)
            val pt = decryptor.decryptBlock(index.toLong(), ct)

            val plainStart = index * SkeFormat.CHUNK_SIZE
            val expected = plain.copyOfRange(plainStart, plainStart + pt.size)
            assertArrayEquals("block $index mismatch", expected, pt)
        }
    }
}

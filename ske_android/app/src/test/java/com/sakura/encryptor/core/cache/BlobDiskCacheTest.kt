package com.sakura.encryptor.core.cache

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The disk caches are the only part of the app whose whole point is surviving a
 * process restart, so the round trip is exercised against a real directory: a
 * second [BlobDiskCache] over the same folder stands in for the next launch.
 */
class BlobDiskCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("ske-cache").toFile()

    @Test
    fun `blob survives a new instance over the same directory`() {
        val root = File(tempDir(), "thumbnails")
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        BlobDiskCache(root, initialLimit = 1024).put("/comics/vol1/a.ske|unlocked", payload)

        val afterRestart = BlobDiskCache(root, initialLimit = 1024)
        assertArrayEquals(payload, afterRestart.get("/comics/vol1/a.ske|unlocked"))
        assertEquals(payload.size.toLong(), afterRestart.usageBytes())
    }

    @Test
    fun `different keys do not collide`() {
        val cache = BlobDiskCache(File(tempDir(), "thumbnails"), initialLimit = 1024)
        cache.put("salt-a|0", byteArrayOf(1))
        cache.put("salt-b|0", byteArrayOf(2))

        assertArrayEquals(byteArrayOf(1), cache.get("salt-a|0"))
        assertArrayEquals(byteArrayOf(2), cache.get("salt-b|0"))
        assertNull(cache.get("salt-c|0"))
    }

    @Test
    fun `clearing removes everything`() {
        val cache = BlobDiskCache(File(tempDir(), "plaintext"), initialLimit = 1024)
        cache.put("a", byteArrayOf(1, 2))
        cache.put("b", byteArrayOf(3, 4))
        assertTrue(cache.usageBytes() > 0)

        cache.clear()

        assertEquals(0L, cache.usageBytes())
        assertNull(cache.get("a"))
    }

    @Test
    fun `oldest entries are evicted when the budget is exceeded`() {
        val cache = BlobDiskCache(File(tempDir(), "plaintext"), initialLimit = 25)

        cache.put("first", ByteArray(10))
        Thread.sleep(20)
        cache.put("second", ByteArray(10))
        Thread.sleep(20)
        cache.put("third", ByteArray(10))

        // 30 bytes in a 25-byte budget: the least recently written had to go.
        assertNull(cache.get("first"))
        assertTrue(cache.get("second") != null)
        assertTrue(cache.get("third") != null)
        assertTrue(cache.usageBytes() <= 25)
    }

    @Test
    fun `unlimited budget never evicts`() {
        val cache = BlobDiskCache(File(tempDir(), "plaintext"), initialLimit = 10)
        cache.put("first", ByteArray(10))

        // What the "无上限" chip stores: no budget at all.
        cache.setLimitBytes(Long.MAX_VALUE)
        Thread.sleep(20)
        cache.put("second", ByteArray(10))
        Thread.sleep(20)
        cache.put("third", ByteArray(10))

        assertEquals(30L, cache.usageBytes())
    }

    @Test
    fun `lowering the budget evicts immediately`() {
        val cache = BlobDiskCache(File(tempDir(), "plaintext"), initialLimit = 1024)
        cache.put("older", ByteArray(10))
        Thread.sleep(20)
        cache.put("newer", ByteArray(10))
        assertEquals(20L, cache.usageBytes())

        // The user dragged the slider down: the budget must be honoured at once,
        // not only on the next write.
        cache.setLimitBytes(10)

        assertTrue(cache.usageBytes() <= 10)
        assertNull(cache.get("older"))
        assertTrue(cache.get("newer") != null)
    }
}

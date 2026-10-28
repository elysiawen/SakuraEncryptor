package com.sakura.encryptor.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ordering to `decName.localeCompare(other, 'zh-CN')` — the rule the
 * web client applies in `FileBrowser.vue` and `usePlaylist.js`.
 */
class DisplayNameOrderTest {

    @Test
    fun `zero padded names sort in numbering order`() {
        val names = listOf("010.jpg", "002.jpg", "001.jpg")

        assertEquals(
            listOf("001.jpg", "002.jpg", "010.jpg"),
            names.sortedWith(DisplayNameOrder),
        )
    }

    @Test
    fun `unpadded numbers follow plain collation, like the web client`() {
        // No numeric awareness: "10" < "2" for localeCompare('zh-CN') as well.
        val names = listOf("page2.jpg", "page10.jpg", "page1.jpg")

        assertEquals(
            listOf("page1.jpg", "page10.jpg", "page2.jpg"),
            names.sortedWith(DisplayNameOrder),
        )
    }

    @Test
    fun `chinese chapter names sort exactly like localeCompare`() {
        // Digits collate before CJK characters, so "第10话" comes before "第1话".
        // Verified against `localeCompare(other, 'zh-CN')` in node:
        //   第10话.cbz | 第1话.cbz | 第2话.cbz
        val names = listOf("第2话.cbz", "第10话.cbz", "第1话.cbz")

        assertEquals(
            listOf("第10话.cbz", "第1话.cbz", "第2话.cbz"),
            names.sortedWith(DisplayNameOrder),
        )
    }

    @Test
    fun `names without digits fall back to collation`() {
        val names = listOf("banana.mp4", "apple.mp4", "cherry.mp4")

        assertEquals(
            listOf("apple.mp4", "banana.mp4", "cherry.mp4"),
            names.sortedWith(DisplayNameOrder),
        )
    }

    @Test
    fun `prefix names sort before their longer variants`() {
        val names = listOf("page1a.jpg", "page1.jpg")

        assertEquals(listOf("page1.jpg", "page1a.jpg"), names.sortedWith(DisplayNameOrder))
    }
}

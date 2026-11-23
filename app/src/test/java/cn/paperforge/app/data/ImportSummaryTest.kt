package cn.paperforge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportSummaryTest {
    @Test
    fun everyPhotoInSaysNothing() {
        assertNull(ImportSummary.message(ImportOutcome(12, emptyList())))
    }

    @Test
    fun skippedPhotosAreCountedAndNamed() {
        assertEquals(
            "已导入 10 张，2 张无法读取：a.jpg、b.jpg",
            ImportSummary.message(ImportOutcome(10, listOf("a.jpg", "b.jpg"))),
        )
    }

    @Test
    fun longListsAreShortened() {
        assertEquals(
            "已导入 1 张，5 张无法读取：1.jpg、2.jpg、3.jpg 等",
            ImportSummary.message(ImportOutcome(1, listOf("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg"))),
        )
    }

    @Test
    fun nothingReadable() {
        assertEquals("所选 2 张图片都无法读取：x.heic、第 2 张", ImportSummary.message(ImportOutcome(0, listOf("x.heic", "第 2 张"))))
    }

    @Test
    fun progressCountsUp() {
        assertEquals("正在导入图片 0/30", ImportSummary.progress(0, 30))
        assertEquals("正在导入图片 30/30", ImportSummary.progress(31, 30))
    }

    @Test
    fun cancelledSaysWhatCameIn() {
        assertEquals("已取消导入", ImportSummary.cancelled(0, 8))
        assertEquals("已取消导入，已导入 3/8 张", ImportSummary.cancelled(3, 8))
    }
}

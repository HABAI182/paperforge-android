package cn.paperforge.app

import cn.paperforge.app.model.PageSpreads
import org.junit.Assert.*
import org.junit.Test

class PageSpreadsTest {
    @Test
    fun coverAndPairsKeepEveryPhysicalPageExactlyOnce() {
        for (count in 1..11) for (cover in listOf(false, true)) {
            val groups = PageSpreads.groups(count, true, cover)
            assertEquals((0 until count).toList(), groups.flatten())
            if (cover) assertEquals(listOf(0), groups.first())
            groups.forEachIndexed { group, pages ->
                pages.forEach { assertEquals(group, PageSpreads.groupOf(groups, it)) }
            }
            val portrait = PageSpreads.groups(count, false, cover)
            for (page in 0 until count) {
                val wide = PageSpreads.groupOf(groups, page)
                assertTrue(page in groups[wide])
                assertEquals(page, PageSpreads.groupOf(portrait, page))
            }
        }
    }
}

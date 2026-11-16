package cn.paperforge.app

import cn.paperforge.app.data.DerivedNames
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class DerivedNamesTest {
    @Test
    fun suffixesDoNotStack() {
        assertEquals("报告", DerivedNames.base("报告.pdf"))
        assertEquals("报告", DerivedNames.base("报告-已去水印.pdf"))
        assertEquals("报告", DerivedNames.base("报告-已去水印-已编辑.pdf"))
        assertEquals("报告", DerivedNames.base("报告-已编辑 (2).pdf"))
        assertEquals("Report", DerivedNames.base("Report No Watermark.pdf"))
        assertEquals("Report", DerivedNames.base("Report Edited (3) Annotated"))
        assertEquals("Report", DerivedNames.base("Report · 已批注"))
        assertEquals("", DerivedNames.base("已编辑"))
    }

    @Test
    fun ownCountersOnly() {
        // A user's own "(n)" is part of the name; only a counter after one of our suffixes is ours.
        assertEquals("Exam (1)", DerivedNames.base("Exam (1).pdf"))
        assertEquals("Exam (2)", DerivedNames.base("Exam (2)-已去水印 (2).pdf"))
        assertEquals("第(3)章", DerivedNames.base("第(3)章.pdf"))
    }

    @Test
    fun freeFileNeverOverwrites() {
        val dir = Files.createTempDirectory("derived").toFile()
        try {
            val first = File(dir, "a-已编辑.pdf")
            assertEquals(first, DerivedNames.freeFile(first))
            first.writeText("x")
            assertEquals(File(dir, "a-已编辑 (2).pdf"), DerivedNames.freeFile(first))
            File(dir, "a-已编辑 (2).pdf").writeText("y")
            assertEquals(File(dir, "a-已编辑 (3).pdf"), DerivedNames.freeFile(first))
        } finally {
            dir.deleteRecursively()
        }
    }
}

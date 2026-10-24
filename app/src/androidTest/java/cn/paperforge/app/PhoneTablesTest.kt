package cn.paperforge.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.pdf.PhoneTables
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhoneTablesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeAndScannedTablesExportEditableMergedCells() = runBlocking {
        val app = compose.activity.application as PaperForgeApp
        for (variant in listOf("office", "scanned")) {
            val input = File(compose.activity.cacheDir, "phone-$variant.pdf")
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("phone-$variant-table.pdf")
                .use { source -> input.outputStream().use { source.copyTo(it) } }
            val tables = PhoneTables.extract(input, app.ocrEngine, JSONObject()) {}
            val table = tables.first()
            assertEquals("$variant rows", 5, table.rows.size)
            assertEquals("$variant columns", 3, table.rows[0].size)
            assertEquals("$variant identifier", "00123", table.rows[2][1])
            assertEquals("$variant amount", "120.50", table.rows[2][2])
            assertEquals(2, table.cells.count { it.columnEnd > it.column })
            val output = File(compose.activity.filesDir, "phone-table-$variant.xlsx")
            PhoneTables.write(tables, output, false)
            ZipFile(output).use { zip ->
                val xml =
                    zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                        .bufferedReader()
                        .use { it.readText() }
                assertTrue(xml.contains("00123"))
                assertTrue(xml.contains("120.50"))
                assertTrue(xml.contains("mergeCell"))
            }
        }
    }

    @Test
    fun borderlessAndCrossPageTablesRetainStructure() = runBlocking {
        val app = compose.activity.application as PaperForgeApp
        for (kind in listOf("borderless", "multipage")) {
            val input = File(compose.activity.cacheDir, "$kind.pdf")
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("phone-$kind-table.pdf")
                .use { source -> input.outputStream().use { source.copyTo(it) } }
            val tables =
                PhoneTables.extract(input, app.ocrEngine, JSONObject().put("joinTables", true)) {}
            assertTrue(tables.isNotEmpty())
            if (kind == "borderless")
                assertTrue(tables.flatMap { it.rows }.flatten().contains("00123"))
            else {
                assertEquals(1, tables.size)
                assertEquals(5, tables[0].rows.size)
                assertEquals(listOf(1, 2), tables[0].sourcePages)
            }
        }
    }

    @Test
    fun phoneWordAndSlidesContainActualEditableStructures() = runBlocking {
        val app = compose.activity.application as PaperForgeApp
        val input = File(compose.activity.cacheDir, "phone-structure.pdf")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("phone-office-table.pdf")
            .use { source -> input.outputStream().use { source.copyTo(it) } }
        val word = File(compose.activity.filesDir, "phone-structure.docx")
        cn.paperforge.app.pdf.PhoneDocx.convert(input, word, app.ocrEngine)
        ZipFile(word).use { zip ->
            val xml =
                zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use {
                    it.readText()
                }
            assertTrue(xml.contains("<w:tbl>"))
            assertTrue(xml.contains("gridSpan"))
            assertTrue(xml.contains("00123"))
            assertTrue(xml.contains("120.50"))
            assertTrue(zip.entries().asSequence().any { it.name.startsWith("word/media/") })
        }
        val slides = File(compose.activity.filesDir, "phone-slides.pptx")
        cn.paperforge.app.pdf.PhoneSlides.convert(compose.activity, input, slides)
        ZipFile(slides).use { zip ->
            assertNotNull(zip.getEntry("ppt/slides/slide1.xml"))
            assertNotNull(zip.getEntry("ppt/slides/slide2.xml"))
            assertNotNull(zip.getEntry("ppt/media/page2.png"))
        }
    }
}

package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import cn.paperforge.app.pdf.PdfEngine
import cn.paperforge.app.ui.scanFilterName
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 强力去背景 (CLEAN) was merged into 练习册 (WORKBOOK). Documents saved before the merge still carry filter "CLEAN" in
 * their pages, and settings may still hold it: such pages must open, render and export exactly as 练习册 pages do, show
 * the 练习册 label, and the stored choices must move to 练习册 once, without touching anything else.
 */
@RunWith(AndroidJUnit4::class)
class CleanPresetMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    /** A lit notebook page: dark writing, a pale pencil line and faint marks of the other side. */
    private fun photo(): File {
        val file = File(context.cacheDir, "clean-migration.jpg")
        val bitmap = Bitmap.createBitmap(1600, 2000, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.rgb(70, 66, 60))
        val paper = android.graphics.Paint().apply { color = Color.rgb(236, 232, 222) }
        canvas.drawRect(160f, 160f, 1440f, 1840f, paper)
        val ink = android.graphics.Paint().apply { color = Color.rgb(30, 30, 40); textSize = 54f; isAntiAlias = true }
        for (row in 0 until 12) canvas.drawText("Line $row of the saved page 第${row}行", 240f, 300f + row * 120f, ink)
        val pencil = android.graphics.Paint().apply { color = Color.rgb(170, 168, 162); strokeWidth = 3f }
        canvas.drawLine(240f, 1760f, 1300f, 1720f, pencil)
        val ghost = android.graphics.Paint().apply { color = Color.rgb(214, 210, 200); textSize = 54f; isAntiAlias = true }
        canvas.save(); canvas.scale(-1f, 1f, 800f, 0f)
        for (row in 0 until 6) canvas.drawText("the other side 背面", 300f, 360f + row * 240f, ghost)
        canvas.restore()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        return file
    }

    private val quad = Quad.ordered(listOf(PointN(.1f, .08f), PointN(.9f, .08f), PointN(.9f, .92f), PointN(.1f, .92f)))

    private fun page(file: File, filter: String, id: String) =
        PageRow(id = id, documentId = "clean-migration", position = 0, original = file.path, corners = quad.encode(), filter = filter)

    @Test
    fun aSavedCleanPageRendersExactlyAsWorkbook() {
        val file = photo()
        val clean = ScanProcessor.render(page(file, "CLEAN", "clean-old"), 1400)
        val workbook = ScanProcessor.render(page(file, "WORKBOOK", "clean-new"), 1400)
        assertEquals(workbook.width, clean.width)
        assertEquals(workbook.height, clean.height)
        val a = IntArray(clean.width * clean.height).also { clean.getPixels(it, 0, clean.width, 0, 0, clean.width, clean.height) }
        val b = IntArray(workbook.width * workbook.height).also { workbook.getPixels(it, 0, workbook.width, 0, 0, workbook.width, workbook.height) }
        val differing = a.indices.count { a[it] != b[it] }
        assertEquals("a saved 强力去背景 page looks exactly like 练习册", 0, differing)
        assertEquals("练习册", scanFilterName("CLEAN"))
    }

    @Test
    fun aSavedCleanPageExportsLikeWorkbook() = runBlocking {
        val file = photo()
        PDFBoxResourceLoader.init(context)
        fun exported(filter: String): Triple<Int, Int, String> {
            val pdf = File(context.cacheDir, "clean-migration-$filter.pdf")
            runBlocking { PdfEngine.scan(listOf(page(file, filter, "clean-pdf-$filter")), pdf, resolution = "STANDARD") }
            return PDDocument.load(pdf).use { doc ->
                assertEquals(1, doc.numberOfPages)
                val res = doc.getPage(0).resources
                val image = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
                Triple(image.width, image.height, "${image.cosObject.filters} ${image.colorSpace.name}")
            }
        }
        val clean = exported("CLEAN")
        val workbook = exported("WORKBOOK")
        assertEquals("same size and the same encoding as 练习册, no longer a grey JPEG", workbook, clean)
    }

    @Test
    fun storedCleanChoicesMoveToWorkbookOnce() {
        UserSettings.initialize(context)
        val prefs = UserSettings.prefs
        val saved = Triple(prefs.getString("filter", null), prefs.getString("captureMode", null), prefs.getBoolean("cleanMergedV1", false))
        try {
            prefs.edit().putBoolean("cleanMergedV1", false).putString("filter", "CLEAN").putString("captureMode", "clean").commit()
            UserSettings.initialize(context)
            assertEquals("WORKBOOK", prefs.getString("filter", null))
            assertEquals("document", prefs.getString("captureMode", null))
            assertTrue(prefs.getBoolean("cleanMergedV1", false))
            // Once migrated, a later choice is the user's own and is left alone.
            prefs.edit().putString("filter", "GRAY").putString("captureMode", "book").commit()
            UserSettings.initialize(context)
            assertEquals("GRAY", prefs.getString("filter", null))
            assertEquals("book", prefs.getString("captureMode", null))
            // Other choices are not touched by the migration.
            prefs.edit().putBoolean("cleanMergedV1", false).putString("filter", "BW").putString("captureMode", "receipt").commit()
            UserSettings.initialize(context)
            assertEquals("BW", prefs.getString("filter", null))
            assertEquals("receipt", prefs.getString("captureMode", null))
        } finally {
            prefs.edit().putString("filter", saved.first).putString("captureMode", saved.second).putBoolean("cleanMergedV1", saved.third).commit()
            UserSettings.initialize(context)
        }
    }
}

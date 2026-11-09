package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.LibraryPreferences
import cn.paperforge.app.data.NameTemplate
import cn.paperforge.app.data.NamingOptions
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.renameBatch
import cn.paperforge.app.data.undoBatchRename
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NamingAssistanceRecoveryTest {
    @Test
    fun explicitFirstPageRecognitionIsCachedAndNativePdfNeedsNoOcr() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        fun main(block: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        main {
            vm =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    MainViewModel::class.java]
        }
        val scan = DocumentRow(UUID.randomUUID().toString(), "Original scan name")
        val pdfId = UUID.randomUUID().toString()
        val source = File(repo.folder(scan.id), "source.png")
        val pdf = File(repo.folder(pdfId), "source.pdf")
        val native = DocumentRow(pdfId, "Original PDF name", kind = "pdf", pdfPath = pdf.path)
        val preferences = LibraryPreferences(app)
        val oldPreferences = preferences.export()
        try {
            val bitmap = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 65f
                }
            listOf("VECTOR ALGEBRA", "CHAPTER TWO", "2026-11-01").forEachIndexed { i, text ->
                canvas.drawText(text, 65f, 180f + i * 140f, paint)
            }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            repo.dao.putDocument(scan)
            val page =
                PageRow(UUID.randomUUID().toString(), scan.id, 0, source.path, filter = "ORIGINAL")
            repo.dao.putPage(page)
            assertNull(vm.namingSuggestion(scan))
            assertNotNull(vm.namingSuggestion(scan, recognizeMissing = true))
            val recognized = repo.dao.pages(scan.id).single()
            assertTrue(recognized.ocrText.orEmpty().contains("VECTOR", ignoreCase = true))
            assertEquals(scan.title, repo.dao.document(scan.id)!!.title)
            vm.namingSuggestion(scan, recognizeMissing = true)
            assertEquals(recognized, repo.dao.pages(scan.id).single())
            PDDocument().use { doc ->
                val sheet = PDPage()
                doc.addPage(sheet)
                PDPageContentStream(doc, sheet).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 24f)
                    content.newLineAtOffset(50f, 700f)
                    content.showText("INVOICE 2026-11-01 Total $42.50")
                    content.endText()
                }
                doc.save(pdf)
            }
            repo.dao.putDocument(native)
            val suggestion = vm.namingSuggestion(native)
            assertNotNull(suggestion)
            assertTrue(suggestion!!.evidence.any { it.contains("42.50") })
            assertEquals(native.title, repo.dao.document(native.id)!!.title)
            val previews =
                NameTemplate.previewNames(
                    listOf(scan, native),
                    listOf(scan, native),
                    mapOf(scan.id to "Reviewed name", native.id to "Reviewed name"),
                )
            assertEquals(listOf("Reviewed name", "Reviewed name (2)"), previews.map { it.after })
            assertTrue(previews[1].collision)
            repo.renameBatch(previews, NamingOptions())
            assertEquals("Reviewed name", repo.dao.document(scan.id)!!.title)
            assertEquals(2, repo.undoBatchRename().restored)
            assertEquals(scan.title, repo.dao.document(scan.id)!!.title)
            assertEquals(native.title, repo.dao.document(native.id)!!.title)
        } finally {
            repo.deleteDocument(scan)
            repo.deleteDocument(native)
            preferences.restore(oldPreferences)
            main { store.clear() }
        }
    }
}

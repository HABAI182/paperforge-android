package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderConversionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun model() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @Test
    fun readerSwipesFullscreensBookmarksAndRestoresProgress() {
        val vm = model()
        val file = File(vm.repo.exports, "reader-v060-${System.currentTimeMillis()}.pdf")
        PDDocument().use { pdf ->
            repeat(3) { pdf.addPage(PDPage(PDRectangle.A4)) }
            pdf.save(file)
        }
        val doc = runBlocking { vm.repo.registerPdf(file, "下一代阅读验收") }
        try {
            compose.runOnIdle { vm.open(doc) }
            compose.waitUntil(12000) {
                compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("第 1 页").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("收藏此页").performClick()
            compose.onNodeWithText("书签 1").assertIsDisplayed()
            compose.onNodeWithText("连续滚动").performClick()
            compose.onNodeWithText("暖纸").performClick()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithText("2 / 3").assertIsDisplayed()
            compose.onNodeWithContentDescription("全屏").performClick()
            compose.onNodeWithContentDescription("PDF 工具").assertDoesNotExist()
            compose.onNodeWithContentDescription("第 2 页").assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
            compose.runOnIdle { vm.screen.value = "home" }
            compose.waitForIdle()
            val saved = ReaderPreferences(compose.activity).load(doc.id)
            assertEquals(1, saved.page)
            assertFalse(saved.horizontal)
            assertEquals(1, saved.tone)
            assertTrue(ReaderPreferences(compose.activity).bookmarks(doc.id).contains(1))
            compose.runOnIdle { vm.open(doc) }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
            }
            val current = runBlocking { vm.repo.dao.document(doc.id)!! }
            assertTrue(current.lastOpened > 0)
            assertEquals(doc.modified, current.modified)
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("左右翻页").performClick()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithText("2 / 3").assertIsDisplayed()
            compose.onNodeWithContentDescription("第 2 页").performTouchInput { doubleClick() }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("恢复缩放").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("恢复缩放").performClick()
        } finally {
            compose.runOnIdle { vm.screen.value = "home" }
            compose.waitForIdle()
            runBlocking { vm.repo.deleteDocument(doc) }
        }
    }

    @Test
    fun localConversionsProduceRealTextAndImageFiles() = runBlocking {
        val context = compose.activity
        val directory = File(context.cacheDir, "conversion-test").apply { mkdirs() }
        val input =
            File(directory, "中文.txt").apply {
                writeText("纸匠转换验收\nInvoice 00123\n" + "中英文混排自动换行。".repeat(600))
            }
        val pdf = File(directory, "text.pdf")
        LocalConversions.textToPdf(context, Uri.fromFile(input), input.name, pdf)
        PDDocument.load(pdf).use { assertTrue(it.numberOfPages > 1) }
        val text = PdfEngine.text(pdf)
        assertTrue(text.contains("纸匠转换验收"))
        assertTrue(text.contains("00123"))
        val image = File(directory, "alpha.png")
        Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.TRANSPARENT)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val jpg = File(directory, "white.jpg")
        LocalConversions.images(context, listOf(Uri.fromFile(image)), "jpg", jpg) {}
        BitmapFactory.decodeFile(jpg.path).also { bitmap ->
            assertEquals(80, bitmap.width)
            assertTrue(Color.red(bitmap.getPixel(0, 0)) > 245)
            bitmap.recycle()
        }
        val batch = File(directory, "batch.zip")
        LocalConversions.images(
            context,
            listOf(Uri.fromFile(image), Uri.fromFile(image)),
            "webp",
            batch,
        ) {}
        ZipFile(batch).use { zip ->
            assertEquals(2, zip.size())
            assertNotNull(zip.getEntry("image-001.webp"))
        }
        val job = File(context.filesDir, "desktop-test-task.zip")
        DesktopTasks.create(context, Uri.fromFile(pdf), "text.pdf", "pdf-docx", job)
        ZipFile(job).use {
            assertNotNull(it.getEntry("task.json"))
            assertNotNull(it.getEntry("input.pdf"))
        }
    }

    @Test
    fun desktopResultImportAndSegmentedImagesAreReadable() = runBlocking {
        val context = compose.activity
        val directory = File(context.cacheDir, "advanced-conversion").apply { mkdirs() }
        val input = File(directory, "pages.pdf")
        PDDocument().use { pdf ->
            repeat(7) { index ->
                val page = PDPage(PDRectangle.A4)
                pdf.addPage(page)
                if (index == 6)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.setNonStrokingColor(200, 20, 20)
                        stream.addRect(0f, 0f, page.mediaBox.width, page.mediaBox.height)
                        stream.fill()
                    }
            }
            pdf.save(input)
        }
        val long = File(directory, "long.zip")
        PdfLongImages.convert(input, long)
        ZipFile(long).use { zip ->
            val pages = zip.entries().asSequence().filter { it.name.endsWith(".png") }.toList()
            assertEquals(2, pages.size)
            val bitmap = zip.getInputStream(pages.last()).use { BitmapFactory.decodeStream(it) }
            assertEquals(960, bitmap.width)
            assertTrue(bitmap.height <= 8192)
            val pixel = bitmap.getPixel(480, bitmap.height - 2)
            assertTrue(Color.red(pixel) > 150 && Color.green(pixel) < 60)
            bitmap.recycle()
        }
        val webp = File(directory, "pages-webp.zip")
        PdfTools.images(input, webp, "7", "webp", 800)
        ZipFile(webp).use { zip ->
            val entry = zip.getEntry("page-0007.webp")
            assertNotNull(entry)
            val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
            assertNotNull(bitmap)
            bitmap.recycle()
        }
        val result = File(directory, "desktop-result.zip")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("desktop-result.zip")
            .use { source -> result.outputStream().use { source.copyTo(it) } }
        val files = DesktopTasks.import(context, Uri.fromFile(result), directory)
        assertEquals(1, files.size)
        assertEquals("docx", files.first().extension)
        ZipFile(files.first()).use { zip ->
            val xml =
                zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use {
                    it.readText()
                }
            assertTrue(xml.contains("纸匠转换验收"))
            assertTrue(xml.contains("00123"))
        }
    }
}

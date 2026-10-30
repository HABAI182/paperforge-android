package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.pdf.PdfImageEnhancement
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PdfEnhancementUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun readerToolScopesAndBeforeAfterPreview() {
        val app = compose.activity.application as PaperForgeApp
        val file = File(app.cacheDir, "enhancement-ui.pdf")
        PDDocument().use { doc ->
            val b = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.LTGRAY)
            val image = LosslessFactory.createFromImage(doc, b)
            b.recycle()
            repeat(2) {
                val p = PDPage(PDRectangle(300f, 400f))
                doc.addPage(p)
                PDPageContentStream(doc, p).use { it.drawImage(image, 0f, 0f, 300f, 400f) }
            }
            doc.save(file)
        }
        val row = runBlocking { app.repository.registerPdf(file, "Enhancement UI", listed = false) }
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.waitUntil(15000) { !model.busy.value }
            compose.runOnIdle { model.open(row) }
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("工具").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("工具").performClick()
            compose.onNodeWithText("搜索工具，如水印、Word、页码").performTextInput("图像增强")
            compose.onNodeWithText("图像增强 · 扫描 PDF 二次增强").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("共 2 页 · 选择 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            PdfImageEnhancement.presets.values.forEach { compose.onNodeWithText(it).assertExists() }
            compose.onNodeWithText("选页").performClick()
            compose.onNodeWithText("页码，如 1-3,5").performTextInput("2")
            compose.onNodeWithText("预览第 2 页对比").performScrollTo().performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("增强后预览").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("增强后预览").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("原图").performScrollTo().performClick()
            compose.onNodeWithContentDescription("原图预览").assertExists()
            compose.onNodeWithText("整篇").performScrollTo().performClick()
            compose.onNodeWithText("共 2 页 · 选择 2 页").assertExists()
            compose.onNodeWithText("取消").performClick()
            listOf("视图", "页面", "批注", "工具").forEach {
                compose.onNodeWithContentDescription(it).assertExists()
            }
        } finally {
            compose.runOnIdle { model.closeDocument() }
            runBlocking { app.repository.dao.deleteDocument(row.id) }
            file.delete()
        }
    }
}

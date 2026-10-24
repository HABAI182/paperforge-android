package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.reader.ReaderViewOptions
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderDisplayTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun nightPhotosAndDisplayOnlyTrimsPersistWithoutChangingThePdf() {
        val app = compose.activity.application as PaperForgeApp
        val repo = app.repository
        val file = File(repo.exports, "reader-display-${System.nanoTime()}.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(600f, 800f))
            pdf.addPage(page)
            val bitmap =
                Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(210, 40, 60))
                }
            PDPageContentStream(pdf, page).use {
                it.drawImage(LosslessFactory.createFromImage(pdf, bitmap), 150f, 300f, 300f, 200f)
            }
            bitmap.recycle()
            pdf.save(file)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val doc = runBlocking { repo.registerPdf(file, "Display test") }
        val prefs = ReaderPreferences(app)
        prefs.save(doc.id, ReaderPosition(tone = 2))
        prefs.view(doc.id, ReaderViewOptions(preserveImageColor = false))
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            fun center() =
                compose.onNodeWithTag("reader-viewport-0").captureToImage().let {
                    it.toPixelMap()[it.width / 2, it.height / 2]
                }
            assertTrue(center().green > .6f)
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithContentDescription("图片保留原色").performScrollTo().performClick()
            compose.onNodeWithText("显示裁边", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("全篇").performScrollTo().performClick()
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .onFirst()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.1f) }
            compose.onNodeWithText("适宽").performScrollTo().performClick()
            compose.onNodeWithText("完成").performClick()
            compose.waitUntil(10000) { center().red > .65f && center().green < .3f }
            val view = prefs.view(doc.id)
            assertTrue(view.fitWidth && view.preserveImageColor)
            assertEquals(.1f, view.insets(0).left, .001f)
            assertEquals(.1f, view.insets(5).left, .001f)
            val exported = prefs.export(listOf(doc.id))
            prefs.restore(exported, mapOf(doc.id to "display-remapped"))
            assertEquals(view, prefs.view("display-remapped"))
            assertArrayEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            compose.runOnIdle { model.screen.value = "home" }
            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(center().red > .65f)
        } finally {
            compose.runOnIdle { model.screen.value = "home" }
            runBlocking { repo.deleteDocument(doc) }
        }
    }
}

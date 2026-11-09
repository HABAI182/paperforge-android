package cn.paperforge.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The page's text in place with a switch back to the scan, and the overview of all pages. */
class ReaderTextViewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    @Test
    fun textInPlaceSwitchesBackAndOverviewShowsEveryPage() {
        val proof = File(app.filesDir, "reader-text-proof").apply { mkdirs() }
        val source = File(proof, "source.pdf")
        PDDocument().use { pdf ->
            repeat(2) { number ->
                val page = PDPage(PDRectangle(400f, 600f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { c ->
                    fun line(size: Float, x: Float, y: Float, text: String) {
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA, size)
                        c.newLineAtOffset(x, y)
                        c.showText(text)
                        c.endText()
                    }
                    line(22f, 40f, 520f, "Page ${number + 1} heading")
                    line(12f, 40f, 480f, "First line of small text on this page")
                    line(12f, 40f, 460f, "Second line, placed where it was found")
                }
            }
            pdf.save(source)
        }
        val doc = runBlocking { app.repository.registerPdf(source, "文字视图验收", listed = false) }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        fun waitTag(tag: String) =
            compose.waitUntil(30000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        fun screenshot(name: String) {
            compose.waitForIdle()
            Thread.sleep(800)
            // From the device, so a sheet shows as it really does, on top of the page.
            val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(proof, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
        screenshot("home")
        compose.runOnIdle { vm.open(doc) }
        waitTag("reader-viewport-0")
        screenshot("original")
        compose.onNodeWithText("文字").performClick()
        waitTag("reader-page-text-0")
        screenshot("text-in-place")
        compose.onNodeWithText("原图").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reader-page-text-0").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("全文").performClick()
        waitTag("reader-all-text")
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("Page 2 heading", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("overview")
        assertTrue(
            compose.onAllNodesWithText("Page 1 heading", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }
}

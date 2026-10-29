package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class FoldingWorkspaceFlowTest {
    private val window = WindowLayoutInfoPublisherRule()
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(window).around(compose)

    @Test
    fun separatingVerticalAndTabletopHingesKeepActualReaderPage() {
        val app = compose.activity.application as PaperForgeApp
        val proof = File(app.filesDir, "folding-workspace-proof").apply { mkdirs() }
        val input = File(proof, "source.pdf")
        PDDocument().use { pdf ->
            repeat(3) { index ->
                val page = PDPage(PDRectangle(500f, 700f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(PDType1Font.HELVETICA, 24f)
                    out.newLineAtOffset(60f, 570f)
                    out.showText("FOLD PAGE ${index + 1}")
                    out.endText()
                }
            }
            pdf.save(input)
        }
        val hash = runBlocking { PdfAssembly.sha256(input) }
        val doc = runBlocking {
            app.repository.registerPdf(input, "Fold geometry acceptance", listed = false)
        }
        ReaderPreferences(app).save(doc.id, ReaderPosition(page = 1))
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle { vm.open(doc) }
        fun waitPage() =
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag("reader-viewport-1").fetchSemanticsNodes().isNotEmpty()
            }
        waitPage()
        val size = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val width = size.width.toInt()
        val height = size.height.toInt()
        assertTrue("Use the wide emulator for this flow", width > 1800)
        val records = JSONArray()
        fun publish(name: String, rect: Rect, vertical: Boolean, separating: Boolean = true) {
            val feature =
                object : FoldingFeature {
                    override val bounds = rect
                    override val state =
                        if (separating) FoldingFeature.State.HALF_OPENED
                        else FoldingFeature.State.FLAT
                    override val orientation =
                        if (vertical) FoldingFeature.Orientation.VERTICAL
                        else FoldingFeature.Orientation.HORIZONTAL
                    override val isSeparating = separating
                    override val occlusionType =
                        if (rect.width() > 0 && rect.height() > 0) FoldingFeature.OcclusionType.FULL
                        else FoldingFeature.OcclusionType.NONE
                }
            window.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(feature)))
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag("workspace-document-pane")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            waitPage()
            compose.waitForIdle()
            val pane =
                compose.onNodeWithTag("workspace-document-pane").fetchSemanticsNode().boundsInRoot
            if (vertical) assertTrue(pane.left >= rect.right || pane.right <= rect.left)
            else assertTrue(pane.top >= rect.bottom || pane.bottom <= rect.top)
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File(proof, "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            records.put(
                JSONObject()
                    .put("name", name)
                    .put("hinge", rect.flattenToString())
                    .put("documentPane", pane.toString())
                    .put("page", 2)
            )
        }
        publish("vertical", Rect(width / 3, 0, width / 3 + 32, height), true)
        publish("tabletop", Rect(0, height / 3, width, height / 3 + 28), false)
        publish("zero-width-crease", Rect(width / 3, 0, width / 3, height), true)
        window.overrideWindowLayoutInfo(WindowLayoutInfo(emptyList()))
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("workspace-document-pane").fetchSemanticsNodes().isEmpty()
        }
        waitPage()
        assertEquals(hash, runBlocking { PdfAssembly.sha256(input) })
        File(proof, "proof.json")
            .writeText(
                JSONObject()
                    .put("sourceSha256", hash)
                    .put("sourceUnchanged", true)
                    .put("actualReader", true)
                    .put("pageAfterUnfold", 2)
                    .put("injectedWindowManagerEvents", records)
                    .put("physicalFoldable", false)
                    .toString(2)
            )
    }
}

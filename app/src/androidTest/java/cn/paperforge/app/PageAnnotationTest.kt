package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.annotation.PageAnnotations
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.AnnotationEditor
import cn.paperforge.app.model.PageAnnotation
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.PdfEngine
import cn.paperforge.app.ui.CapturePagePreview
import cn.paperforge.app.ui.PaperForgeTheme
import cn.paperforge.app.ui.ScanAnnotationScreen
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests for the v200-feedback batch (2026-11-04): swipeable capture preview and the baked
 * annotation layer.
 *
 * These exercise the real Compose screens and the real save/export code paths on
 * `paperforge_api35`. They do not re-assert the older CaptureReview assertions; the legacy
 * single-page entry is still covered by CaptureReviewFeedbackTest.
 */
@RunWith(AndroidJUnit4::class)
class PageAnnotationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app: PaperForgeApp
        get() = ApplicationProvider.getApplicationContext()

    private val output: File
        get() = File(app.filesDir, "annotation-tests").apply { mkdirs() }

    private fun screenshot(name: String) {
        // Full-screen capture instead of onRoot(): the preview sheet is a second window/root, so
        // "the root" is ambiguous there. uiAutomation captures exactly what the device shows.
        val bitmap =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
            ?: error("device screenshot unavailable")
        try {
            File(output, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * White page with a dense thin-bar pattern inside the mosaic box, so pixelating that box
     * measurably lowers local edge energy instead of averaging a flat colour.
     */
    private fun fixture(width: Int = 900, height: Int = 1200): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 24) }
        val top = (height * .30f).toInt()
        val bottom = (height * .40f).toInt()
        var x = (width * .30f).toInt()
        while (x < width * .70f) {
            canvas.drawRect(
                x.toFloat(),
                top.toFloat(),
                (x + 2).toFloat(),
                bottom.toFloat(),
                bars,
            )
            x += 8
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            textSize = height * .022f
        }
        repeat(8) { line ->
            canvas.drawText(
                "paperforge annotation source ${line + 1}",
                width * .1f,
                height * (.55f + line * .035f),
                body,
            )
        }
        return bitmap
    }

    private fun newFixturePage(vm: MainViewModel, name: String): Pair<PageRow, File> =
        runBlocking {
            val document = vm.repo.newScan()
            val original = File(vm.repo.folder(document.id), "$name.png")
            val bitmap = fixture()
            try {
                original.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
            val page =
                PageRow(
                    UUID.randomUUID().toString(),
                    document.id,
                    0,
                    original.path,
                    filter = "ORIGINAL",
                )
            vm.repo.dao.putPage(page)
            page to original
        }

    private fun previewPages(vm: MainViewModel): List<PageRow> = runBlocking {
        val document = vm.repo.newScan()
        (0 until 3).map { index ->
            val file = File(vm.repo.folder(document.id), "preview-$index.png")
            val bitmap = fixture(600, 800)
            try {
                file.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
            PageRow(
                    UUID.randomUUID().toString(),
                    document.id,
                    index,
                    file.path,
                    filter = "ORIGINAL",
                )
                .also { vm.repo.dao.putPage(it) }
        }
    }

    // ------------------------------------------------------- A: preview pager

    @Test
    fun capturePreviewSwipesPagesAndActsOnTheVisiblePage() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val pages = previewPages(vm)
        var open by mutableStateOf(true)
        var modified: String? = null
        var highDefinition: String? = null
        var retaken: String? = null
        compose.activity.setContent {
            PaperForgeTheme {
                if (open)
                    CapturePagePreview(
                        pages = pages,
                        startNumber = 1,
                        initialPage = 0,
                        onDismiss = { open = false },
                        onModify = { modified = it.id },
                        onRetake = { retaken = it.id },
                        onUndo = {},
                        onHd = { highDefinition = it.id },
                    )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("第 1 页").assertIsDisplayed()
        compose.onNodeWithTag("capture-preview-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(4000) {
            compose.onAllNodesWithText("第 2 页").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("preview-pager-page-2.png")
        compose.onNodeWithText("修改").performClick()
        compose.runOnIdle { assertEquals(pages[1].id, modified) }
        compose.onNodeWithText("AI 高清").performClick()
        compose.runOnIdle { assertEquals(pages[1].id, highDefinition) }
        compose.onNodeWithText("重拍").performClick()
        compose.runOnIdle { assertEquals(pages[1].id, retaken) }
        // Walk back to the first page, then push past the edge: a hint must appear and the page
        // must stay on page 1.
        compose.onNodeWithTag("capture-preview-pager").performTouchInput { swipeRight() }
        compose.waitUntil(4000) {
            compose.onAllNodesWithText("第 1 页").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("capture-preview-pager").performTouchInput { swipeRight() }
        compose.waitUntil(2000) {
            compose.onAllNodesWithText("已是第一页").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("第 1 页").assertIsDisplayed()
        screenshot("preview-pager-edge.png")
    }

    // ---------------------------------------- B: draw / undo / redo / bake / save

    @Test
    fun annotationStrokeUndoRedoAndBakeKeepsOriginalAndHistory() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val (page, original) = newFixturePage(vm, "annotation-source")
        val beforeSha = sha256(original)
        val before = runBlocking { vm.repo.dao.pages(page.documentId).first() }
        val editor = AnnotationEditor()
        val sample = PageAnnotation("pen", listOf(PointN(.2f, .2f), PointN(.45f, .3f)))
        editor.add(sample)
        assertTrue(editor.canUndo)
        assertTrue("eraser did not hit the stroke", editor.idsAt(PointN(.32f, .25f)).isNotEmpty())
        editor.removeIds(editor.idsAt(PointN(.32f, .25f)))
        assertTrue("eraser left the stroke behind", editor.annotations.isEmpty())
        assertTrue(editor.undo())
        assertEquals(1, editor.annotations.size)
        assertTrue(editor.redo())
        assertTrue(editor.annotations.isEmpty())

        val saved = AtomicReference<List<PageAnnotation>?>(null)
        compose.activity.setContent {
            PaperForgeTheme {
                ScanAnnotationScreen(
                    page = page,
                    onBack = {},
                    onSave = { saved.set(it) },
                )
            }
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("annotation-canvas").fetchSemanticsNodes().isNotEmpty()
        }
        val canvas = compose.onNodeWithTag("annotation-canvas").fetchSemanticsNode()
        val width = canvas.size.width.toFloat()
        val height = canvas.size.height.toFloat()
        // One pen stroke, then undo and redo through the real toolbar.
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            swipe(
                Offset(width * .10f, height * .10f),
                Offset(width * .45f, height * .22f),
                350,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("annotate-undo").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("annotate-redo").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("annotate-undo").assertIsEnabled()

        // Mosaic over the dense bar pattern.
        compose.onNodeWithTag("annotate-tool-mosaic").performClick()
        compose.onNodeWithTag("annotate-style-mosaic").assertIsDisplayed()
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            swipe(
                Offset(width * .28f, height * .28f),
                Offset(width * .72f, height * .42f),
                400,
            )
        }
        compose.waitForIdle()
        // Solid black bar, dragged thinner than the configured thickness.
        compose.onNodeWithTag("annotate-style-redact").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            swipe(
                Offset(width * .20f, height * .60f),
                Offset(width * .80f, height * .61f),
                400,
            )
        }
        compose.waitForIdle()
        // Text annotation: tap, type, confirm.
        compose.onNodeWithTag("annotate-tool-text").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            click(Offset(width * .12f, height * .72f))
        }
        compose.waitUntil(4000) {
            compose.onAllNodesWithTag("annotate-text-field").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("annotate-text-field").performClick()
        compose.onNodeWithTag("annotate-text-field").performTextInput("重点复习")
        compose.onNodeWithText("添加").performClick()
        compose.waitUntil(4000) {
            compose.onAllNodesWithTag("annotate-text-field").fetchSemanticsNodes().isEmpty()
        }
        val keyboard =
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        compose.runOnIdle {
            keyboard?.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        Thread.sleep(350)
        // Eraser: wipe the text annotation, then undo it — the text must come back for the save.
        compose.onNodeWithTag("annotate-tool-eraser").performClick()
        val afterKeyboard = compose.onNodeWithTag("annotation-canvas").fetchSemanticsNode()
        val ew = afterKeyboard.size.width.toFloat()
        val eh = afterKeyboard.size.height.toFloat()
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            swipe(
                Offset(ew * .10f, eh * .73f),
                Offset(ew * .30f, eh * .73f),
                300,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("annotate-undo").assertIsEnabled().performClick()
        compose.waitForIdle()
        screenshot("annotation-tools.png")
        compose.onNodeWithTag("annotate-save").performClick()
        compose.waitUntil(4000) { saved.get() != null }
        val annotations = saved.get()!!
        val kinds = annotations.map { it.kind }.toSet()
        assertTrue("pen missing: $kinds", "pen" in kinds)
        assertTrue("mosaic missing: $kinds", "mosaic" in kinds)
        assertTrue("redact missing: $kinds", "redact" in kinds)
        assertTrue("text missing: $kinds", "text" in kinds)

        compose.runOnIdle { vm.saveAnnotations(page, annotations) }
        compose.waitUntil(60000) {
            runBlocking {
                val latest = vm.repo.dao.pages(page.documentId).first()
                latest.original != original.path && !vm.busy.value
            }
        }
        val updated = runBlocking { vm.repo.dao.pages(page.documentId).first() }
        assertEquals("page revision must advance", before.revision + 1, updated.revision)
        assertNotEquals("original path must not be overwritten", original.path, updated.original)
        assertTrue(File(updated.original).isFile)
        assertEquals("camera original changed", beforeSha, sha256(original))
        val metadata = JSONObject(updated.processingMetadata)
        assertEquals(annotations.size, metadata.getJSONObject("pageAnnotations").getInt("count"))
        assertFalse(metadata.has("flatDocument"))

        val baked = android.graphics.BitmapFactory.decodeFile(updated.original)
            ?: error("baked page unreadable")
        val rendered = ScanProcessor.render(page, PageAnnotations.BAKE_EDGE)
        try {
            // Mosaic: local edge energy inside the box drops sharply versus the unannotated render.
            val mosaicBox = floatArrayOf(.28f, .28f, .72f, .42f)
            val beforeEnergy = edgeEnergy(rendered, mosaicBox)
            val afterEnergy = edgeEnergy(baked, mosaicBox)
            assertTrue(
                "mosaic did not cover the pattern: $beforeEnergy -> $afterEnergy",
                afterEnergy < 12f && afterEnergy < beforeEnergy * .55f,
            )
            // Black bar: all sampled pixels are solid black.
            val redactBlack = blackShare(baked, floatArrayOf(.20f, .593f, .80f, .617f))
            assertTrue("black bar not solid: $redactBlack", redactBlack > .95f)
            // Text annotation is visible in the baked pixels.
            assertTrue(
                "text annotation missing from the baked page",
                darkCount(baked, floatArrayOf(.10f, .70f, .60f, .80f)) > 20,
            )
        } finally {
            rendered.recycle()
            baked.recycle()
        }
        // Re-opening the page shows the annotations because they are in the page pixels now.
        val reopened = ScanProcessor.render(updated, 1600)
        try {
            assertTrue(
                "text annotation vanished on re-open",
                darkCount(reopened, floatArrayOf(.10f, .68f, .60f, .82f)) > 5,
            )
        } finally {
            reopened.recycle()
        }
        android.graphics.BitmapFactory.decodeFile(updated.original).let { bitmap ->
            try {
                File(output, "annotated-page.png").outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            } finally {
                bitmap.recycle()
            }
        }

        // History: undo restores the camera original, redo restores the annotated version.
        runBlocking { vm.repo.undo(page.documentId) }
        val undone = runBlocking { vm.repo.dao.pages(page.documentId).first() }
        assertEquals("undo did not restore the original page", original.path, undone.original)
        assertEquals("undo must not touch the camera file", beforeSha, sha256(original))
        runBlocking { vm.repo.undo(page.documentId, true) }
        val redone = runBlocking { vm.repo.dao.pages(page.documentId).first() }
        assertEquals("redo did not restore the annotated page", updated.original, redone.original)
    }

    // ------------------------------------------- C: export embeds baked annotations

    @Test
    fun exportedPdfContainsBakedAnnotations() = runBlocking {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val (page, original) = newFixturePage(vm, "annotation-export")
        val annotations =
            listOf(
                PageAnnotation(
                    "mosaic",
                    listOf(PointN(.28f, .28f), PointN(.72f, .42f)),
                    width = .035f,
                ),
                PageAnnotation(
                    "redact",
                    listOf(PointN(.20f, .60f), PointN(.80f, .61f)),
                    width = .035f,
                ),
            )
        val baked = PageAnnotations.bake(page, annotations)
        val file = try {
            PageAnnotations.write(baked, vm.repo.folder(page.documentId))
        } finally {
            baked.recycle()
        }
        vm.repo.savePage(
            page.copy(
                original = file.path,
                corners = cn.paperforge.app.model.Quad.Full.encode(),
                filter = "ORIGINAL",
                brightness = 0f,
                contrast = 1f,
                rotation = 0,
                deskewDegrees = 0f,
                outputAspect = 0f,
                detectionMethod = "annotation",
                processingMetadata = PageAnnotations.metadata(page, annotations),
            )
        )
        val saved = vm.repo.dao.pages(page.documentId).first()
        assertNotEquals("export test edited the original file", original.path, saved.original)
        val pdf = File(vm.repo.exports, "annotation-export-${System.nanoTime()}.pdf")
        PdfEngine.scan(listOf(saved), pdf, paper = "A4", quality = 95)
        try {
            PDDocument.load(pdf).use { document ->
                assertEquals(1, document.numberOfPages)
                val resources = document.getPage(0).resources
                val images =
                    resources.xObjectNames.mapNotNull {
                        resources.getXObject(it) as? PDImageXObject
                    }
                assertTrue("PDF has no embedded page image", images.isNotEmpty())
                val image = images.first().image
                try {
                    val redactBlack = blackShare(image, floatArrayOf(.20f, .593f, .80f, .617f))
                    assertTrue(
                        "exported PDF lost the black bar: $redactBlack",
                        redactBlack > .90f,
                    )
                    val mosaicEnergy = edgeEnergy(image, floatArrayOf(.28f, .28f, .72f, .42f))
                    assertTrue(
                        "exported PDF lost the mosaic blocks: energy $mosaicEnergy",
                        mosaicEnergy < 16f,
                    )
                    File(output, "pdf-embedded-page.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    image.recycle()
                }
            }
        } finally {
            pdf.delete()
        }
        Unit
    }

    // ------------------------------------------------------------- pixel probes

    /** Mean |luma difference| between pixels two apart: flat mosaic blocks score far below bars. */
    private fun edgeEnergy(bitmap: Bitmap, box: FloatArray): Float {
        val left = (box[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 3)
        val top = (box[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 3)
        val right = (box[2] * bitmap.width).toInt().coerceIn(left + 2, bitmap.width - 1)
        val bottom = (box[3] * bitmap.height).toInt().coerceIn(top + 2, bitmap.height - 1)
        var sum = 0.0
        var count = 0
        val stepX = ((right - left) / 80).coerceAtLeast(1)
        val stepY = ((bottom - top) / 12).coerceAtLeast(1)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right - 2) {
                val a = luma(bitmap.getPixel(x, y))
                val b = luma(bitmap.getPixel(x + 2, y))
                sum += kotlin.math.abs(a - b)
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun luma(pixel: Int): Double =
        .299 * Color.red(pixel) + .587 * Color.green(pixel) + .114 * Color.blue(pixel)

    private fun blackShare(bitmap: Bitmap, box: FloatArray): Float {
        val left = (box[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (box[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (box[2] * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box[3] * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        var black = 0
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (luma(bitmap.getPixel(x, y)) < 40) black++
                count++
                x += 3
            }
            y += 3
        }
        return if (count == 0) 0f else black.toFloat() / count
    }

    private fun darkCount(bitmap: Bitmap, box: FloatArray): Int {
        val left = (box[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (box[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (box[2] * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box[3] * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        var dark = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (luma(bitmap.getPixel(x, y)) < 120) dark++
                x += 2
            }
            y += 2
        }
        return dark
    }
}

package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Mat

class ScanCoreWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PaperForgeApp

    private fun fixture(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open("scan-core/$name").use {
            BitmapFactory.decodeStream(it)!!
        }

    @Test
    fun nativeModelsFindRepresentativePaperEdgesAndZeroStrengthPreservesPixels() {
        val results = JSONArray()
        for (name in listOf("dark-desk-0.jpg", "white-desk-0.jpg", "clutter-0.jpg")) {
            val image = fixture(name)
            val mat = Mat()
            try {
                Utils.bitmapToMat(image, mat)
                val detection = NeuralEdgeDetector.detect(mat, preview = true)
                assertNotNull("$name: edge network must produce paper corners", detection.quad)
                val q = detection.quad!!
                val expected =
                    Quad(
                        listOf(
                            PointN(.2425f, .08f),
                            PointN(.76f, .09666667f),
                            PointN(.84f, .91f),
                            PointN(.18f, .98333335f),
                        )
                    )
                assertTrue(
                    "$name: wrong rectangle ${q.encode()}",
                    q.points.zip(expected.points).maxOf { (a, b) ->
                        kotlin.math.hypot(a.x - b.x, a.y - b.y)
                    } < .07f,
                )
                results.put(
                    JSONObject()
                        .put("image", name)
                        .put("method", detection.method)
                        .put("ms", detection.elapsedMs)
                        .put("corners", q.encode())
                )
            } finally {
                mat.release()
                image.recycle()
            }
        }
        val image = fixture("dark-desk-0.jpg")
        val scaled = Bitmap.createScaledBitmap(image, 320, 240, true)
        image.recycle()
        try {
            val corrected = PageDewarp.apply(scaled, 0f)
            try {
                assertTrue("Zero strength must leave the image unchanged", scaled.sameAs(corrected))
            } finally {
                corrected.recycle()
            }
        } finally {
            scaled.recycle()
        }
        File(app.filesDir, "scan-core-results.json").writeText(results.toString(2))
    }

    @Test
    fun curvePreviewApplyUndoRedoRetainOriginalAndInvalidateOcr() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val repo = app.repository
        val detector = UserSettings.string("detector", "NEURAL")
        UserSettings.set("detector", "NEURAL")
        val doc = runBlocking { repo.newScan() }
        val file = repo.captureFile(doc.id)
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("scan-core/dark-desk-0.jpg")
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            val page = runBlocking {
                repo.addCapture(doc.id, file).also {
                    repo.dao.updatePage(it.copy(ocrText = "stale text"))
                }
            }
            assertTrue(
                "New default model must actually be used",
                page.detectionMethod!!.startsWith("neural-edge"),
            )
            compose.runOnIdle { model.open(doc) }
            compose.waitUntil(10000) { model.pages.value.isNotEmpty() }
            compose.onNodeWithContentDescription("曲面校正").performClick()
            compose.waitUntil(20000) {
                runCatching { compose.onNodeWithText("应用").assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithContentDescription("曲面校正预览").assertIsDisplayed()
            val screenshot =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir, "scan-core-preview.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("应用").performClick()
            compose.waitUntil(20000) {
                !model.busy.value && model.pages.value.firstOrNull()?.original != file.path
            }
            val corrected = runBlocking { repo.dao.pages(doc.id).first() }
            assertTrue(File(corrected.original).exists())
            assertTrue(file.exists())
            assertEquals(Quad.Full.encode(), corrected.corners)
            assertNull(corrected.ocrText)
            compose.onNodeWithContentDescription("撤销").performClick()
            compose.waitUntil(10000) {
                !model.busy.value && model.pages.value.first().original == file.path
            }
            assertNull(runBlocking { repo.dao.pages(doc.id).first().ocrText })
            compose.onNodeWithContentDescription("重做").performClick()
            compose.waitUntil(10000) {
                !model.busy.value && model.pages.value.first().original == corrected.original
            }
        } finally {
            runBlocking { repo.deleteDocument(doc) }
            UserSettings.set("detector", detector)
        }
    }
}

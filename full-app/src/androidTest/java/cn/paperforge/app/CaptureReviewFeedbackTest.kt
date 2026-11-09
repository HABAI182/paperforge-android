package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.data.LocalTaskRow
import cn.paperforge.app.data.LocalTaskState
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ui.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Mat

class CaptureReviewFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun centredShutterAndSinglePageReview() {
        val photo = File(compose.activity.cacheDir, "capture-review-test.png")
        Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            photo.outputStream().use { stream ->
                it.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            it.recycle()
        }
        val page = PageRow("capture-review", "fixture", 0, photo.path, filter = "ORIGINAL")
        var preview by mutableStateOf(false)
        var action = ""
        compose.activity.setContent {
            PaperForgeTheme {
                Column(Modifier.fillMaxSize().testTag("capture-root")) {
                    Box(Modifier.weight(1f)) {
                        CaptureRecentPage(page, 12, true, { preview = true }, { action = "modify" })
                    }
                    CaptureActionBar(
                        false,
                        { TextButton(onClick = { action = "retake" }) { Text("撤回/重拍") } },
                        { Button(onClick = {}, modifier = Modifier.size(76.dp)) { Text("拍照") } },
                        { TextButton(onClick = {}) { Text("完成 123") } },
                    )
                }
                if (preview)
                    CaptureSinglePagePreview(
                        page,
                        12,
                        { preview = false },
                        {
                            action = "modify"
                            preview = false
                        },
                        {
                            action = "retake"
                            preview = false
                        },
                        {
                            action = "undo"
                            preview = false
                        },
                        {
                            action = "hd"
                            preview = false
                        },
                    )
            }
        }
        compose.waitForIdle()
        val bar = compose.onNodeWithTag("capture-action-bar").fetchSemanticsNode().boundsInRoot
        val shutter =
            compose.onNodeWithTag("capture-shutter-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(bar.center.x, shutter.center.x, .6f)
        compose.waitUntil(3000) {
            compose.onAllNodesWithContentDescription("预览第 12 页").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription("预览第 12 页").performClick()
        compose.onNodeWithText("第 12 页").assertIsDisplayed()
        compose.onNodeWithText("AI 高清").performClick()
        compose.runOnIdle {
            assertEquals("hd", action)
            preview = true
        }
        compose.onNodeWithText("重拍", substring = false).performClick()
        compose.runOnIdle {
            assertEquals("retake", action)
            preview = true
        }
        compose.onNodeWithText("撤回", substring = false).performClick()
        compose.runOnIdle { assertEquals("undo", action) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "feedback-20011/capture-layout.png")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun actualCameraPreviewTargetsPageAndUndoStopsItsPendingWork() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val oldAuto = UserSettings[SettingKeys.AutoShutter]
        UserSettings.set(SettingKeys.AutoShutter, false)
        val document = runBlocking { vm.repo.newScan() }
        val original = File(vm.repo.folder(document.id), "review-page.png")
        Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            original.outputStream().use { stream ->
                it.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            it.recycle()
        }
        val page =
            PageRow(
                java.util.UUID.randomUUID().toString(),
                document.id,
                0,
                original.path,
                filter = "ORIGINAL",
            )
        val task =
            LocalTaskRow(
                java.util.UUID.randomUUID().toString(),
                "CAPTURE",
                document.id,
                "fixture",
                page.id,
                null,
                0,
                LocalTaskState.RUNNING,
            )
        runBlocking {
            vm.repo.dao.putPage(page)
            vm.repo.dao.putTask(task)
        }
        try {
            compose.runOnIdle {
                vm.selected.value = document
                vm.screen.value = "capture"
            }
            compose.activity.setContent {
                PaperForgeTheme {
                    val screen by vm.screen.collectAsState()
                    DocumentWorkspace(vm, screen) {
                        if (screen == "capture") CameraScreen(vm) {} else EditorScreen(vm) {}
                    }
                }
            }
            compose.waitUntil(4000) {
                compose.onAllNodesWithTag("capture-recent-page").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(4000) {
                compose
                    .onAllNodesWithContentDescription("预览第 1 页")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val shutter =
                compose.onNodeWithTag("capture-shutter-slot").fetchSemanticsNode().boundsInRoot
            assertEquals("actual camera shutter off-centre", root.center.x, shutter.center.x, .6f)
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(compose.activity.filesDir, "feedback-20011/actual-camera.png")
                    .apply { parentFile!!.mkdirs() }
                    .outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithContentDescription("预览第 1 页").performClick()
            compose.onNodeWithText("第 1 页").assertIsDisplayed()
            compose.onAllNodesWithText("修改", substring = false).onLast().performClick()
            compose.runOnIdle {
                assertEquals(document.id, vm.selected.value!!.id)
                assertEquals(page.id, vm.pages.value.single().id)
                assertEquals("editor", vm.screen.value)
                vm.screen.value = "capture"
            }
            compose.waitForIdle()
            compose.onNodeWithText("撤回/重拍").performClick()
            compose.onNodeWithText("重拍此页").performClick()
            compose.runOnIdle { assertEquals(page.id, vm.replacement.value) }
            compose.onNodeWithText("撤回/重拍").performClick()
            compose.onNodeWithText("撤回此页").performClick()
            compose.waitUntil(4000) { !vm.busy.value && vm.pages.value.isEmpty() }
            runBlocking {
                assertEquals(LocalTaskState.CANCELLED, vm.repo.dao.task(task.id)!!.state)
                assertTrue(original.isFile)
            }
            compose.runOnIdle { vm.undo() }
            compose.waitUntil(4000) { !vm.busy.value && vm.pages.value.any { it.id == page.id } }
        } finally {
            UserSettings.set(SettingKeys.AutoShutter, oldAuto)
        }
    }

    @Test
    fun superResolutionKeepsWhitePaperAndVisibleInk() = runBlocking {
        val photo = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(photo)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(
            "a=2",
            6f,
            38f,
            android.graphics.Paint().apply {
                color = Color.DKGRAY
                textSize = 16f
                isAntiAlias = true
            },
        )
        try {
            val result =
                ScanRetouch.superResolve(
                    compose.activity,
                    photo,
                    IntArray(64 * 80) { -1 },
                    .55f,
                    {},
                    240,
                )
            try {
                assertEquals(192, result.width)
                assertEquals(240, result.height)
                for (y in 0 until 50) for (x in 0 until result.width) assertEquals(
                    "SR dirt in blank paper",
                    Color.WHITE,
                    result.getPixel(x, y),
                )
                val values = IntArray(result.width * result.height)
                result.getPixels(values, 0, result.width, 0, 0, result.width, result.height)
                assertTrue("visible characters lost", values.count { Color.red(it) < 160 } > 80)
            } finally {
                result.recycle()
            }
        } finally {
            photo.recycle()
        }
    }

    @Test
    fun fourEdgesTightenDeskButKeepRuledPaper() {
        val photo = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(photo)
        val pen = android.graphics.Paint()
        val mat = Mat()
        try {
            canvas.drawColor(Color.rgb(120, 78, 40))
            pen.color = Color.rgb(234, 230, 216)
            canvas.drawRect(25f, 30f, 577f, 768f, pen)
            Utils.bitmapToMat(photo, mat)
            val detection = DetectionResult(Quad.Full, .9f, "fixture")
            val result = PrimaryPaperFrame.refine(mat, detection)
            assertTrue("left desk", result.quad!!.points[0].x > .03f)
            assertTrue("top desk", result.quad!!.points[0].y > .025f)
            assertTrue("right desk", result.quad!!.points[1].x < .975f)
            assertTrue("bottom desk", result.quad!!.points[2].y < .975f)
            canvas.drawColor(Color.WHITE)
            pen.color = Color.GRAY
            pen.strokeWidth = 2f
            for (y in 30..780 step 35) canvas.drawLine(0f, y.toFloat(), 599f, y.toFloat(), pen)
            Utils.bitmapToMat(photo, mat)
            assertEquals(
                "rulings treated as paper border",
                Quad.Full,
                PrimaryPaperFrame.refine(mat, detection).quad,
            )
            canvas.drawColor(Color.rgb(236, 231, 215))
            pen.color = Color.rgb(177, 173, 161)
            canvas.drawRect(0f, 714f, 600f, 800f, pen)
            Utils.bitmapToMat(photo, mat)
            assertEquals(
                "same-paper shade trimmed as a material boundary",
                Quad.Full,
                PrimaryPaperFrame.refine(mat, detection).quad,
            )
        } finally {
            photo.recycle()
            mat.release()
        }
    }
}

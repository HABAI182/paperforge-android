package cn.paperforge.app

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ui.rememberPaperDocumentsPicker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FlatExportAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private val repo
        get() = app.repository

    @Test
    fun existingDraftFlatJobKeepsNameAndCanUndo() = runBlocking {
        val sourceFile = File(app.filesDir, "flat-feedback-source/latest.jpg")
        val doc = repo.newScan().copy(title = "已有课堂笔记")
        repo.dao.putDocument(doc)
        val source = sourceFile.copyTo(repo.captureFile(doc.id), overwrite = true)
        val page =
            PageRow(
                UUID.randomUUID().toString(),
                doc.id,
                0,
                source.path,
                corners =
                    Quad(
                            listOf(
                                PointN(.214f, .212f),
                                PointN(.836f, .230f),
                                PointN(.871f, .882f),
                                PointN(.104f, .878f),
                            )
                        )
                        .encode(),
                filter = "WORKBOOK",
            )
        repo.dao.putPage(page)
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            val work =
                cn.paperforge.app.pdf.RetouchJobs.enqueue(
                    repo,
                    page,
                    RetouchRequest("FLAT_SCAN"),
                    inPlace = true,
                )
            withTimeout(120000) {
                while (
                    androidx.work.WorkManager.getInstance(app)
                        .getWorkInfoById(work)
                        .get()
                        ?.state
                        ?.isFinished != true
                ) kotlinx.coroutines.delay(100)
            }
            val info =
                checkNotNull(androidx.work.WorkManager.getInstance(app).getWorkInfoById(work).get())
            assertEquals(
                info.outputData.getString("error"),
                androidx.work.WorkInfo.State.SUCCEEDED,
                info.state,
            )
            val updated = repo.dao.pages(doc.id).single()
            assertEquals("WORKBOOK", updated.filter)
            assertEquals(
                2,
                PageResources.metadata(updated)
                    .getJSONObject("flatDocument")
                    .getInt("appearanceModel"),
            )
            File(app.filesDir, "november-feedback/flatten-job.json")
                .writeText(
                    JSONObject()
                        .put(
                            "enqueueToPublishMs",
                            android.os.SystemClock.elapsedRealtime() - started,
                        )
                        .put("resultUpdatedInPlace", true)
                        .put("filter", updated.filter)
                        .toString(2)
                )
            assertNotEquals(source.path, updated.original)
            assertEquals("已有课堂笔记", repo.dao.document(doc.id)!!.title)
            assertTrue(
                PageResources.entries(updated).any {
                    it.role == "dewarp-grid" && File(it.path).isFile
                }
            )
            repo.undo(doc.id)
            assertEquals(source.path, repo.dao.pages(doc.id).single().original)
        } finally {
            repo.deleteDocument(doc)
        }
    }

    @Test
    fun latestOwnerPhotoFlatForegroundAndPdf() = runBlocking {
        val folder = File(app.filesDir, "flat-review").apply { mkdirs() }
        val sourceFile = File(app.filesDir, "flat-feedback-source/latest.jpg")
        assertTrue("Owner input must be seeded", sourceFile.isFile)
        val doc = repo.newScan().copy(title = "概率论课堂笔记")
        repo.dao.putDocument(doc)
        val source = sourceFile.copyTo(repo.captureFile(doc.id), overwrite = true)
        val page =
            PageRow(
                UUID.randomUUID().toString(),
                doc.id,
                0,
                source.path,
                corners =
                    Quad(
                            listOf(
                                PointN(.214f, .212f),
                                PointN(.836f, .230f),
                                PointN(.871f, .882f),
                                PointN(.104f, .878f),
                            )
                        )
                        .encode(),
                filter = "WORKBOOK",
            )
        repo.dao.putPage(page)
        fun save(bitmap: Bitmap, name: String) {
            File(folder, name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        val raw = ScanProcessor.render(page.copy(filter = "ORIGINAL"), 2400)
        save(raw, "owner-crop.png")
        ScanProcessor.render(page, 2400).also {
            save(it, "owner-before.png")
            it.recycle()
        }
        val started = System.currentTimeMillis()
        val result = FlatDocument.process(app, raw)
        try {
            save(result.bitmap, "owner-flat.png")
            result.grid.write(File(folder, "owner.pfgrid.gz").also { it.delete() })
            val report =
                JSONObject()
                    .put("lines", result.lines)
                    .put("algorithm", result.algorithm)
                    .put("milliseconds", System.currentTimeMillis() - started)
                    .put("width", result.bitmap.width)
                    .put("height", result.bitmap.height)
                    .put("foregroundSha256", NeuralPaper.SHA)
            File(folder, "owner-metrics.json").writeText(report.toString(2))
            assertTrue("Paper rules must be observed before flattening", result.lines >= 8)
            assertTrue(result.bitmap.width >= raw.width)
            val output = File(repo.folder(doc.id), "flat.png")
            output.outputStream().use { result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            repo.savePage(
                page.copy(original = output.path, corners = Quad.Full.encode(), filter = "ORIGINAL")
            )
            val exported = File(folder, "概率论课堂笔记.pdf")
            cn.paperforge.app.pdf.PdfEngine.scan(
                repo.dao.pages(doc.id),
                exported,
                context = app,
                searchable = false,
            ) {}
            ParcelFileDescriptor.open(exported, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { assertEquals(1, it.pageCount) }
            }
            assertTrue(sourceFile.readBytes().contentEquals(source.readBytes()))
        } finally {
            result.bitmap.recycle()
            raw.recycle()
            repo.deleteDocument(doc)
        }
    }

    @Test
    fun appLibrarySuppliesPdfImagesAndExistingOfficeOutputs() {
        val token = UUID.randomUUID().toString().take(8)
        val doc = runBlocking { repo.newScan() }.copy(title = "库内扫描-$token")
        runBlocking { repo.dao.putDocument(doc) }
        val photo = repo.captureFile(doc.id)
        Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).also { image ->
            image.eraseColor(Color.WHITE)
            photo.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        runBlocking {
            repo.dao.putPage(
                PageRow(UUID.randomUUID().toString(), doc.id, 0, photo.path, filter = "ORIGINAL")
            )
        }
        val office =
            File(repo.exports, "库内文档-$token.docx").apply { writeText("office-result-probe") }
        runBlocking { repo.recordOutput(office) }
        val requested = mutableStateOf(arrayOf("application/pdf"))
        val returned = java.util.concurrent.atomic.AtomicReference<List<Uri>>(emptyList())
        compose.activity.setContent {
            MaterialTheme {
                val picker = rememberPaperDocumentsPicker { returned.set(it) }
                Button(onClick = { picker.launch(requested.value) }) { Text("开始导入验收") }
            }
        }
        fun choose(title: String): Uri {
            returned.set(emptyList())
            compose.onNodeWithText("开始导入验收").performClick()
            compose.onNodeWithText("PaperForge 文档库").performClick()
            compose.onNodeWithText("搜索名称或分类").performTextInput(title)
            compose.waitUntil(20000) {
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
            compose.onNodeWithText("导入所选 1").performClick()
            compose.waitUntil(30000) { returned.get().isNotEmpty() }
            return returned.get().single()
        }
        try {
            val pdf = choose(doc.title)
            assertEquals("application/pdf", app.contentResolver.getType(pdf))
            app.contentResolver.openInputStream(pdf)!!.use {
                assertEquals("%PDF-", String(it.readNBytes(5)))
            }
            compose.runOnIdle { requested.value = arrayOf("image/*") }
            val image = choose(doc.title)
            assertEquals("image/png", app.contentResolver.getType(image))
            val rendered =
                app.contentResolver.openInputStream(image)!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(300, rendered.width)
            assertEquals(420, rendered.height)
            rendered.recycle()
            compose.runOnIdle {
                requested.value =
                    arrayOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    )
            }
            val reused = choose(office.name)
            app.contentResolver.openInputStream(reused)!!.use {
                assertEquals(office.readText(), it.bufferedReader().readText())
            }
        } finally {
            runBlocking { repo.deleteDocument(doc) }
            office.delete()
        }
    }

    @Test
    fun documentFilterSwitchKeepsTheLibrarySurfaceStill() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            model.screen.value = "home"
            model.homeTab.value = 0
        }
        compose.waitForIdle()
        val before = compose.onNodeWithText("PaperForge").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onAllNodesWithText("全部", substring = false).onFirst().performClick()
            compose.mainClock.advanceTimeByFrame()
            val after = compose.onNodeWithText("PaperForge").fetchSemanticsNode().boundsInRoot
            assertEquals(before.left, after.left, .5f)
            assertEquals(before.top, after.top, .5f)
            compose.onNodeWithText("最近", substring = false).performClick()
            compose.mainClock.advanceTimeByFrame()
            val recent = compose.onNodeWithText("PaperForge").fetchSemanticsNode().boundsInRoot
            assertEquals(before.left, recent.left, .5f)
            assertEquals(before.top, recent.top, .5f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun editorExportsPdfWithRenamedTitleDespiteLegacyZipPreset() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val doc = runBlocking { repo.newScan() }
        val originalOptions = ScanExportOptions.current()
        val photo = repo.captureFile(doc.id)
        Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).also { image ->
            image.eraseColor(Color.WHITE)
            Canvas(image)
                .drawText(
                    "PDF",
                    30f,
                    100f,
                    Paint().apply {
                        color = Color.BLACK
                        textSize = 48f
                    },
                )
            photo.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        val page = PageRow(UUID.randomUUID().toString(), doc.id, 0, photo.path, filter = "ORIGINAL")
        runBlocking {
            repo.dao.putPage(page)
            repo.rename(doc, "概率论课堂笔记")
        }
        ScanExportOptions(format = "PNG", naming = "{date}-{title}-{pages}").remember()
        try {
            compose.runOnIdle {
                model.selected.value = doc.copy(title = "概率论课堂笔记")
                model.screen.value = "editor"
            }
            compose.waitUntil(15000) {
                compose
                    .onAllNodesWithContentDescription("导出 PDF")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription("导出 PDF").performClick()
            compose.onNodeWithText("生成 PDF").performClick()
            compose.waitUntil(90000) { model.output.value?.extension == "pdf" }
            val file = model.output.value!!
            assertEquals("概率论课堂笔记.pdf", file.name)
            assertEquals("概率论课堂笔记", runBlocking { repo.dao.document(doc.id)!!.title })
            assertEquals("%PDF-", file.inputStream().use { String(it.readNBytes(5)) })
            compose.onNodeWithText("用其他应用打开").assertExists()
            compose.onNodeWithText("分享", useUnmergedTree = true).assertExists()
            val instrumentation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val launched = java.util.concurrent.atomic.AtomicReference<android.content.Intent?>()
            val monitor =
                object : android.app.Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(
                        intent: android.content.Intent
                    ): android.app.Instrumentation.ActivityResult? {
                        if (intent.action == android.content.Intent.ACTION_CHOOSER) {
                            launched.set(intent)
                            return android.app.Instrumentation.ActivityResult(
                                android.app.Activity.RESULT_CANCELED,
                                null,
                            )
                        }
                        return null
                    }
                }
            instrumentation.addMonitor(monitor)
            try {
                for ((label, action) in
                    listOf(
                        "用其他应用打开" to android.content.Intent.ACTION_VIEW,
                        "分享" to android.content.Intent.ACTION_SEND,
                    )) {
                    launched.set(null)
                    compose.onNodeWithText(label, useUnmergedTree = true).performClick()
                    compose.waitUntil(10000) { launched.get() != null }
                    @Suppress("DEPRECATION")
                    val target =
                        launched
                            .get()!!
                            .getParcelableExtra<android.content.Intent>(
                                android.content.Intent.EXTRA_INTENT
                            )!!
                    assertEquals(action, target.action)
                    assertEquals("application/pdf", target.type)
                    assertTrue(
                        target.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                    )
                    val readable = target.clipData!!.getItemAt(0).uri
                    assertEquals("content", readable.scheme)
                    app.contentResolver.query(readable, null, null, null, null)!!.use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(
                            "概率论课堂笔记.pdf",
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    android.provider.OpenableColumns.DISPLAY_NAME
                                )
                            ),
                        )
                    }
                    app.contentResolver.openInputStream(readable)!!.use {
                        assertEquals("%PDF-", String(it.readNBytes(5)))
                    }
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            val bitmap =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .takeScreenshot()
            File(app.filesDir, "flat-review/export-dialog.png")
                .also { it.parentFile!!.mkdirs() }
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            compose.runOnIdle {
                model.output.value = null
                model.selected.value = null
                model.screen.value = "home"
            }
            originalOptions.remember()
            runBlocking { repo.deleteDocument(doc) }
        }
    }
}

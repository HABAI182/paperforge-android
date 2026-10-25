package cn.paperforge.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.OcrRegion
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class TableWorkspaceFlowTest {
    private suspend fun waitFor(app: PaperForgeApp, id: UUID): WorkInfo =
        withTimeout(60000) {
            WorkManager.getInstance(app)
                .getWorkInfoByIdFlow(id)
                .filterNotNull()
                .first { it.state.isFinished }
                .also {
                    assertEquals(
                        it.outputData.getString("error"),
                        WorkInfo.State.SUCCEEDED,
                        it.state,
                    )
                }
        }

    @Test
    fun retainedSourcePerPageResumeRegionDraftAndTypedExports() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val oldBackend = UserSettings.string("ocrBackend", "MLKIT")
            UserSettings.set("ocrBackend", "MLKIT")
            val before = repo.dao.allTasks().map { it.id }.toSet()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val errors = mutableListOf<String>()
            val queued = MutableStateFlow<UUID?>(null)
            val workspace =
                TableWorkspace(repo, scope, {}, { queued.value = it }, { errors.add(it) })
            val source =
                File(app.filesDir, "conversion-inputs/table-proof-${System.nanoTime()}.pdf").apply {
                    parentFile!!.mkdirs()
                }
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("phone-office-table.pdf")
                .use { input -> source.outputStream().use { input.copyTo(it) } }
            val originalHash = PdfAssembly.sha256(source)
            var keepDraft: String? = null
            try {
                val row =
                    DocumentJobs.prepare(
                        repo,
                        "CONVERT",
                        JobInput(
                            JSONObject()
                                .put("kind", "TABLE_ANALYZE")
                                .put("name", "表格工作台验收")
                                .put("inputs", JSONArray(listOf(source.path)))
                                .put(
                                    "options",
                                    JSONObject().put("tableStrategy", "lines_strict").toString(),
                                )
                        ),
                    )
                DocumentJobs.schedule(repo, row)
                waitFor(app, UUID.fromString(row.workId))
                val complete = repo.dao.task(row.id)!!
                val checkpoints = JSONObject(complete.payload).getJSONObject("checkpoint")
                assertTrue(checkpoints.length() > 0)
                val paths =
                    checkpoints.keys().asSequence().associateWith { key ->
                        checkpoints.getJSONObject(key).getString("path")
                    }
                val hashes = paths.mapValues { PdfAssembly.sha256(File(it.value)) }
                repo.dao.putTask(
                    complete.copy(
                        state = LocalTaskState.FAILED,
                        resources =
                            DocumentResources.encode(
                                    complete.resourceEntries().filterNot { it.id == "result" }
                                )
                                .toString(),
                        error = "simulate interruption after pages",
                    )
                )
                val resumed = DocumentJobs.retry(repo, repo.dao.task(row.id)!!)
                waitFor(app, resumed)
                val resumedRow = repo.dao.task(row.id)!!
                val reused = JSONObject(resumedRow.payload).getJSONObject("checkpoint")
                paths.forEach { (key, path) ->
                    assertEquals(path, reused.getJSONObject(key).getString("path"))
                    assertEquals(hashes[key], PdfAssembly.sha256(File(path)))
                }
                workspace.result(resumedRow)
                val draft = workspace.draft.value!!
                keepDraft = draft.taskId
                val table = draft.tables.first()
                assertEquals("00123", table.rows[2][1])
                assertEquals("120.50", table.rows[2][2])
                assertEquals(2, table.cells.count { it.columnEnd > it.column })
                val cellBounds = table.cells.map { it.box }
                val region = OcrRegion(.11452514f, .26939654f, .863865f, .43427777f)
                val regional =
                    PhoneTables.extract(
                        source,
                        app.ocrEngine,
                        JSONObject()
                            .put("pages", "1")
                            .put("tableStrategy", "lines_strict")
                            .put(
                                "region",
                                JSONArray(
                                    listOf(region.left, region.top, region.right, region.bottom)
                                ),
                            ),
                    ) {}
                assertEquals("00123", regional.first().rows[2][1])
                assertEquals(table.rows, regional.first().rows)
                assertEquals(13, regional.first().cells.size)
                assertEquals(2, regional.first().cells.count { it.columnEnd > it.column })
                assertEquals(
                    table.cells.first().box.left,
                    regional.first().cells.first().box.left,
                    .008f,
                )
                workspace.cell(0, 2, 1, "00042")
                workspace.cell(0, 0, 0, "客户\t\"编号\"\n合并")
                workspace.options(draft.options.copy(columnTypes = mapOf("0:2" to "DECIMAL")))
                workspace.persist()
                val reopened =
                    TableWorkspace(repo, scope, {}, { queued.value = it }, { errors.add(it) })
                reopened.open(repo.dao.task(draft.taskId)!!)
                assertEquals("00042", reopened.draft.value!!.tables[0].rows[2][1])
                assertTrue(
                    reopened.draft.value!!
                        .tables[0]
                        .cells
                        .first { it.row == 2 && it.column == 1 }
                        .corrected
                )
                for (options in
                    listOf(
                        reopened.draft.value!!.options,
                        reopened.draft.value!!
                            .options
                            .copy(format = "CSV", separator = ";", excelText = true),
                        reopened.draft.value!!.options.copy(format = "TSV", encoding = "UTF16LE"),
                        reopened.draft.value!!.options.copy(format = "CSV", encoding = "GB18030"),
                    )) {
                    queued.value = null
                    reopened.export(options)
                    val work = withTimeout(10000) { queued.filterNotNull().first() }
                    val info = waitFor(app, work)
                    val output = File(info.outputData.getString("path")!!)
                    output.copyTo(
                        File(
                            app.cacheDir,
                            "table-proof-${options.format}-${options.encoding}.${output.extension}",
                        ),
                        overwrite = true,
                    )
                    if (options.format == "XLSX")
                        ZipFile(output).use { zip ->
                            val xml =
                                zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                                    .bufferedReader()
                                    .use { it.readText() }
                            assertTrue(xml.contains("00042"))
                            assertTrue(xml.contains("t=\"n\" s=\"2\"><v>120.50</v>"))
                            assertTrue(xml.contains("mergeCell"))
                        }
                }
                assertTrue(errors.toString(), errors.isEmpty())
                assertEquals(originalHash, PdfAssembly.sha256(source))
                File(app.cacheDir, "table-workspace-proof.json")
                    .writeText(
                        JSONObject()
                            .put("sourceSha256", originalHash)
                            .put("checkpointPathsReused", paths.size)
                            .put("draftTask", draft.taskId)
                            .put("table", TableData.json(reopened.draft.value!!.tables.first()))
                            .toString(2)
                    )
            } finally {
                scope.cancel()
                UserSettings.set("ocrBackend", oldBackend)
                repo.dao
                    .allTasks()
                    .filter { it.id !in before && it.id != keepDraft }
                    .forEach { row ->
                        if (row.state in LocalTaskRow.active) DocumentJobs.cancel(repo, row)
                        DocumentJobs.forget(repo, row.id)
                    }
            }
        }

    @Test
    fun lineOnlyPaddleReadsDetectedCellsWithoutInventedWordBoxes() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val oldBackend = UserSettings.string("ocrBackend", "MLKIT")
            val oldPack = UserSettings.string("ocrPackId", "")
            val pack =
                app.ocrModels.install(
                    Uri.fromFile(File(app.getExternalFilesDir(null), "paddle-proof.pfocr.zip"))
                )
            val source = File(app.cacheDir, "paddle-scanned-table.pdf")
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("phone-scanned-table.pdf")
                .use { input -> source.outputStream().use { input.copyTo(it) } }
            try {
                UserSettings.set("ocrPackId", pack.id)
                UserSettings.set("ocrBackend", "PADDLE")
                val tables =
                    PhoneTables.extract(
                        source,
                        app.ocrEngine,
                        JSONObject().put("pages", "1").put("tableStrategy", "lines_strict"),
                    ) {}
                val table = tables.first()
                assertEquals("00123", table.rows[2][1])
                assertEquals("120.50", table.rows[2][2])
                assertTrue(table.cells.all { it.textSource == "cell-ocr" })
                assertTrue(table.ocrLayout!!.lines.all { it.elements.isEmpty() })
                val encoded = TableData.json(table)
                assertEquals(
                    table.cells.size,
                    encoded
                        .getJSONObject("ocrLayout")
                        .getJSONObject("ir")
                        .getJSONArray("tables")
                        .getJSONObject(0)
                        .getJSONArray("cells")
                        .length(),
                )
                File(app.cacheDir, "table-paddle-proof.json").writeText(encoded.toString(2))
            } finally {
                UserSettings.set("ocrPackId", oldPack)
                UserSettings.set("ocrBackend", oldBackend)
                app.ocrEngine.trimUnused()
                source.delete()
            }
        }

    @Test
    fun nativeHeaderDoesNotSuppressOcrInsideAnImageTable() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val original = File(app.cacheDir, "mixed-table-before.pdf")
            val input = File(app.cacheDir, "mixed-table-header.pdf")
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("phone-scanned-table.pdf")
                .use { source -> original.outputStream().use { source.copyTo(it) } }
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(original).use { pdf ->
                com.tom_roush.pdfbox.pdmodel
                    .PDPageContentStream(
                        pdf,
                        pdf.getPage(0),
                        com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND,
                        true,
                        true,
                    )
                    .use { stream ->
                        stream.beginText()
                        stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 9f)
                        stream.newLineAtOffset(25f, 12f)
                        stream.showText("NATIVE HEADER ONLY 2026")
                        stream.endText()
                    }
                pdf.save(input)
            }
            try {
                val tables =
                    PhoneTables.extract(
                        input,
                        app.ocrEngine.snapshot(cn.paperforge.app.ocr.BundledOcrEngine.ID),
                        JSONObject().put("tableStrategy", "lines_strict"),
                    ) {}
                assertEquals("00123", tables.first().rows[2][1])
                assertEquals("120.50", tables.first().rows[2][2])
                assertNotNull(tables.first().ocrLayout)
                File(app.cacheDir, "table-mixed-header-proof.json")
                    .writeText(TableData.encode(tables).toString(2))
            } finally {
                original.delete()
                input.delete()
            }
        }

    @Test
    fun gridSeparatorsKeepHorizontalAndVerticalMergedCells() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val bitmap =
            android.graphics.Bitmap.createBitmap(700, 420, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas =
            android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                strokeWidth = 3f
                style = android.graphics.Paint.Style.STROKE
            }
        canvas.drawRect(40f, 40f, 640f, 360f, paint)
        for (y in listOf(120f, 280f)) canvas.drawLine(40f, y, 640f, y, paint)
        canvas.drawLine(240f, 200f, 640f, 200f, paint)
        canvas.drawLine(240f, 120f, 240f, 360f, paint)
        canvas.drawLine(440f, 40f, 440f, 360f, paint)
        paint.style = android.graphics.Paint.Style.FILL
        paint.textSize = 20f
        val tokens = mutableListOf<PhoneTables.Token>()
        fun token(text: String, x: Float, y: Float) {
            canvas.drawText(text, x, y, paint)
            tokens +=
                PhoneTables.Token(
                    text,
                    android.graphics.RectF(
                        x / 700,
                        (y - 20) / 420,
                        (x + paint.measureText(text)) / 700,
                        y / 420,
                    ),
                )
        }
        token("HEADER", 55f, 85f)
        token("AMOUNT", 455f, 85f)
        token("REGION", 55f, 165f)
        token("00123", 255f, 165f)
        token("120.50", 455f, 165f)
        token("00456", 255f, 245f)
        token("80.00", 455f, 245f)
        token("TOTAL", 55f, 325f)
        token("00077", 255f, 325f)
        token("200.50", 455f, 325f)
        try {
            val table = PhoneTables.detect(bitmap, 1, tokens, "lines_strict").single()
            assertEquals(4, table.rows.size)
            assertEquals(3, table.rows.first().size)
            assertEquals(10, table.cells.size)
            assertEquals(1, table.cells.single { it.row == 0 && it.column == 0 }.columnEnd)
            assertEquals(2, table.cells.single { it.row == 1 && it.column == 0 }.rowEnd)
            assertEquals("00123", table.rows[1][1])
            assertEquals("120.50", table.rows[1][2])
            File(app.cacheDir, "table-both-spans-proof.json")
                .writeText(TableData.json(table).toString(2))
            File(app.cacheDir, "table-both-spans-proof.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}

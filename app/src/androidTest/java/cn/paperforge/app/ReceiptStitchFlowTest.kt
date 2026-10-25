package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptStitchFlowTest {
    @Test
    fun overlapAlignmentManualSeamsAndDurableSegmentOutputKeepRowsAndSideEdges() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val directory =
            File(app.filesDir, "conversion-inputs/stitch-proof-${UUID.randomUUID()}").apply {
                mkdirs()
            }
        val a = File(directory, "receipt-top.png")
        val b = File(directory, "receipt-bottom.png")
        val whole = Bitmap.createBitmap(256, 2300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whole)
        canvas.drawColor(Color.WHITE)
        val random = java.util.Random(4242)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }
        for (row in 0 until 74) canvas.drawText(
            "%03d %08x 00123".format(row, random.nextInt()),
            18f,
            (row * 30 + 22).toFloat(),
            paint,
        )
        paint.color = Color.RED
        canvas.drawRect(0f, 90f, 4f, 110f, paint)
        val top = Bitmap.createBitmap(whole, 0, 0, 256, 1450)
        a.outputStream().use { top.compress(Bitmap.CompressFormat.PNG, 100, it) }
        top.recycle()
        val lower = Bitmap.createBitmap(256, 1300, Bitmap.Config.ARGB_8888)
        Canvas(lower).apply {
            drawColor(Color.WHITE)
            drawBitmap(whole, Rect(0, 1000, 246, 2300), Rect(10, 0, 256, 1300), null)
            paint.color = Color.BLUE
            drawRect(0f, 1190f, 4f, 1210f, paint)
        }
        b.outputStream().use { lower.compress(Bitmap.CompressFormat.PNG, 100, it) }
        lower.recycle()
        whole.recycle()
        val hashes = listOf(a, b).map { PdfAssembly.sha256(it) }
        val detected = ReceiptAlignment.match(a, b)
        File(app.cacheDir, "receipt-auto-proof.json").writeText(detected.json().toString(2))
        assertEquals("AUTO", detected.mode)
        assertEquals(450f / 1300f, detected.overlap, .002f)
        assertEquals(-10f / 256f, detected.shift, .005f)
        val blank = File(directory, "blank.png")
        val white =
            Bitmap.createBitmap(256, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        blank.outputStream().use { white.compress(Bitmap.CompressFormat.PNG, 100, it) }
        white.recycle()
        assertEquals("UNRESOLVED", ReceiptAlignment.match(blank, blank).mode)
        blank.delete()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var queued: UUID? = null
        val workspace = LongImageWorkspace(repo, scope, {}, { queued = it }, { errors.add(it) })
        try {
            workspace.create(
                listOf(a to "上半张", b to "下半张"),
                LongImageOptions(width = 256, gap = 16, segmentHeight = 384),
            )
            workspace.seam(1, detected.copy(mode = "MANUAL"))
            workspace.move(1, -1)
            workspace.move(0, 1)
            workspace.persist()
            val id = workspace.draft.value!!.taskId
            val persisted = LongImageDraft.decode(repo.dao.task(id)!!)
            assertEquals(detected.overlap, persisted.seam(1).overlap, .00001f)
            assertEquals(detected.shift, persisted.seam(1).shift, .00001f)
            workspace.open(repo.dao.task(id)!!)
            workspace.generate()
            withTimeout(20000) {
                while (
                    repo.dao.task(id)?.state !in
                        setOf(LocalTaskState.SUCCEEDED, LocalTaskState.FAILED)
                ) delay(30)
            }
            val row = repo.dao.task(id)!!
            assertTrue(errors.toString(), errors.isEmpty())
            assertNotNull(queued)
            assertEquals(row.error, LocalTaskState.SUCCEEDED, row.state)
            val output = File(row.resourceEntries().first { it.id == "result" }.path)
            ZipFile(output).use { zip ->
                val report =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("long-image-manifest.json"))
                            .bufferedReader()
                            .readText()
                    )
                assertEquals(2300, report.getInt("totalLogicalRows"))
                assertEquals(
                    450,
                    report.getJSONArray("stitches").getJSONObject(0).getInt("skippedTopRows"),
                )
                val segments = report.getJSONArray("segments")
                assertEquals(6, segments.length())
                val result = Bitmap.createBitmap(266, 2300, Bitmap.Config.ARGB_8888)
                var y = 0
                for (i in 0 until segments.length()) {
                    val entry = segments.getJSONObject(i)
                    val image =
                        zip.getInputStream(zip.getEntry(entry.getString("file"))).use {
                            BitmapFactory.decodeStream(it)
                        }
                    assertEquals(266, image.width)
                    Canvas(result).drawBitmap(image, 0f, y.toFloat(), null)
                    y += image.height
                    image.recycle()
                }
                assertEquals(Color.RED, result.getPixel(11, 100))
                assertEquals(Color.BLUE, result.getPixel(1, 2200))
                result.recycle()
                assertTrue(
                    (0 until report.getJSONArray("sourceMapping").length()).all {
                        !report
                            .getJSONArray("sourceMapping")
                            .getJSONObject(it)
                            .optBoolean("separator")
                    }
                )
            }
            assertEquals(hashes, listOf(a, b).map { PdfAssembly.sha256(it) })
            output.copyTo(File(app.cacheDir, "receipt-stitch-proof.zip"), overwrite = true)
            // Leave one synthetic draft for actual UI review and restart continuation.
            workspace.create(
                listOf(a to "票据上半张", b to "票据下半张"),
                LongImageOptions(width = 256, gap = 0, segmentHeight = 384),
            )
            workspace.seam(1, detected)
            workspace.persist()
            DocumentJobs.forget(repo, row.id)
        } finally {
            scope.cancel()
        }
    }
}

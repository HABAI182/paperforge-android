package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.Quad
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The camera's 书本 mode through the real capture worker: a photo of an open book is split at its gutter before it
 * is finished. 左页 and 右页 keep one page, 两页 gives both with the left first, and a photo of a single sheet is
 * never cut. The photo is drawn on the device: an open spread with a shadowed gutter on a dark desk.
 */
class BookCaptureTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val repo = (instrument.targetContext.applicationContext as PaperForgeApp).repository

    private fun spreadPhoto(): Bitmap {
        val w = 2000
        val h = 1500
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(58, 52, 48))
        val paper = Paint().apply { color = Color.rgb(236, 234, 228) }
        canvas.drawRect(180f, 180f, 1820f, 1320f, paper)
        // The gutter: paper darkening toward the fold from both sides.
        for (x in 900 until 1100) {
            val d = kotlin.math.abs(x - 1000) / 100f
            val shade = (70 * (1 - d) * (1 - d)).toInt()
            paper.color = Color.rgb(236 - shade, 234 - shade, 228 - shade)
            canvas.drawRect(x.toFloat(), 180f, x + 1f, 1320f, paper)
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); textSize = 30f }
        for (row in 0 until 22) {
            val y = 260f + row * 46f
            canvas.drawText("LEFT PAGE LINE $row ABCDEFG", 260f, y, ink)
            canvas.drawText("RIGHT PAGE LINE $row HIJKLMN", 1120f, y, ink)
        }
        return bitmap
    }

    /** An open spread whose gutter casts no shadow: nothing for an automatic split to trust. */
    private fun flatSpreadPhoto(): Bitmap {
        val bitmap = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(58, 52, 48))
        canvas.drawRect(180f, 180f, 1820f, 1320f, Paint().apply { color = Color.rgb(236, 234, 228) })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); textSize = 30f }
        for (row in 0 until 22) {
            canvas.drawText("LEFT PAGE LINE $row ABCDEFG", 260f, 260f + row * 46f, ink)
            canvas.drawText("RIGHT PAGE LINE $row HIJKLMN", 1120f, 260f + row * 46f, ink)
        }
        return bitmap
    }

    private fun sheetPhoto(): Bitmap {
        val bitmap = Bitmap.createBitmap(1500, 2000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(58, 52, 48))
        canvas.drawRect(220f, 220f, 1280f, 1780f, Paint().apply { color = Color.rgb(236, 234, 228) })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); textSize = 30f }
        for (row in 0 until 28) canvas.drawText("ONE SHEET LINE $row", 300f, 300f + row * 50f, ink)
        return bitmap
    }

    private suspend fun capture(document: String, photo: Bitmap): LocalTaskRow {
        val row = CaptureTasks.prepare(repo, document, null)
        CaptureTasks.raw(row).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        CaptureTasks.accept(repo, row.id)
        withTimeout(240_000) { while (repo.dao.task(row.id)?.state in LocalTaskRow.active) delay(100) }
        return repo.dao.task(row.id)!!
    }

    private fun span(page: PageRow): ClosedFloatingPointRange<Float> {
        val q = Quad.decode(page.corners).points
        return q.minOf { it.x }..q.maxOf { it.x }
    }

    private fun cleanly(body: suspend () -> Unit) = runBlocking {
        val documentsBefore = repo.dao.allDocuments().map { it.id }.toSet()
        val tasksBefore = repo.dao.allTasks().map { it.id }.toSet()
        val preferences = UserSettings.exportJson()
        val mode = UserSettings.string("captureMode", "document")
        val bookPage = UserSettings.string("bookPage", "BOTH")
        try {
            // The default detector (MobileNetV3 x1.5), not whatever an earlier test left behind.
            UserSettings.set(SettingKeys.Detector, "PAGENET_V2")
            body()
        } finally {
            repo.dao.allDocuments().filter { it.id !in documentsBefore }.forEach { repo.deleteDocument(it) }
            repo.dao.allTasks().filter { it.id !in tasksBefore }.forEach {
                repo.database.openHelper.writableDatabase.execSQL("DELETE FROM local_tasks WHERE id=?", arrayOf(it.id))
            }
            UserSettings.importJson(preferences)
            UserSettings.set("captureMode", mode)
            UserSettings.set("bookPage", bookPage)
        }
    }

    @Test
    fun leftOrRightKeepsOnePageAndASingleSheetIsNeverCut() = cleanly {
        UserSettings.set(SettingKeys.Filter, "ORIGINAL")
        UserSettings.set("captureMode", "book")
        val photo = spreadPhoto()
        for ((choice, inside) in listOf("LEFT" to (.05f..0.56f), "RIGHT" to (.44f..0.95f))) {
            UserSettings.set("bookPage", choice)
            val document = repo.newScan()
            val task = capture(document.id, photo)
            assertEquals(task.detail, LocalTaskState.SUCCEEDED, task.state)
            val pages = repo.dao.pages(document.id)
            assertEquals("$choice keeps one page", 1, pages.size)
            val span = span(pages.single())
            assertTrue("$choice page spans $span (${pages.single().detectionMethod})", span.start >= inside.start && span.endInclusive <= inside.endInclusive)
            assertTrue(pages.single().detectionMethod.orEmpty().contains("+book-" + choice.lowercase()))
        }
        // A single sheet in 书本 mode stays whole.
        UserSettings.set("bookPage", "BOTH")
        val document = repo.newScan()
        capture(document.id, sheetPhoto())
        val sheet = repo.dao.pages(document.id)
        assertEquals(1, sheet.size)
        assertFalse(sheet.single().detectionMethod.orEmpty().contains("+book-"))
        photo.recycle()
    }

    @Test
    fun anUncertainGutterCutsNothingUntilTheUserPlacesTheSplit() = cleanly {
        UserSettings.set(SettingKeys.Filter, "WORKBOOK")
        UserSettings.set("captureMode", "book")
        UserSettings.set("bookPage", "LEFT")
        val photo = flatSpreadPhoto()
        val document = repo.newScan()
        val task = capture(document.id, photo)
        photo.recycle()
        assertEquals(task.detail, LocalTaskState.SUCCEEDED, task.state)
        val page = repo.dao.pages(document.id).single()
        // The whole spread, unflattened, with the split waiting for the user.
        assertTrue(page.detectionMethod.orEmpty(), page.detectionMethod.orEmpty().contains("+book-pending"))
        val pending = SpreadGutter.bookPending(page.processingMetadata)
        assertNotNull(pending)
        assertEquals("LEFT", pending!!.first)
        assertTrue(span(page).let { it.start < .2f && it.endInclusive > .8f })
        // The user confirms the split (the editor's dialog calls this): one page, the left one, no pending mark.
        repo.splitBook(page, pending.second, pending.third, "left")
        val after = repo.dao.pages(document.id).single()
        assertTrue(span(after).endInclusive <= .6f)
        assertNull(SpreadGutter.bookPending(after.processingMetadata))
        repo.undo(document.id)
        assertEquals(page.corners, repo.dao.pages(document.id).single().corners)
    }

    /**
     * Real open notebooks and books, off-centre and curved, pushed to files/book-cases (the owner's photos and held-out
     * Commons spreads; not part of the repository). 书本 · 左页 on each: either the page is cut at a certain gutter, or
     * the whole crop is kept for the user to split. Every result is written to files/book-cases-out for review.
     */
    @Test
    fun realSpreadsAreCutOnlyWhereTheGutterIsCertain() = cleanly {
        val app = instrument.targetContext
        val photos = java.io.File(app.filesDir, "book-cases").listFiles { f -> f.extension == "jpg" }.orEmpty().sorted()
        org.junit.Assume.assumeTrue("no photos in files/book-cases", photos.isNotEmpty())
        val out = java.io.File(app.filesDir, "book-cases-out").apply { deleteRecursively(); mkdirs() }
        UserSettings.set(SettingKeys.Filter, "ORIGINAL")
        UserSettings.set("captureMode", "book")
        UserSettings.set("bookPage", "LEFT")
        val report = org.json.JSONArray()
        for (file in photos) {
            val document = repo.newScan()
            val row = CaptureTasks.prepare(repo, document.id, null)
            file.copyTo(CaptureTasks.raw(row), overwrite = true)
            CaptureTasks.accept(repo, row.id)
            withTimeout(240_000) { while (repo.dao.task(row.id)?.state in LocalTaskRow.active) delay(100) }
            val pages = repo.dao.pages(document.id)
            assertEquals(file.name, 1, pages.size)
            val page = pages.single()
            val method = page.detectionMethod.orEmpty()
            val pending = SpreadGutter.bookPending(page.processingMetadata)
            // Never both cut and pending; a pending page is the whole detected crop.
            assertFalse(file.name, method.contains("+book-left-") && pending != null)
            if (pending != null) assertTrue(file.name, method.contains("+book-pending"))
            report.put(org.json.JSONObject().put("file", file.name).put("method", method).put("corners", page.corners)
                .put("pending", pending?.toString() ?: org.json.JSONObject.NULL))
            ScanProcessor.render(page, 1200).let { bitmap ->
                java.io.File(out, file.nameWithoutExtension + ".jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bitmap.recycle()
            }
        }
        java.io.File(out, "report.json").writeText(report.toString(1))
    }

    @Test
    fun bothGivesTwoFinishedPagesLeftFirstAndUndoTakesTheRightOneOut() = cleanly {
        UserSettings.set(SettingKeys.Filter, "WORKBOOK")
        UserSettings.set("captureMode", "book")
        UserSettings.set("bookPage", "BOTH")
        val photo = spreadPhoto()
        val document = repo.newScan()
        val task = capture(document.id, photo)
        photo.recycle()
        assertEquals(task.detail, LocalTaskState.SUCCEEDED, task.state)
        assertTrue(task.detail + " " + repo.dao.pages(document.id).map { it.detectionMethod + " " + it.corners }, task.detail.contains("左右两页"))
        val pages = repo.dao.pages(document.id)
        assertEquals(2, pages.size)
        assertEquals(listOf(0, 1), pages.map { it.position })
        assertTrue(pages[0].detectionMethod.orEmpty().contains("+book-both-") || pages[0].detectionMethod == "flat-document")
        // Both pages went through the workbook finish, each from its own half of the photo.
        pages.forEach { assertTrue(java.io.File(it.original).isFile) }
        assertNotEquals(pages[0].original, pages[1].original)
        repo.undo(document.id)
        assertEquals(1, repo.dao.pages(document.id).size)
    }
}

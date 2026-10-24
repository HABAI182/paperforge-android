package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.*
import cn.paperforge.app.model.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class DewarpResourceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val repo
        get() = (context.applicationContext as PaperForgeApp).repository

    @Test
    fun savedGridFollowsTheRenderedPixelsAndSurvivesHistoryAndBackup() = runBlocking {
        val old = repo.dao.allDocuments().map { it.id }.toSet()
        val doc = repo.newScan()
        val backup = File(repo.exports, "grid-${System.nanoTime()}.zip")
        val input =
            File(context.cacheDir, "reader-text/trace-${UUID.randomUUID()}.png").apply {
                parentFile?.mkdirs()
            }
        val image = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        for (y in 0 until 300) for (x in 0 until 400) image.setPixel(
            x,
            y,
            Color.rgb(x * 255 / 399, y * 255 / 299, 80),
        )
        try {
            val original = repo.captureFile(doc.id)
            original.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val page =
                PageRow(UUID.randomUUID().toString(), doc.id, 0, original.path, filter = "ORIGINAL")
            repo.dao.putPage(page)
            val result = PageDewarp.process(image, .65f)
            val out = File(repo.folder(doc.id), "result.png")
            val field = File(repo.folder(doc.id), "mapping.pfgrid.gz")
            try {
                input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.outputStream().use {
                    result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                result.grid.write(field)
                for ((x, y) in listOf(80 to 90, 200 to 150, 320 to 240)) {
                    val p = result.grid.inputPoint(PointN(x / 399f, y / 299f))
                    val pixel = result.bitmap.getPixel(x, y)
                    assertEquals(p.x * 255, Color.red(pixel).toFloat(), 3f)
                    assertEquals(p.y * 255, Color.green(pixel).toFloat(), 3f)
                }
            } finally {
                result.bitmap.recycle()
            }
            val changed =
                page.copy(
                    original = out.path,
                    processingMetadata =
                        PageResources.withDewarp(
                            page,
                            input,
                            field,
                            out,
                            .65f,
                            ScanModel.fingerprint(true),
                        ),
                )
            repo.savePage(changed)
            val trace = checkNotNull(DewarpTrace.load(changed))
            val p = PointN(.3f, .6f)
            assertEquals(trace.grid.inputPoint(p), trace.inputPoint(changed, p, 400, 300))
            val rotated = changed.copy(rotation = 90)
            val q = trace.inputPoint(rotated, PointN(1 - p.y, p.x), 300, 400)
            assertEquals(trace.grid.inputPoint(p).x, q.x, .0001f)
            assertEquals(trace.grid.inputPoint(p).y, q.y, .0001f)
            repo.undo(doc.id)
            assertEquals(original.path, repo.dao.pages(doc.id).single().original)
            assertTrue(field.path in repo.originalResources(doc.id))
            repo.undo(doc.id, true)
            val disposable =
                File(context.cacheDir, "reader-text/throwaway-${System.nanoTime()}.json").apply {
                    writeText("cache")
                }
            val storage = LibraryStorage(repo)
            assertTrue(input.canonicalPath in storage.report().protectedPaths)
            storage.clearCaches(setOf("reader-text", "document-covers"))
            assertTrue(input.exists())
            assertFalse(disposable.exists())
            assertTrue(original.exists() && field.exists() && out.exists())
            BackupStore.backup(repo, backup)
            BackupStore.restore(repo, Uri.fromFile(backup))
            val restored =
                repo.dao.allDocuments().single {
                    it.id !in old && it.id != doc.id && it.title == doc.title
                }
            val restoredPage = repo.dao.pages(restored.id).single()
            assertTrue(PageResources.paths(restoredPage).all { File(it).exists() })
            assertFalse(
                PageResources.paths(restoredPage).any { it in PageResources.paths(changed) }
            )
            assertEquals(
                trace.grid.inputPoint(p),
                checkNotNull(DewarpTrace.load(restoredPage)).grid.inputPoint(p),
            )
            repo.undo(restored.id)
            assertTrue(File(repo.dao.pages(restored.id).single().original).exists())
        } finally {
            image.recycle()
            input.delete()
            backup.delete()
            repo.dao.allDocuments().filter { it.id !in old }.forEach { repo.deleteDocument(it) }
        }
    }

    @Test
    fun failureAfterDatabaseWritesRollsBackDocumentsReaderSettingsAndFiles() = runBlocking {
        val old = repo.dao.allDocuments().map { it.id }.toSet()
        val doc = repo.newScan()
        val backup = File(repo.exports, "restore-failure-${System.nanoTime()}.zip")
        val broken = File(repo.exports, "restore-broken-${System.nanoTime()}.zip")
        try {
            val file = repo.captureFile(doc.id)
            val image = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            repo.dao.putPage(PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path))
            ReaderPreferences(context).save(doc.id, ReaderPosition(zoom = 2f))
            BackupStore.backup(repo, backup)
            ZipFile(backup).use { zip ->
                val metadata =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("paperforge.json"))
                            .bufferedReader()
                            .readText()
                    )
                metadata.put(
                    "studyNotes",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("documentId", doc.id)
                                .put("title", "bad")
                                .put("page", 0)
                        ),
                )
                val bytes = metadata.toString().toByteArray(Charsets.UTF_8)
                val integrity =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("integrity.json"))
                            .bufferedReader()
                            .readText()
                    )
                integrity.put(
                    "paperforge.json",
                    JSONObject()
                        .put("bytes", bytes.size)
                        .put(
                            "sha256",
                            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                                "%02x".format(it.toInt() and 255)
                            },
                        ),
                )
                ZipOutputStream(broken.outputStream()).use { out ->
                    zip.entries().asSequence().forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        when (entry.name) {
                            "paperforge.json" -> out.write(bytes)
                            "integrity.json" ->
                                out.write(integrity.toString().toByteArray(Charsets.UTF_8))
                            else -> zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
            val beforeIds = repo.dao.allDocuments().map { it.id }.toSet()
            val beforeFiles =
                File(context.filesDir, "documents")
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.path }
                    .toSet()
            val beforePrefs =
                listOf("preferences", "reader", "reader-view", "signature").associateWith {
                    context.getSharedPreferences(it, 0).all.toMap()
                }
            val result = runCatching { BackupStore.restore(repo, Uri.fromFile(broken)) }
            assertTrue(result.isFailure)
            assertEquals(beforeIds, repo.dao.allDocuments().map { it.id }.toSet())
            assertEquals(
                beforeFiles,
                File(context.filesDir, "documents")
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.path }
                    .toSet(),
            )
            beforePrefs.forEach { (key, values) ->
                assertEquals(values, context.getSharedPreferences(key, 0).all)
            }
        } finally {
            backup.delete()
            broken.delete()
            repo.dao.allDocuments().filter { it.id !in old }.forEach { repo.deleteDocument(it) }
        }
    }
}

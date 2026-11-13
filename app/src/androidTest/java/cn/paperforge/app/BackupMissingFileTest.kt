package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupMissingFileTest {
    @Test
    fun backupSkipsVanishedExportAndItsArchiveStillRestores() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as PaperForgeApp).repository
        val existingDocuments = repo.dao.allDocuments().map { it.id }.toSet()
        val existingOutputs = repo.dao.allOutputs().map { it.path }.toSet()
        val originalSettings = UserSettings.exportJson()
        val doc = repo.newScan()
        val stamp = System.nanoTime()
        val archive = File(repo.exports, "missing-export-$stamp.zip")
        val kept = File(repo.exports, "missing-export-kept-$stamp.pdf")
        val gone = File(repo.exports, "missing-export-gone-$stamp.pdf")
        try {
            val page =
                PageRow(
                    UUID.randomUUID().toString(),
                    doc.id,
                    0,
                    repo.captureFile(doc.id).path,
                    filter = "ORIGINAL",
                )
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("scan-core/dark-desk-0.jpg")
                .use { input -> File(page.original).outputStream().use { input.copyTo(it) } }
            repo.dao.putPage(page)
            kept.writeText("%PDF-1.4 kept export")
            gone.writeText("%PDF-1.4 vanished export")
            repo.dao.putOutput(OutputRow(kept.path, doc.id, "kept", "pdf"))
            repo.dao.putOutput(OutputRow(gone.path, doc.id, "gone", "pdf"))
            // The export disappears from storage while the library still lists it.
            assertTrue(gone.delete())

            val result = BackupStore.backup(repo, archive)
            // Earlier test runs may have left other vanished files; ours must be among them.
            assertTrue(result.missing >= 1)
            ZipFile(archive).use { zip ->
                val meta =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("paperforge.json"))
                            .bufferedReader()
                            .use { it.readText() }
                    )
                val integrity =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("integrity.json"))
                            .bufferedReader()
                            .use { it.readText() }
                    )
                val missing =
                    meta.getJSONArray("missing").let { a ->
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    }
                assertEquals(result.missing, missing.size)
                val outputs =
                    meta.getJSONArray("outputs").let { a ->
                        (0 until a.length())
                            .map { OutputRow.decode(a.getJSONObject(it)) }
                            .filter { it.sourceDocumentId == doc.id }
                    }
                val goneEntry = outputs.single { it.sourceTitle == "gone" }.path
                val keptEntry = outputs.single { it.sourceTitle == "kept" }.path
                assertTrue(goneEntry in missing)
                assertNull(zip.getEntry(goneEntry))
                assertFalse(integrity.has(goneEntry))
                assertFalse(keptEntry in missing)
                assertNotNull(zip.getEntry(keptEntry))
                assertTrue(integrity.has(keptEntry))
            }

            assertTrue(BackupStore.preview(repo, Uri.fromFile(archive)).documents >= 1)
            BackupStore.restore(repo, Uri.fromFile(archive))
            val restored =
                repo.dao.allDocuments().single {
                    it.title == doc.title && it.id != doc.id && it.id !in existingDocuments
                }
            assertTrue(File(repo.dao.pages(restored.id).single().original).isFile)
            val restoredOutputs =
                repo.dao.allOutputs().filter { it.sourceDocumentId == restored.id }
            // The vanished export's stale record is dropped; the surviving one comes back.
            assertEquals(listOf("kept"), restoredOutputs.map { it.sourceTitle })
            assertEquals("%PDF-1.4 kept export", File(restoredOutputs.single().path).readText())
        } finally {
            repo.dao
                .allOutputs()
                .filter { it.path !in existingOutputs }
                .forEach {
                    repo.dao.removeOutput(it.path)
                    File(it.path).delete()
                }
            repo.dao
                .allDocuments()
                .filter { it.id !in existingDocuments }
                .forEach { repo.deleteDocument(it) }
            UserSettings.importJson(originalSettings)
            archive.delete()
            kept.delete()
            gone.delete()
        }
    }

    private val repo get() =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PaperForgeApp).repository

    /** A new scan with [count] real pages; returns the document and its pages. */
    private suspend fun scanWithPages(count: Int): Pair<DocumentRow, List<PageRow>> {
        val doc = repo.newScan()
        val pages =
            (0 until count).map { index ->
                PageRow(UUID.randomUUID().toString(), doc.id, index, repo.captureFile(doc.id).path, filter = "ORIGINAL")
                    .also { page ->
                        InstrumentationRegistry.getInstrumentation().context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
                            File(page.original).outputStream().use { input.copyTo(it) }
                        }
                        repo.dao.putPage(page)
                    }
            }
        return doc to pages
    }

    /** Copies [source] to [target], passing every entry through [change]; null drops the entry. */
    private fun rewrite(source: File, target: File, change: (String, ByteArray) -> ByteArray?) {
        ZipFile(source).use { zip ->
            ZipOutputStream(target.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    val bytes = change(entry.name, zip.getInputStream(entry).use { it.readBytes() }) ?: continue
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun metadata(archive: File) =
        ZipFile(archive).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("paperforge.json")).bufferedReader().use { it.readText() })
        }

    private suspend fun cleanUp(existing: Set<String>) {
        repo.dao.allDocuments().filter { it.id !in existing }.forEach { repo.deleteDocument(it) }
    }

    @Test
    fun aPageWhoseImageIsGoneIsReportedAndLeftOutOnRestore() = runBlocking {
        val existing = repo.dao.allDocuments().map { it.id }.toSet()
        val settings = UserSettings.exportJson()
        val archive = File(repo.exports, "missing-page-${System.nanoTime()}.zip")
        try {
            val (doc, pages) = scanWithPages(2)
            assertTrue(File(pages[1].original).delete())
            val result = BackupStore.backup(repo, archive)
            assertTrue("a missing page image must be reported as a missing page", result.missingPages >= 1)
            val recorded =
                metadata(archive).getJSONArray("missingPages").let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).getString("pageId") }
                }
            assertTrue(pages[1].id in recorded)
            assertFalse(pages[0].id in recorded)
            val report = BackupStore.restoreReport(repo, Uri.fromFile(archive))
            assertTrue("restore must say it left pages out", report.skippedPages >= 1)
            val restored =
                repo.dao.allDocuments().single { it.title == doc.title && it.id != doc.id && it.id !in existing }
            val restoredPages = repo.dao.pages(restored.id)
            assertEquals("only the page with its image comes back", 1, restoredPages.size)
            assertTrue(File(restoredPages.single().original).isFile)
            // No restored page anywhere points at a file that is not there.
            for (d in repo.dao.allDocuments().filter { it.id !in existing && it.id != doc.id })
                for (page in repo.dao.pages(d.id))
                    assertTrue("dangling page image ${page.original}", File(page.original).isFile)
        } finally {
            cleanUp(existing)
            UserSettings.importJson(settings)
            archive.delete()
        }
    }

    @Test
    fun aDamagedArchiveIsStillRejected() = runBlocking {
        val existing = repo.dao.allDocuments().map { it.id }.toSet()
        val settings = UserSettings.exportJson()
        val stamp = System.nanoTime()
        val archive = File(repo.exports, "damaged-$stamp.zip")
        val withoutImage = File(repo.exports, "damaged-no-image-$stamp.zip")
        val flipped = File(repo.exports, "damaged-flipped-$stamp.zip")
        try {
            val (doc, pages) = scanWithPages(1)
            BackupStore.backup(repo, archive)
            val entry =
                metadata(archive)
                    .getJSONArray("pages")
                    .let { a -> (0 until a.length()).map { DocumentCodec.page(a.getJSONObject(it)) } }
                    .single { it.id == pages.single().id }
                    .original
            // An image that is not listed as missing but is not in the archive: damaged, not skipped.
            rewrite(archive, withoutImage) { name, bytes -> if (name == entry) null else bytes }
            assertTrue(runCatching { BackupStore.restore(repo, Uri.fromFile(withoutImage)) }.isFailure)
            // An image whose bytes changed fails its integrity check.
            rewrite(archive, flipped) { name, bytes ->
                if (name == entry) bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else bytes
            }
            assertTrue(runCatching { BackupStore.restore(repo, Uri.fromFile(flipped)) }.isFailure)
            assertTrue(
                "a rejected restore must not leave documents behind",
                repo.dao.allDocuments().none { it.title == doc.title && it.id != doc.id && it.id !in existing },
            )
        } finally {
            cleanUp(existing)
            UserSettings.importJson(settings)
            listOf(archive, withoutImage, flipped).forEach { it.delete() }
        }
    }

    @Test
    fun aVersionThreeArchiveWithoutTheMissingListsStillRestores() = runBlocking {
        val existing = repo.dao.allDocuments().map { it.id }.toSet()
        val settings = UserSettings.exportJson()
        val stamp = System.nanoTime()
        val archive = File(repo.exports, "v3-$stamp.zip")
        val older = File(repo.exports, "v3-older-$stamp.zip")
        val fixed = File(repo.exports, "v3-fixed-$stamp.zip")
        try {
            val (doc, _) = scanWithPages(2)
            // An older archive has no list of skipped files, so it can only be complete. Export records whose file
            // is gone (left by earlier test runs on this device) would make the backup skip files; they are
            // dangling records, which a restore drops anyway, so they are cleared first.
            repo.dao.allOutputs().filterNot { File(it.path).isFile }.forEach { repo.dao.removeOutput(it.path) }
            BackupStore.backup(repo, archive)
            org.junit.Assume.assumeTrue(
                "the library still lists other files that are gone",
                metadata(archive).getJSONArray("missing").length() == 0,
            )
            // As written before "missing" and "missingPages" existed, with the metadata's integrity record updated.
            var rewritten = ByteArray(0)
            rewrite(archive, older) { name, bytes ->
                if (name == "paperforge.json")
                    JSONObject(String(bytes))
                        .apply {
                            remove("missing")
                            remove("missingPages")
                        }
                        .toString()
                        .toByteArray()
                        .also { rewritten = it }
                else bytes
            }
            rewrite(older, fixed) { name, bytes ->
                if (name == "integrity.json")
                    JSONObject(String(bytes))
                        .put("paperforge.json", JSONObject().put("bytes", rewritten.size).put("sha256", sha(rewritten)))
                        .toString()
                        .toByteArray()
                else bytes
            }
            val meta = metadata(fixed)
            assertEquals(3, meta.getInt("format"))
            assertFalse(meta.has("missing") || meta.has("missingPages"))
            val report = BackupStore.restoreReport(repo, Uri.fromFile(fixed))
            assertEquals(0, report.skippedPages)
            val restored =
                repo.dao.allDocuments().single { it.title == doc.title && it.id != doc.id && it.id !in existing }
            assertEquals(2, repo.dao.pages(restored.id).size)
        } finally {
            cleanUp(existing)
            UserSettings.importJson(settings)
            listOf(archive, older, fixed).forEach { it.delete() }
        }
    }
}

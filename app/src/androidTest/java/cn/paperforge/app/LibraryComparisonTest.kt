package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.reader.StudyNotes
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryComparisonTest {
    @Test
    fun comparesOriginalsAndVisualCandidatesWithoutDeletingAndRestoresIndependentVersionGroups() =
        runBlocking {
            val instrument = InstrumentationRegistry.getInstrumentation()
            val app = instrument.targetContext.applicationContext as PaperForgeApp
            val repo = app.repository
            val before = repo.dao.allDocuments().map { it.id }.toSet()
            val preferences = LibraryPreferences(app)
            val original = preferences.export()
            val notes = StudyNotes.load(app).map { it.id }.toSet()
            val prefix = "compare-${UUID.randomUUID().toString().take(8)}"
            val archive = File(repo.exports, "compare-${System.nanoTime()}.zip")
            suspend fun scan(name: String) =
                repo.newScan().copy(title = "$prefix-$name").also { repo.dao.updateDocument(it) }
            suspend fun page(doc: DocumentRow, file: File) =
                PageRow(UUID.randomUUID().toString(), doc.id, 0, file.path, filter = "ORIGINAL")
                    .also { repo.dao.putPage(it) }
            try {
                val first = scan("source")
                val firstFile = repo.captureFile(first.id)
                instrument.context.assets.open("scan-core/dark-desk-0.jpg").use { input ->
                    firstFile.outputStream().use { input.copyTo(it) }
                }
                val firstPage = page(first, firstFile)
                val copy =
                    repo.copyPages(first.id, setOf(firstPage.id), null, "$prefix-copy").document
                val variant = scan("reencoded")
                val png = File(repo.folder(variant.id), "same-pixels.png")
                BitmapFactory.decodeFile(firstFile.path).let { bitmap ->
                    try {
                        png.outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                page(variant, png)
                val cropped = scan("different-crop")
                val croppedFile = repo.captureFile(cropped.id)
                firstFile.copyTo(croppedFile)
                page(cropped, croppedFile).also {
                    repo.dao.updatePage(it.copy(corners = "0.1,0.0;1.0,0.0;1.0,1.0;0.1,1.0"))
                }
                val blankA = scan("blank-white")
                val blankB = scan("blank-grey")
                for ((doc, color) in
                    listOf(blankA to Color.WHITE, blankB to Color.rgb(245, 245, 245))) {
                    val file = repo.captureFile(doc.id)
                    val bitmap =
                        Bitmap.createBitmap(180, 250, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(color)
                        }
                    try {
                        file.outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    page(doc, file)
                }
                val pdfA =
                    scan("pdf-a").let { doc ->
                        val file = File(repo.folder(doc.id), "source.pdf")
                        PDDocument().use { pdf ->
                            pdf.addPage(PDPage())
                            pdf.save(file)
                        }
                        doc.copy(kind = "pdf", pdfPath = file.path).also {
                            repo.dao.updateDocument(it)
                        }
                    }
                val pdfB =
                    scan("pdf-b").let { doc ->
                        val file = File(repo.folder(doc.id), "source.pdf")
                        File(pdfA.pdfPath!!).copyTo(file)
                        doc.copy(kind = "pdf", pdfPath = file.path).also {
                            repo.dao.updateDocument(it)
                        }
                    }
                val missing = scan("missing-source")
                page(missing, File(repo.folder(missing.id), "absent.jpg"))
                val documents =
                    listOf(first, copy, variant, cropped, blankA, blankB, pdfA, pdfB, missing)
                val beforeCompare = repo.dao.allDocuments().map { it.id }.toSet()
                val report = repo.compareDocuments(documents.map { it.id }.toSet())
                assertEquals(documents.size, report.documents)
                assertTrue(report.exact.any { it.toSet() == setOf(first.id, copy.id) })
                assertTrue(report.exact.any { it.toSet() == setOf(pdfA.id, pdfB.id) })
                assertFalse(report.exact.any { variant.id in it || cropped.id in it })
                fun SimilarPage.ids() = setOf(this.first.documentId, this.second.documentId)
                assertTrue(
                    report.similar.any {
                        variant.id in it.ids() &&
                            it.ids().any { id -> id in setOf(first.id, copy.id) } &&
                            !it.sameOriginal
                    }
                )
                assertTrue(report.similar.any { cropped.id in it.ids() && it.sameOriginal })
                assertFalse(report.similar.any { it.ids() == setOf(blankA.id, blankB.id) })
                assertTrue(report.skipped.any { it.contains(missing.title) })
                assertEquals(beforeCompare, repo.dao.allDocuments().map { it.id }.toSet())
                assertTrue(documents.all { repo.dao.document(it.id)!!.trashedAt == null })

                val family = preferences.group("$prefix-versions", listOf(first.id, copy.id))
                preferences.updateFamily(family.id, preferred = copy.id)
                val merged = preferences.group("$prefix-versions", listOf(copy.id, variant.id))
                assertEquals(setOf(first.id, copy.id, variant.id), merged.members.toSet())
                assertEquals(
                    1,
                    preferences.families().count { it.members.any { id -> id in merged.members } },
                )
                preferences.updateFamily(merged.id, preferred = variant.id)
                repo.deleteDocument(missing)
                val originalIds = repo.dao.allDocuments().map { it.id }.toSet()
                BackupStore.backup(repo, archive)
                BackupStore.restore(repo, Uri.fromFile(archive))
                val restored =
                    preferences.families().single { it.id != merged.id && it.name == merged.name }
                assertEquals(3, restored.members.size)
                assertTrue(restored.members.none { it in originalIds })
                assertEquals(
                    variant.title,
                    repo.dao.document(checkNotNull(restored.preferred))!!.title,
                )
                assertEquals(
                    setOf(first.title, copy.title, variant.title),
                    restored.members.map { repo.dao.document(it)!!.title }.toSet(),
                )
                preferences.updateFamily(restored.id, remove = restored.preferred)
                assertEquals(2, preferences.families().single { it.id == restored.id }.members.size)
                assertEquals(3, preferences.families().single { it.id == merged.id }.members.size)
                preferences.dissolveFamily(restored.id)
                assertTrue(restored.members.all { repo.dao.document(it) != null })
            } finally {
                archive.delete()
                repo.dao
                    .allDocuments()
                    .filter { it.id !in before }
                    .forEach { repo.deleteDocument(it) }
                preferences.restore(original)
                StudyNotes.load(app)
                    .filter { it.id !in notes }
                    .forEach { StudyNotes.remove(app, it.id) }
            }
        }
}

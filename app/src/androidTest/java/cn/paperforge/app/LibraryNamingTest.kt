package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryNamingTest {
    @Test
    fun previewResolvesConflictsAndUndoRestoresOnlyUnchangedNamesIncludingAfterBackup() =
        runBlocking {
            val instrument = InstrumentationRegistry.getInstrumentation()
            val app = instrument.targetContext.applicationContext as PaperForgeApp
            val repo = app.repository
            val originalDocs = repo.dao.allDocuments().map { it.id }.toSet()
            val preferences = LibraryPreferences(app)
            val originalPreferences = preferences.export()
            val notes = cn.paperforge.app.reader.StudyNotes.load(app).map { it.id }.toSet()
            val archive = File(repo.exports, "names-${System.nanoTime()}.zip")
            val scratch = mutableListOf<File>()
            val time = LocalDateTime.of(2026, 10, 25, 8, 0)
            val prefix = "names-" + UUID.randomUUID().toString().take(8)
            val project = "归档-$prefix"
            try {
                val first = repo.newScan()
                val second = repo.newScan()
                repo.rename(first, "First source")
                repo.rename(second, "Second source")
                val selected = listOf(repo.dao.document(first.id)!!, repo.dao.document(second.id)!!)
                assertEquals(
                    "{First source}",
                    NameTemplate.render(selected.first(), NamingOptions("{{{name}}}"), 1, time),
                )
                val occupied = repo.newScan().copy(title = "$prefix-20261025-扫描-001")
                repo.dao.updateDocument(occupied)
                val options = NamingOptions(pattern = prefix + "-{date:yyyyMMdd}-{type}-{n:03}")
                val planned = NameTemplate.preview(selected, repo.dao.allDocuments(), options, time)
                assertEquals("$prefix-20261025-扫描-001 (2)", planned[0].after)
                assertEquals("$prefix-20261025-扫描-002", planned[1].after)
                assertTrue(planned.first().collision)
                repo.renameBatch(planned, options)
                repo.rename(repo.dao.document(first.id)!!, "Later manual title")
                val undone = repo.undoBatchRename()
                assertEquals(1, undone.restored)
                assertEquals(1, undone.preserved)
                assertEquals("Later manual title", repo.dao.document(first.id)!!.title)
                assertEquals("Second source", repo.dao.document(second.id)!!.title)
                val custom = NamingOptions("{field:项目}-{n:02}", 7, mapOf("项目" to project))
                val next =
                    NameTemplate.preview(
                        listOf(repo.dao.document(first.id)!!, repo.dao.document(second.id)!!),
                        repo.dao.allDocuments(),
                        custom,
                        time,
                    )
                repo.renameBatch(next, custom)
                assertEquals("$project-07", repo.dao.document(first.id)!!.title)
                val unicode = NamingOptions("{field}", fields = mapOf("项目" to "合同📁".repeat(100)))
                val longNames = NameTemplate.preview(selected, emptyList(), unicode, time)
                assertNotEquals(longNames[0].after, longNames[1].after)
                longNames.forEach { change ->
                    assertTrue(change.after.toByteArray(Charsets.UTF_8).size <= 200)
                    File(
                            repo.exports,
                            change.after + "-" + UUID.randomUUID().toString().take(8) + ".pdf",
                        )
                        .also {
                            assertTrue(it.createNewFile())
                            scratch += it
                        }
                }
                BackupStore.backup(repo, archive)
                BackupStore.restore(repo, Uri.fromFile(archive))
                val restored =
                    repo.dao.allDocuments().filter {
                        it.id !in originalDocs && it.id !in listOf(first.id, second.id, occupied.id)
                    }
                val copiedFirst = restored.single { it.title == "$project-07" }
                val copiedSecond = restored.single { it.title == "$project-08" }
                assertEquals(
                    setOf(copiedFirst.id, copiedSecond.id),
                    preferences.renameUndo().map { it.documentId }.toSet(),
                )
                assertEquals(project, preferences.naming().fields["项目"])
                val restoredUndo = repo.undoBatchRename()
                assertEquals(2, restoredUndo.restored)
                assertEquals("Later manual title", repo.dao.document(copiedFirst.id)!!.title)
                assertEquals("Second source", repo.dao.document(copiedSecond.id)!!.title)
                assertEquals("$project-07", repo.dao.document(first.id)!!.title)
            } finally {
                scratch.forEach { it.delete() }
                archive.delete()
                repo.dao
                    .allDocuments()
                    .filter { it.id !in originalDocs }
                    .forEach { repo.deleteDocument(it) }
                preferences.restore(originalPreferences)
                cn.paperforge.app.reader.StudyNotes.load(app)
                    .filter { it.id !in notes }
                    .forEach { cn.paperforge.app.reader.StudyNotes.remove(app, it.id) }
            }
        }
}

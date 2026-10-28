package cn.paperforge.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyOutputNamesFlowTest {
    @Test
    fun oldRenamedDocumentsUseTitlesWithoutChangingExplicitFileTemplates() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val db = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
            val repo = Repository(app, db)
            val folder =
                File(app.cacheDir, "owned-legacy-names-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                for ((filename, title, expected) in
                    listOf(
                        Triple("扫描-extract-12ab34cd.pdf", "已经重命名的资料", "已经重命名的资料.pdf"),
                        Triple("original.pdf", "合同", "合同.pdf"),
                        Triple("2026-自定义模板.pdf", "自定义模板", "2026-自定义模板.pdf"),
                        Triple("用户文件-12ab34cd.pdf", "用户文件-12ab34cd", "用户文件-12ab34cd.pdf"),
                    )) {
                    val file = File(folder, filename).apply { writeText("owned filename fixture") }
                    repo.registerPdf(file, title)
                    assertEquals(expected, OutputNames.display(repo, file))
                }
            } finally {
                for (doc in repo.dao.allDocuments()) repo.deleteDocument(doc)
                folder.delete()
                db.close()
            }
        }
}

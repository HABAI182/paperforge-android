package cn.paperforge.app.quality

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.CjkSearch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchIndexTest {
    @Test fun cjkBigramsAndLatinQueryFollowOcrEditsAndTrash()=runBlocking {
        val repo=ApplicationProvider.getApplicationContext<PaperForgeApp>().repository;val doc=repo.newScan()
        val page=PageRow("fts-${doc.id}",doc.id,0,File(repo.folder(doc.id),"dummy.jpg").path,ocrText="清晰讲义，中文扫描 PaperForge ABC123")
        try {
            repo.dao.putPage(page)
            for(query in listOf("中文扫描","中","paperforge","abc123","\"中文扫描\" OR *")) {
                val normalized=CjkSearch.normalize(query)
                val found=repo.dao.search(normalized).first()
                if(!query.contains(" OR ")) assertTrue("Missing $query",found.any { it.id==doc.id })
            }
            repo.trash(doc);assertFalse(repo.dao.search("中文扫描").first().any { it.id==doc.id });repo.restore(doc)
            repo.dao.updatePage(page.copy(ocrText="更新后的新内容"))
            assertFalse(repo.dao.search("中文扫描").first().any { it.id==doc.id })
            assertTrue(repo.dao.search("新内容").first().any { it.id==doc.id })
            repo.dao.deletePage(page.id);assertFalse(repo.dao.search("新内容").first().any { it.id==doc.id })
        } finally { repo.deleteDocument(doc) }
    }
}

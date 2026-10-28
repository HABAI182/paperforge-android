package cn.paperforge.app.data

import android.util.AtomicFile
import androidx.room.withTransaction
import cn.paperforge.app.imaging.RetouchRequest
import cn.paperforge.app.pdf.PdfAssembly
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*

/** Source-bound draft is a page resource, so normal backup/remap retains it. */
object RetouchDrafts {
    private val lock = Mutex()

    suspend fun sourceKey(page: PageRow): String =
        listOf(
                PdfAssembly.sha256(File(page.original)),
                page.corners,
                page.rotation,
                page.filter,
                page.brightness,
                page.contrast,
                page.enhanceStrength,
                page.outputAspect,
                page.deskewDegrees,
            )
            .joinToString("|")

    fun load(page: PageRow, key: String): RetouchRequest? =
        PageResources.entries(page)
            .lastOrNull { it.role == "retouch-draft" }
            ?.let { resource ->
                runCatching {
                        val json = JSONObject(File(resource.path).readText())
                        if (json.getString("source") == key)
                            RetouchRequest.decode(json.getJSONObject("request"))
                        else null
                    }
                    .getOrNull()
            }

    suspend fun save(repo: Repository, page: PageRow, key: String, request: RetouchRequest) =
        lock.withLock {
            repo.database.withTransaction {
                val current =
                    repo.dao.pages(page.documentId).firstOrNull { it.id == page.id }
                        ?: return@withTransaction
                require(sourceKey(current) == key) { "页面已变化，请重新打开修复工作台" }
                val resources = PageResources.entries(current)
                val prior = resources.lastOrNull { it.role == "retouch-draft" }
                val file =
                    prior?.path?.let(::File)
                        ?: File(repo.folder(page.documentId), "retouch-${page.id}.json")
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(
                        JSONObject()
                            .put("source", key)
                            .put("request", request.json())
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                    atomic.finishWrite(stream)
                } catch (e: Throwable) {
                    atomic.failWrite(stream)
                    throw e
                }
                val metadata =
                    PageResources.metadata(current)
                        .put(
                            "resources",
                            DocumentResources.encode(
                                resources.filterNot { it.role == "retouch-draft" } +
                                    PageResource(
                                        prior?.id ?: "retouch-draft-${page.id}",
                                        "retouch-draft",
                                        file.path,
                                    )
                            ),
                        )
                repo.dao.updatePage(current.copy(processingMetadata = metadata.toString()))
            }
        }
}

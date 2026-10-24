package cn.paperforge.app.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageBucket(
    val id: String,
    val title: String,
    val bytes: Long,
    val files: Int,
    val cache: Boolean = false,
)

data class StorageReport(val buckets: List<StorageBucket>, val protectedPaths: Set<String>) {
    val total
        get() = buckets.sumOf { it.bytes }

    val reclaimable
        get() = buckets.filter { it.cache }.sumOf { it.bytes }
}

/** Only explicitly regenerable folders can be cleaned, with live/history references excluded. */
class LibraryStorage(private val repo: Repository) {
    private val caches = linkedMapOf("document-covers" to "文档缩略图", "reader-text" to "阅读补识别缓存")

    suspend fun report(): StorageReport =
        withContext(Dispatchers.IO) {
            val docs = repo.dao.allDocuments()
            val pages = repo.dao.allPages()
            val current =
                (docs.mapNotNull { it.pdfPath } +
                        pages.flatMap { PageResources.paths(it) } +
                        repo.dao.allOutputs().map { it.path })
                    .toSet()
            val history =
                repo.dao
                    .allHistory()
                    .flatMap { entry ->
                        (HistoryState.pages(entry.beforeJson) + HistoryState.pages(entry.afterJson))
                            .flatMap { PageResources.paths(it) }
                    }
                    .toSet()
            fun canonical(paths: Collection<String>) = paths.map { File(it).canonicalPath }.toSet()
            val currentPaths = canonical(current)
            val historyPaths = canonical(history)
            val protected = currentPaths + historyPaths
            val assigned = mutableSetOf<String>()
            fun bucket(
                id: String,
                title: String,
                files: Sequence<File>,
                cache: Boolean = false,
            ): StorageBucket {
                val selected =
                    files
                        .filter { it.isFile }
                        .map { it.canonicalFile }
                        .filter { assigned.add(it.path) }
                        .toList()
                return StorageBucket(
                    id,
                    title,
                    selected.sumOf { it.length() },
                    selected.size,
                    cache,
                )
            }
            val buckets = mutableListOf<StorageBucket>()
            buckets += bucket("exports", "导出文件（含已入库结果）", repo.exports.walkTopDown())
            buckets += bucket("current", "原件与工作页", currentPaths.asSequence().map(::File))
            buckets +=
                bucket("history", "历史保留资源", (historyPaths - currentPaths).asSequence().map(::File))
            buckets +=
                bucket(
                    "annotations",
                    "批注草稿",
                    docs.asSequence().map { File(repo.folder(it.id), "annotations.json") },
                )
            buckets +=
                bucket(
                    "documents-other",
                    "文档其他资源",
                    File(repo.context.filesDir, "documents").walkTopDown(),
                )
            val database = repo.context.getDatabasePath("paperforge.db")
            buckets +=
                bucket(
                    "database",
                    "文档数据库与识别文字",
                    sequenceOf(database, File(database.path + "-wal"), File(database.path + "-shm")),
                )
            caches.forEach { (id, title) ->
                buckets +=
                    bucket(
                        id,
                        title,
                        File(repo.context.cacheDir, id).walkTopDown().filter {
                            it.canonicalPath !in protected
                        },
                        true,
                    )
            }
            buckets += bucket("cache-other", "其他运行缓存", repo.context.cacheDir.walkTopDown())
            StorageReport(buckets, protected)
        }

    suspend fun clearCaches(ids: Set<String>): Long =
        withContext(Dispatchers.IO) {
            val protected = report().protectedPaths
            var bytes = 0L
            ids.filter { it in caches }
                .forEach { id ->
                    val directory = File(repo.context.cacheDir, id).canonicalFile
                    require(directory.parentFile == repo.context.cacheDir.canonicalFile)
                    directory
                        .walkBottomUp()
                        .filter { it.isFile && it.canonicalPath !in protected }
                        .forEach { file ->
                            val size = file.length()
                            if (file.delete()) bytes += size
                        }
                }
            bytes
        }
}

package cn.paperforge.app.data

import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import java.util.zip.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

object BackupStore {
    suspend fun backup(repo: Repository, output: File) {
        val documents = repo.dao.allDocuments()
        val pages = repo.dao.allPages()
        val history = repo.dao.allHistory()
        val outputs = repo.dao.allOutputs()
        val paths =
            (documents.mapNotNull { it.pdfPath } +
                    documents.flatMap {
                        DocumentResources.entries(repo, it.id).map { r -> r.path }
                    } +
                    pages.flatMap { PageResources.paths(it) } +
                    outputs.map { it.path } +
                    history.flatMap {
                        listOf(
                            *HistoryState.pages(it.beforeJson)
                                .flatMap { p -> PageResources.paths(p) }
                                .toTypedArray(),
                            *HistoryState.pages(it.afterJson)
                                .flatMap { p -> PageResources.paths(p) }
                                .toTypedArray(),
                        )
                    })
                .distinct()
        val mapping =
            paths.associateWith {
                "files/${UUID.randomUUID()}.${File(it).extension.ifBlank { "bin" }}"
            }
        fun page(p: PageRow) = DocumentCodec.page(PageResources.remap(p, mapping::getValue))
        val metadata =
            JSONObject()
                .put("format", 3)
                .put(
                    "documentResources",
                    JSONObject().apply {
                        documents.forEach { doc ->
                            put(
                                doc.id,
                                DocumentResources.encode(
                                    DocumentResources.entries(repo, doc.id),
                                    mapping::getValue,
                                ),
                            )
                        }
                    },
                )
                .put("library", LibraryPreferences(repo.context).export())
                .put(
                    "outputs",
                    JSONArray().apply {
                        outputs.forEach { put(it.copy(path = mapping.getValue(it.path)).encode()) }
                    },
                )
                .put(
                    "studyNotes",
                    cn.paperforge.app.reader.StudyNotes.encode(
                        cn.paperforge.app.reader.StudyNotes.load(repo.context)
                    ),
                )
                .put("preferences", JSONObject(cn.paperforge.app.UserSettings.exportJson()))
                .put(
                    "reader",
                    cn.paperforge.app
                        .ReaderPreferences(repo.context)
                        .export(documents.map { it.id }),
                )
                .put(
                    "annotations",
                    JSONObject().apply {
                        documents.forEach { doc ->
                            val draft = File(repo.folder(doc.id), "annotations.json")
                            if (draft.exists()) put(doc.id, JSONArray(draft.readText()))
                        }
                    },
                )
                .put(
                    "signature",
                    repo.context.getSharedPreferences("signature", 0).getString("strokes", "[]"),
                )
                .put(
                    "documents",
                    JSONArray().apply {
                        documents.forEach {
                            put(
                                DocumentCodec.document(
                                    it.copy(pdfPath = it.pdfPath?.let(mapping::getValue))
                                )
                            )
                        }
                    },
                )
                .put("pages", JSONArray().apply { pages.forEach { put(page(it)) } })
                .put(
                    "history",
                    JSONArray().apply {
                        history.forEach {
                            put(
                                JSONObject()
                                    .put("documentId", it.documentId)
                                    .put("pageId", it.pageId)
                                    .put(
                                        "before",
                                        HistoryState.map(it.beforeJson) { p ->
                                            PageResources.remap(p, mapping::getValue)
                                        },
                                    )
                                    .put(
                                        "after",
                                        HistoryState.map(it.afterJson) { p ->
                                            PageResources.remap(p, mapping::getValue)
                                        },
                                    )
                                    .put("applied", it.applied)
                            )
                        }
                    },
                )
        val temporary = File(output.path + ".part")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                val integrity = JSONObject()
                val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry("paperforge.json"))
                zip.write(metadataBytes)
                zip.closeEntry()
                integrity.put(
                    "paperforge.json",
                    JSONObject().put("bytes", metadataBytes.size).put("sha256", sha(metadataBytes)),
                )
                paths.forEach { path ->
                    coroutineContext.ensureActive()
                    zip.putNextEntry(ZipEntry(mapping.getValue(path)))
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val bytes =
                        java.security.DigestInputStream(File(path).inputStream(), digest).use {
                            it.copyTo(zip)
                        }
                    zip.closeEntry()
                    integrity.put(
                        mapping.getValue(path),
                        JSONObject().put("bytes", bytes).put("sha256", hex(digest.digest())),
                    )
                }
                zip.putNextEntry(ZipEntry("integrity.json"))
                zip.write(integrity.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            check(temporary.renameTo(output))
        } finally {
            temporary.delete()
        }
    }

    suspend fun restore(repo: Repository, uri: Uri): Int {
        val archive = File(repo.context.cacheDir, "restore-${UUID.randomUUID()}.zip")
        repo.context.contentResolver.openInputStream(uri)?.use { input ->
            archive.outputStream().use { input.copyTo(it) }
        } ?: error("无法读取备份")
        val created = mutableListOf<File>()
        val sideState = RestoreSideState(repo.context)
        var sideChanged = false
        try {
            ZipFile(archive).use { zip ->
                val metadata =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("paperforge.json") ?: error("不是纸匠备份文件"))
                            .bufferedReader()
                            .use { it.readText() }
                    )
                require(metadata.getInt("format") in 1..3) { "不支持的备份版本" }
                val integrity =
                    zip.getEntry("integrity.json")?.let { entry ->
                        JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                    }
                if (metadata.getInt("format") >= 2) {
                    require(integrity != null) { "备份缺少完整性清单" }
                    val raw =
                        zip.getInputStream(zip.getEntry("paperforge.json")).use { it.readBytes() }
                    require(
                        sha(raw) == integrity.getJSONObject("paperforge.json").getString("sha256")
                    ) {
                        "备份目录校验失败"
                    }
                }
                val d = metadata.getJSONArray("documents")
                val p = metadata.getJSONArray("pages")
                val h = metadata.optJSONArray("history") ?: JSONArray()
                val documents =
                    (0 until d.length()).map { DocumentCodec.document(d.getJSONObject(it)) }
                val ids = documents.associate { it.id to UUID.randomUUID().toString() }
                val pageIds = mutableMapOf<String, String>()
                val files = mutableMapOf<Pair<String, String>, String>()
                fun copy(entry: String, docId: String): String =
                    files.getOrPut(entry to docId) {
                        require(
                            entry.startsWith("files/") &&
                                !entry.contains("..") &&
                                !entry.contains('\\')
                        ) {
                            "备份路径不合法"
                        }
                        val source = zip.getEntry(entry) ?: error("备份缺少文件")
                        val target =
                            File(
                                if (docId.isEmpty()) repo.exports else repo.folder(docId),
                                "${UUID.randomUUID()}.${File(entry).extension}",
                            )
                        created.add(target)
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val copied =
                            java.security
                                .DigestInputStream(zip.getInputStream(source), digest)
                                .use { input -> target.outputStream().use { input.copyTo(it) } }
                        (if (metadata.getInt("format") >= 2)
                                checkNotNull(integrity).getJSONObject(entry)
                            else integrity?.optJSONObject(entry))
                            ?.let { expected ->
                                require(
                                    copied == expected.getLong("bytes") &&
                                        hex(digest.digest()) == expected.getString("sha256")
                                ) {
                                    "备份资源校验失败：$entry"
                                }
                            }
                        target.path
                    }
                fun page(old: PageRow): PageRow {
                    val docId = ids[old.documentId] ?: error("备份页面缺少所属文档")
                    return PageResources.remap(old) { path -> copy(path, docId) }
                        .copy(
                            id = pageIds.getOrPut(old.id) { UUID.randomUUID().toString() },
                            documentId = docId,
                        )
                }
                val restored =
                    documents.map { old ->
                        val id = ids.getValue(old.id)
                        old.copy(id = id, pdfPath = old.pdfPath?.let { copy(it, id) })
                    }
                val annotations = metadata.optJSONObject("annotations")
                metadata.optJSONObject("documentResources")?.let { resources ->
                    documents.forEach { doc ->
                        resources.optJSONArray(doc.id)?.let { array ->
                            val id = ids.getValue(doc.id)
                            val entries =
                                DocumentResources.decode(array).map {
                                    it.copy(path = copy(it.path, id))
                                }
                            created.add(DocumentResources.write(repo, id, entries))
                        }
                    }
                }
                documents.forEach { old ->
                    annotations?.optJSONArray(old.id)?.let { marks ->
                        File(repo.folder(ids.getValue(old.id)), "annotations.json").also {
                            it.writeText(marks.toString())
                            created.add(it)
                        }
                    }
                }
                val pages =
                    (0 until p.length()).map { page(DocumentCodec.page(p.getJSONObject(it))) }
                val histories =
                    (0 until h.length()).map { i ->
                        val old = h.getJSONObject(i)
                        val before =
                            HistoryState.map(old.getJSONObject("before").toString(), ::page)
                        val after = HistoryState.map(old.getJSONObject("after").toString(), ::page)
                        EditHistory(
                            documentId = ids.getValue(old.getString("documentId")),
                            pageId =
                                pageIds.getOrPut(old.getString("pageId")) {
                                    UUID.randomUUID().toString()
                                },
                            beforeJson = before.toString(),
                            afterJson = after.toString(),
                            applied = old.getBoolean("applied"),
                        )
                    }
                val outputRows =
                    metadata
                        .optJSONArray("outputs")
                        ?.let { array ->
                            (0 until array.length())
                                .map { OutputRow.decode(array.getJSONObject(it)) }
                                .map { old ->
                                    val outputId = old.outputDocumentId?.let(ids::get)
                                    old.copy(
                                        path = copy(old.path, outputId ?: ""),
                                        sourceDocumentId = old.sourceDocumentId?.let(ids::get),
                                        outputDocumentId = outputId,
                                    )
                                }
                        }
                        .orEmpty()
                repo.database.withTransaction {
                    restored.forEach { repo.dao.putDocument(it) }
                    pages.forEach { repo.dao.putPage(it) }
                    histories.forEach { repo.dao.addHistory(it) }
                    outputRows.forEach { repo.dao.putOutput(it) }
                    sideChanged = true
                    metadata.optJSONObject("library")?.let {
                        LibraryPreferences(repo.context).restore(it)
                    }
                    val signatures = repo.context.getSharedPreferences("signature", 0)
                    if (!signatures.contains("strokes") && metadata.has("signature"))
                        signatures
                            .edit()
                            .putString("strokes", metadata.getString("signature"))
                            .apply()
                    metadata.optJSONObject("reader")?.let {
                        cn.paperforge.app.ReaderPreferences(repo.context).restore(it, ids)
                    }
                    metadata.optJSONArray("studyNotes")?.let {
                        cn.paperforge.app.reader.StudyNotes.restore(repo.context, it, ids)
                    }
                    metadata.optJSONObject("preferences")?.let {
                        cn.paperforge.app.UserSettings.importJson(it.toString())
                    }
                }
                restored
                    .filter { it.kind == "pdf" }
                    .forEach { LibraryIndexer.enqueue(repo.context, it.id) }
                return restored.size
            }
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(
                kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO
            ) {
                if (sideChanged)
                    runCatching { sideState.restore() }.onFailure { e.addSuppressed(it) }
                created.forEach { it.delete() }
                created.mapNotNull { it.parentFile }.distinct().forEach { it.delete() }
            }
            throw e
        } finally {
            archive.delete()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sha(bytes: ByteArray) =
        hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
}

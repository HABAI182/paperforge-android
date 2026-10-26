package cn.paperforge.app.data

import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import java.util.zip.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object BackupStore {
    private data class Snapshot(
        val documents: List<DocumentRow>,
        val pages: List<PageRow>,
        val history: List<EditHistory>,
        val outputs: List<OutputRow>,
        val tasks: List<LocalTaskRow>,
    )

    suspend fun backup(repo: Repository, output: File) {
        val (documents, pages, history, outputs, tasks) =
            repo.database.withTransaction {
                Snapshot(
                    repo.dao.allDocuments(),
                    repo.dao.allPages(),
                    repo.dao.allHistory(),
                    repo.dao.allOutputs(),
                    repo.dao.allTasks(),
                )
            }
        val taskPaths = tasks.flatMap { it.resourceEntries() }.map { it.path }.distinct()
        val paths =
            (documents.mapNotNull { it.pdfPath } +
                    documents.flatMap {
                        DocumentResources.entries(repo, it.id).map { r -> r.path }
                    } +
                    pages.flatMap { PageResources.paths(it) } +
                    outputs.map { it.path } +
                    taskPaths.filter { File(it).isFile } +
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
            (paths.associateWith {
                "files/${UUID.randomUUID()}.${File(it).extension.ifBlank { "bin" }}"
            } +
                (taskPaths - paths.toSet()).associateWith {
                    "pending/${UUID.randomUUID()}.${File(it).extension.ifBlank { "bin" }}"
                })
        fun page(p: PageRow) = DocumentCodec.page(PageResources.remap(p, mapping::getValue))
        val metadata =
            JSONObject()
                .put("format", 3)
                .put(
                    "tasks",
                    JSONArray().apply {
                        tasks.forEach { row ->
                            put(
                                row.copy(
                                        payload =
                                            if (row.type in DocumentJobs.types)
                                                DocumentJobs.remapPayload(row.payload, mapping)
                                            else row.payload,
                                        resources =
                                            DocumentResources.encode(
                                                    row.resourceEntries(),
                                                    mapping::getValue,
                                                )
                                                .toString(),
                                    )
                                    .encode()
                            )
                        }
                    },
                )
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
                .put("profiles", SettingsProfiles(repo.context).export())
                .put("assemblyDraft", cn.paperforge.app.pdf.AssemblyDraft.read(repo.context).json())
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
        var published = false
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
                val historicalDocumentIds = mutableMapOf<String, String>()
                val pageIds = mutableMapOf<String, String>()
                val taskIds = mutableMapOf<String, String>()
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
                fun page(old: PageRow, historyOwner: String? = null): PageRow {
                    val docId =
                        ids[old.documentId]
                            ?: if (historyOwner != null)
                                historicalDocumentIds.getOrPut(old.documentId) {
                                    UUID.randomUUID().toString()
                                }
                            else error("备份页面缺少所属文档")
                    val resourceOwner = ids[old.documentId] ?: checkNotNull(historyOwner)
                    val remapped = PageResources.remap(old) { path -> copy(path, resourceOwner) }
                    val processing =
                        JSONObject(remapped.processingMetadata)
                            .apply {
                                optJSONArray("pageOrigins")?.let { origins ->
                                    for (i in 0 until origins.length()) {
                                        val origin = origins.getJSONObject(i)
                                        val document = origin.optString("documentId")
                                        val sourcePage = origin.optString("pageId")
                                        origin.put("documentId", ids[document] ?: JSONObject.NULL)
                                        origin.put(
                                            "pageId",
                                            if (sourcePage.isNotBlank() && sourcePage != "null")
                                                pageIds.getOrPut(sourcePage) {
                                                    UUID.randomUUID().toString()
                                                }
                                            else JSONObject.NULL,
                                        )
                                    }
                                }
                                optString("captureJob")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { id ->
                                        put(
                                            "captureJob",
                                            taskIds.getOrPut(id) { UUID.randomUUID().toString() },
                                        )
                                    }
                            }
                            .toString()
                    return remapped.copy(
                        id = pageIds.getOrPut(old.id) { UUID.randomUUID().toString() },
                        documentId = docId,
                        processingMetadata = processing,
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
                        fun state(key: String) =
                            HistoryState.remap(
                                old.getJSONObject(key).toString(),
                                { page(it, ids.getValue(old.getString("documentId"))) },
                            ) { id ->
                                pageIds.getOrPut(id) { UUID.randomUUID().toString() }
                            }
                        val before = state("before")
                        val after = state("after")
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
                val taskRows =
                    metadata
                        .optJSONArray("tasks")
                        ?.let { array ->
                            (0 until array.length())
                                .map { LocalTaskRow.decode(array.getJSONObject(it)) }
                                .map { old ->
                                    val document = old.documentId?.let(ids::get)
                                    val resources =
                                        if (document == null && old.type == "CAPTURE") emptyList()
                                        else
                                            old.resourceEntries().map { resource ->
                                                val path =
                                                    if (resource.path.startsWith("pending/"))
                                                        files.getOrPut(
                                                            resource.path to (document ?: "")
                                                        ) {
                                                            File(
                                                                    repo.folder(document ?: ""),
                                                                    "${UUID.randomUUID()}.jpg",
                                                                )
                                                                .path
                                                        }
                                                    else copy(resource.path, document ?: "")
                                                resource.copy(path = path)
                                            }
                                    var payload = old.payload
                                    if (old.type in DocumentJobs.types) {
                                        val paths =
                                            old.resourceEntries().zip(resources).associate {
                                                (before, after) ->
                                                before.path to after.path
                                            }
                                        payload =
                                            DocumentJobs.remapPayload(
                                                payload,
                                                paths + ids + pageIds,
                                            )
                                        resources
                                            .firstOrNull { it.id == "manifest" }
                                            ?.let { resource ->
                                                val file = File(resource.path)
                                                val plan =
                                                    cn.paperforge.app.pdf.AssemblyPlan.decode(
                                                        JSONObject(file.readText())
                                                    )
                                                val sourceIds = ids.toMutableMap()
                                                plan.sources.forEach { source ->
                                                    sourceIds.getOrPut(source.documentId) {
                                                        UUID.randomUUID().toString()
                                                    }
                                                }
                                                file.writeText(
                                                    plan.remap(sourceIds).json().toString()
                                                )
                                            }
                                        if (old.type == "CONVERT") {
                                            val restored = JSONObject(payload)
                                            val checkpoint = restored.optJSONObject("checkpoint")
                                            checkpoint?.keys()?.asSequence()?.toList()?.forEach {
                                                key ->
                                                if (key.startsWith("recipe-step-"))
                                                    checkpoint.optJSONObject(key)?.let { stage ->
                                                        if (stage.has("destinationUri")) {
                                                            stage.remove("destinationUri")
                                                            stage.put("complete", false)
                                                        }
                                                    }
                                            }
                                            payload = restored.toString()
                                        }
                                        if (old.type == "SAVE")
                                            payload =
                                                JSONObject(payload)
                                                    .apply {
                                                        getJSONObject("input").remove("uri")
                                                        getJSONObject("input").remove("treeUri")
                                                        optJSONObject("checkpoint")
                                                            ?.remove("destinationUri")
                                                    }
                                                    .toString()
                                    }
                                    old.copy(
                                        payload = payload,
                                        id =
                                            taskIds.getOrPut(old.id) {
                                                UUID.randomUUID().toString()
                                            },
                                        documentId = document,
                                        pageId =
                                            old.pageId?.let {
                                                pageIds.getOrPut(it) {
                                                    UUID.randomUUID().toString()
                                                }
                                            },
                                        targetPageId =
                                            old.targetPageId?.let {
                                                pageIds.getOrPut(it) {
                                                    UUID.randomUUID().toString()
                                                }
                                            },
                                        resources = DocumentResources.encode(resources).toString(),
                                        workId = UUID.randomUUID().toString(),
                                        state =
                                            if (document == null && old.type == "CAPTURE")
                                                LocalTaskState.CANCELLED
                                            else if (old.state in LocalTaskRow.active)
                                                if (old.type == "CAPTURE") LocalTaskState.CAPTURING
                                                else LocalTaskState.PARTIAL
                                            else old.state,
                                        updated = System.currentTimeMillis(),
                                    )
                                }
                        }
                        .orEmpty()
                coroutineContext.ensureActive()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    repo.database.withTransaction {
                        restored.forEach { repo.dao.putDocument(it) }
                        pages.forEach { repo.dao.putPage(it) }
                        histories.forEach { repo.dao.addHistory(it) }
                        outputRows.forEach { repo.dao.putOutput(it) }
                        taskRows.forEach { repo.dao.putTask(it) }
                        sideChanged = true
                        metadata.optJSONObject("assemblyDraft")?.let {
                            cn.paperforge.app.pdf.AssemblyDraft.write(
                                repo.context,
                                cn.paperforge.app.pdf.AssemblyPlan.decode(it).remap(ids),
                            )
                        }
                        metadata.optJSONObject("profiles")?.let {
                            SettingsProfiles(repo.context).importJson(it.toString())
                        }
                        metadata.optJSONObject("library")?.let {
                            LibraryPreferences(repo.context).restore(it, ids)
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
                    published = true
                }
                restored
                    .filter { it.kind == "pdf" }
                    .forEach {
                        runCatching { LibraryIndexer.enqueue(repo.context, it.id) }
                            .onFailure { error ->
                                android.util.Log.w(
                                    "BackupStore",
                                    "Restored PDF indexing will retry",
                                    error,
                                )
                            }
                    }
                (repo.context.applicationContext as? cn.paperforge.app.PaperForgeApp)
                    ?.backgroundScope
                    ?.launch { cn.paperforge.app.imaging.CaptureTasks.recover(repo) }
                return restored.size
            }
        } catch (e: Exception) {
            if (!published)
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

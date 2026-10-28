package cn.paperforge.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.safeFileName
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class Repository(val context: Context, val database: PaperDatabase) {
    val dao = database.dao()
    val documents = dao.observeDocuments()
    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    val exports = File(context.filesDir, "exports").apply { mkdirs() }

    suspend fun newScan(): DocumentRow =
        DocumentRow(
                UUID.randomUUID().toString(),
                "扫描 ${java.text.SimpleDateFormat("MM-dd HH.mm",java.util.Locale.CHINA).format(java.util.Date())}",
                lastOpened = System.currentTimeMillis(),
            )
            .also { dao.putDocument(it) }

    fun folder(id: String) = File(root, id).apply { mkdirs() }

    fun captureFile(id: String) = File(folder(id), "${UUID.randomUUID()}.jpg")

    suspend fun addCapture(
        id: String,
        file: File,
        previewQuad: cn.paperforge.app.model.Quad? = null,
        reference: cn.paperforge.app.model.CaptureReference? = null,
    ): PageRow =
        withContext(Dispatchers.IO) {
            val detection = ScanProcessor.detectFile(file, previewQuad, reference)
            val page =
                PageRow(
                    UUID.randomUUID().toString(),
                    id,
                    0,
                    file.absolutePath,
                    filter = cn.paperforge.app.UserSettings.string("filter", "AUTO"),
                    brightness = cn.paperforge.app.UserSettings.number("brightness", 0f),
                    contrast = cn.paperforge.app.UserSettings.number("contrast", 1f),
                    enhanceStrength = cn.paperforge.app.UserSettings.number("enhanceStrength", 1f),
                    outputAspect = cn.paperforge.app.UserSettings.number("scanAspect", 0f),
                    corners = (detection.quad ?: cn.paperforge.app.model.Quad.Full).encode(),
                    detectionConfidence = detection.confidence,
                    detectionMethod = detection.method,
                    processingMetadata =
                        cn.paperforge.app.imaging.CaptureMetadata.encode(reference, detection),
                )
            database.withTransaction {
                val position =
                    maxOf(
                        dao.pages(id).maxOfOrNull { it.position } ?: -1,
                        dao.reservedCapturePosition(id) ?: -1,
                    ) + 1
                val inserted = page.copy(position = position)
                dao.putPage(inserted)
                dao.touch(id, System.currentTimeMillis())
                inserted
            }
        }

    suspend fun importImages(id: String, uris: List<Uri>) =
        withContext(Dispatchers.IO) {
            uris.forEach { uri ->
                val file = captureFile(id)
                try {
                    copyUri(uri, file)
                    addCapture(id, file)
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            }
        }

    suspend fun importPdf(uri: Uri, password: String = ""): DocumentRow =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val file = File(folder(id), "source.pdf")
            try {
                copyUri(uri, file)
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(file, password).use { pdf ->
                    require(pdf.numberOfPages > 0) { "PDF 没有页面" }
                    if (pdf.isEncrypted) {
                        val unlocked = File(folder(id), "unlocked.pdf")
                        pdf.isAllSecurityToBeRemoved = true
                        pdf.save(unlocked)
                        file.renameTo(File(folder(id), "encrypted-original.pdf"))
                        check(unlocked.renameTo(file))
                    }
                }
                DocumentRow(
                        id,
                        displayName(uri).removeSuffix(".pdf"),
                        kind = "pdf",
                        pdfPath = file.path,
                        lastOpened = System.currentTimeMillis(),
                    )
                    .also {
                        dao.putDocument(it)
                        LibraryIndexer.enqueue(context, it.id)
                    }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    private fun copyUri(uri: Uri, target: File) {
        val temp = File(target.path + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: error("文件提供程序无法打开此文件")
            check(temp.renameTo(target)) { "无法保存本地副本" }
        } finally {
            temp.delete()
        }
    }

    fun displayName(uri: Uri): String =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "导入文档"

    suspend fun savePage(page: PageRow) =
        database.withTransaction {
            val current =
                dao.pages(page.documentId).firstOrNull { it.id == page.id } ?: error("页面不存在")
            val next =
                page.copy(
                    revision = current.revision + 1,
                    ocrText = null,
                    ocrLayout = null,
                    processingMetadata = PageResources.withoutOcrRecord(page),
                )
            dao.clearRedo(page.documentId)
            dao.addHistory(
                EditHistory(
                    documentId = page.documentId,
                    pageId = page.id,
                    beforeJson = DocumentCodec.page(current).toString(),
                    afterJson = DocumentCodec.page(next).toString(),
                )
            )
            dao.updatePage(next)
            dao.touch(page.documentId, System.currentTimeMillis())
        }

    suspend fun cacheOcr(
        page: PageRow,
        result: cn.paperforge.app.model.OcrLayout,
    ): Pair<PageRow, cn.paperforge.app.model.OcrLayout> =
        database.withTransaction {
            var current =
                dao.pages(page.documentId).firstOrNull { it.id == page.id } ?: error("页面不存在")
            require(current.revision == page.revision) { "页面已修改，请重新识别" }
            var adjusted = result
            if (result.orientationCorrection != 0 || result.deskewDegrees != 0f) {
                savePage(
                    current.copy(
                        rotation = (current.rotation + result.orientationCorrection) % 360,
                        deskewDegrees = current.deskewDegrees + result.deskewDegrees,
                    )
                )
                current = dao.pages(page.documentId).first { it.id == page.id }
                adjusted = result.rotated(result.orientationCorrection)
                if (result.deskewDegrees != 0f)
                    adjusted =
                        cn.paperforge.app.model
                            .DeskewGeometry(adjusted.width, adjusted.height, result.deskewDegrees)
                            .map(adjusted, true)
            }
            adjusted =
                adjusted.copy(
                    sourceRevision = current.revision,
                    deskewDegrees = 0f,
                    frame = cn.paperforge.app.model.OcrFrame.bind(current),
                )
            val file = OcrRecords.write(this@Repository, current, OcrRecords.create(page, adjusted))
            adjusted = adjusted.copy(rawRecognition = null)
            val updated =
                current.copy(
                    ocrText = adjusted.text,
                    ocrLayout = adjusted.encode(),
                    processingMetadata = PageResources.withOcrRecord(current, file),
                )
            try {
                if (current.ocrText != null) {
                    dao.clearRedo(page.documentId)
                    dao.addHistory(
                        EditHistory(
                            documentId = page.documentId,
                            pageId = page.id,
                            beforeJson = DocumentCodec.page(current).toString(),
                            afterJson = DocumentCodec.page(updated).toString(),
                        )
                    )
                }
                dao.updatePage(updated)
                dao.touch(page.documentId, System.currentTimeMillis())
                updated to adjusted
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    suspend fun correctOcr(
        page: PageRow,
        text: String,
        layoutOverride: cn.paperforge.app.model.OcrLayout? = null,
    ): PageRow =
        database.withTransaction {
            val current =
                dao.pages(page.documentId).firstOrNull { it.id == page.id } ?: error("页面不存在")
            require(current.revision == page.revision) { "页面已修改，请重新打开识别结果" }
            val lines = text.lines()
            val layout = current.ocrLayout?.let { cn.paperforge.app.model.OcrLayout.decode(it) }
            val corrected =
                layoutOverride?.also { require(it.text == text) { "校正文字与行位置不一致" } }
                    ?: layout
                        ?.takeIf { it.lines.size == lines.size }
                        ?.copy(
                            lines =
                                layout.lines.mapIndexed { i, line ->
                                    if (line.text == lines[i]) line
                                    else
                                        line.copy(
                                            text = lines[i],
                                            elements = emptyList(),
                                            corrected = true,
                                        )
                                }
                        )
            val file =
                OcrRecords.write(this@Repository, current, OcrRecords.corrected(current, text))
            val updated =
                current.copy(
                    ocrText = text,
                    ocrLayout = corrected?.copy(tables = emptyList())?.encode(),
                    processingMetadata = PageResources.withOcrRecord(current, file),
                )
            try {
                dao.clearRedo(current.documentId)
                dao.addHistory(
                    EditHistory(
                        documentId = current.documentId,
                        pageId = current.id,
                        beforeJson = DocumentCodec.page(current).toString(),
                        afterJson = DocumentCodec.page(updated).toString(),
                    )
                )
                dao.updatePage(updated)
                dao.touch(current.documentId, System.currentTimeMillis())
                updated
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    suspend fun undo(id: String, redo: Boolean = false) =
        database.withTransaction {
            val history =
                (if (redo) dao.redoEntry(id) else dao.undoEntry(id)) ?: return@withTransaction
            val encoded = if (redo) history.afterJson else history.beforeJson
            val restored = HistoryState.pages(encoded)
            val linked =
                if (HistoryState.isTransfer(encoded)) dao.linkedHistory(history.pageId)
                else listOf(history)
            val affected =
                if (HistoryState.isTransfer(encoded))
                    (HistoryState.pages(history.beforeJson) + HistoryState.pages(history.afterJson))
                        .map { it.documentId }
                        .toSet()
                else setOf(id)
            if (HistoryState.isTransfer(encoded)) {
                require(linked.map { it.documentId }.toSet() == affected) {
                    "关联文档已删除，无法撤销这次移动；页面仍可复制或移入新文档"
                }
                for (entry in linked) {
                    val doc = dao.document(entry.documentId)
                    require(doc != null && doc.trashedAt == null) { "关联文档已移入回收站，请恢复后撤销移动" }
                    val top =
                        if (redo) dao.redoEntry(entry.documentId)
                        else dao.undoEntry(entry.documentId)
                    require(entry.applied == history.applied && top?.id == entry.id) {
                        "“${doc.title}”有后续编辑，请先撤销该文档的后续编辑"
                    }
                }
            }
            val currentByDoc =
                affected.associateWith { docId -> dao.pages(docId).associateBy { it.id } }
            fun restore(p: PageRow, current: Map<String, PageRow>): PageRow {
                val revision = (current[p.id]?.revision ?: p.revision) + 1
                val layout =
                    p.ocrLayout
                        ?.let { cn.paperforge.app.model.OcrLayout.decode(it) }
                        ?.takeIf { it.matches(p) }
                return p.copy(
                    revision = revision,
                    ocrText =
                        if (
                            layout != null ||
                                PageResources.entries(p).any { it.role == "ocr-record" }
                        )
                            p.ocrText
                        else null,
                    ocrLayout = layout?.withRevision(revision)?.encode(),
                )
            }
            if (HistoryState.order(encoded) != null || HistoryState.isSnapshot(encoded)) {
                val before = HistoryState.pages(history.beforeJson).associateBy { it.id }
                val after = HistoryState.pages(history.afterJson).associateBy { it.id }
                val touched =
                    (before.keys + after.keys)
                        .filter { !HistoryState.sameContent(before[it], after[it]) }
                        .toSet()
                val current = currentByDoc.getValue(id)
                touched.forEach { dao.deletePage(it) }
                val working = current.filterKeys { it !in touched }.toMutableMap()
                restored
                    .filter { it.id in touched }
                    .forEach { page -> working[page.id] = restore(page, current) }
                val desired =
                    HistoryState.order(encoded) ?: restored.sortedBy { it.position }.map { it.id }
                val all =
                    (desired.mapNotNull { working[it] } +
                        working.values.filter { it.id !in desired }.sortedBy { it.position })
                all.forEachIndexed { index, page -> dao.putPage(page.copy(position = index)) }
            } else if (HistoryState.isDelta(encoded)) {
                val touched =
                    (HistoryState.pages(history.beforeJson) + HistoryState.pages(history.afterJson))
                        .map { it.id }
                        .toSet()
                touched.forEach { dao.deletePage(it) }
                for (docId in affected) {
                    val current = currentByDoc.getValue(docId)
                    val ordered =
                        current.values
                            .filter { it.id !in touched }
                            .sortedBy { it.position }
                            .toMutableList()
                    restored
                        .filter { it.documentId == docId }
                        .sortedBy { it.position }
                        .forEach { page ->
                            ordered.add(
                                page.position.coerceIn(0, ordered.size),
                                restore(page, current),
                            )
                        }
                    ordered.forEachIndexed { index, page ->
                        dao.putPage(page.copy(position = index))
                    }
                }
            } else {
                val current = currentByDoc.getValue(id)
                val p = restored.single()
                require(current.containsKey(p.id)) { "该页已移除，请先撤销对应的删页或移动操作" }
                dao.putPage(restore(p, current).copy(position = current.getValue(p.id).position))
            }
            linked.forEach { dao.updateHistory(it.copy(applied = redo)) }
            affected.forEach { dao.touch(it, System.currentTimeMillis()) }
        }

    private suspend fun replacePages(
        id: String,
        before: List<PageRow>,
        after: List<PageRow>,
        pageId: String,
    ) {
        dao.clearRedo(id)
        val ordered = after.mapIndexed { index, p -> p.copy(position = index) }
        val previous = before.associateBy { it.id }
        val next = ordered.associateBy { it.id }
        val touched =
            (previous.keys + next.keys)
                .filter { !HistoryState.sameContent(previous[it], next[it]) }
                .toSet()
        dao.addHistory(
            EditHistory(
                documentId = id,
                pageId = pageId,
                beforeJson =
                    HistoryState.ordered(before.filter { it.id in touched }, before.map { it.id }),
                afterJson =
                    HistoryState.ordered(ordered.filter { it.id in touched }, ordered.map { it.id }),
            )
        )
        val retained = ordered.map { it.id }.toSet()
        before.filter { it.id !in retained }.forEach { dao.deletePage(it.id) }
        ordered.forEach { dao.putPage(it) }
        dao.touch(id, System.currentTimeMillis())
    }

    suspend fun reorder(id: String, order: List<PageRow>) =
        database.withTransaction {
            val before = dao.pages(id)
            require(before.map { it.id }.toSet() == order.map { it.id }.toSet()) { "页面列表已变化，请重新排序" }
            if (before.map { it.id } != order.map { it.id })
                replacePages(
                    id,
                    before,
                    order.map { previous -> before.first { it.id == previous.id } },
                    order.firstOrNull()?.id ?: "order",
                )
        }

    suspend fun deletePage(page: PageRow) =
        database.withTransaction {
            val before = dao.pages(page.documentId)
            require(before.any { it.id == page.id }) { "页面不存在" }
            // Keep original files while any undo/redo state references them.
            replacePages(page.documentId, before, before.filter { it.id != page.id }, page.id)
        }

    suspend fun duplicate(page: PageRow) =
        database.withTransaction {
            val all = dao.pages(page.documentId)
            val index = all.indexOfFirst { it.id == page.id }
            require(index >= 0)
            val next = all.toMutableList()
            next.add(index + 1, page.copy(id = UUID.randomUUID().toString()))
            replacePages(page.documentId, all, next, page.id)
        }

    suspend fun splitBook(
        page: PageRow,
        topFraction: Float = .5f,
        bottomFraction: Float = .5f,
        keep: String = "both",
    ) =
        database.withTransaction {
            val (leftQuad, rightQuad) =
                cn.paperforge.app.model.CropGeometry.split(
                    cn.paperforge.app.model.Quad.decode(page.corners),
                    topFraction,
                    bottomFraction,
                )
            require(leftQuad.valid() && rightQuad.valid()) { "书脊位置不合法" }
            require(keep in setOf("both", "left", "right"))
            val halves =
                listOf(
                        page.copy(
                            id = UUID.randomUUID().toString(),
                            corners = leftQuad.encode(),
                            outputAspect = 0f,
                            ocrText = null,
                            ocrLayout = null,
                        ),
                        page.copy(
                            id = UUID.randomUUID().toString(),
                            corners = rightQuad.encode(),
                            outputAspect = 0f,
                            ocrText = null,
                            ocrLayout = null,
                        ),
                    )
                    .filterIndexed { index, _ ->
                        keep == "both" ||
                            (keep == "left" && index == 0) ||
                            (keep == "right" && index == 1)
                    }
            val all = dao.pages(page.documentId)
            val index = all.indexOfFirst { it.id == page.id }
            require(index >= 0)
            val next =
                all.toMutableList().apply {
                    removeAt(index)
                    addAll(index, halves)
                }
            replacePages(page.documentId, all, next, page.id)
        }

    suspend fun originalResources(id: String): Set<String> =
        (listOfNotNull(dao.document(id)?.pdfPath) +
                DocumentResources.entries(this, id).map { it.path } +
                dao.tasksForDocument(id)
                    .flatMap { it.resourceEntries() }
                    .map { it.path }
                    .filter { File(it).isFile } +
                dao.pages(id).flatMap { PageResources.paths(it) } +
                dao.allHistory()
                    .filter { it.documentId == id }
                    .flatMap {
                        HistoryState.pages(it.beforeJson).flatMap { p -> PageResources.paths(p) } +
                            HistoryState.pages(it.afterJson).flatMap { p -> PageResources.paths(p) }
                    })
            .toSet()

    suspend fun trash(doc: DocumentRow) {
        dao.updateDocument(doc.copy(trashedAt = System.currentTimeMillis()))
    }

    suspend fun restore(doc: DocumentRow) {
        dao.updateDocument(doc.copy(trashedAt = null, modified = System.currentTimeMillis()))
    }

    suspend fun editDocuments(ids: Set<String>, edit: (DocumentRow) -> DocumentRow) {
        database.withTransaction {
            ids.forEach { id -> dao.document(id)?.let { dao.updateDocument(edit(it)) } }
        }
    }

    suspend fun deleteDocument(doc: DocumentRow) {
        val (work, historicalDirectories) =
            database.withTransaction {
                val historicalDirectories =
                    dao.allHistory()
                        .filter { it.documentId == doc.id }
                        .flatMap { entry ->
                            (HistoryState.pages(entry.beforeJson) +
                                    HistoryState.pages(entry.afterJson))
                                .flatMap { PageResources.paths(it) }
                        }
                        .mapNotNull { File(it).parentFile }
                        .distinct()
                val tasks = dao.tasksForDocument(doc.id)
                tasks.forEach { row ->
                    dao.putTask(
                        row.copy(
                            documentId = null,
                            resources =
                                DocumentResources.encode(
                                        row.resourceEntries().filter {
                                            it.id == "result" && it.path != doc.pdfPath
                                        }
                                    )
                                    .toString(),
                            state =
                                if (row.state in LocalTaskRow.active) LocalTaskState.CANCELLED
                                else row.state,
                            detail = "来源文档已永久删除",
                            updated = System.currentTimeMillis(),
                        )
                    )
                }
                dao.deleteDocument(doc.id)
                dao.clearHistory(doc.id)
                tasks.mapNotNull { it.workId } to historicalDirectories
            }
        work.forEach {
            androidx.work.WorkManager.getInstance(context).cancelWorkById(UUID.fromString(it))
        }
        // Only our UUID-named private directory is removed; external documents are never deleted.
        val directory = File(root, doc.id)
        require(directory.canonicalFile.parentFile == root.canonicalFile)
        val remainingDocuments = dao.allDocuments()
        val retained =
            (dao.allPages().flatMap { PageResources.paths(it) } +
                    dao.allHistory().flatMap { entry ->
                        (HistoryState.pages(entry.beforeJson) + HistoryState.pages(entry.afterJson))
                            .flatMap { PageResources.paths(it) }
                    } +
                    dao.allTasks().flatMap { it.resourceEntries() }.map { it.path } +
                    remainingDocuments.flatMap {
                        listOfNotNull(it.pdfPath) +
                            DocumentResources.entries(this, it.id).map { r -> r.path }
                    } +
                    dao.allOutputs().map { it.path })
                .map { File(it).canonicalPath }
                .toSet()
        val liveIds = remainingDocuments.map { it.id }.toSet()
        (historicalDirectories + directory)
            .map { it.canonicalFile }
            .distinct()
            .filter { it.parentFile == root.canonicalFile && it.name !in liveIds }
            .forEach { obsolete ->
                obsolete.walkBottomUp().forEach { file ->
                    if (file.isDirectory || file.canonicalPath !in retained) file.delete()
                }
            }
        doc.pdfPath?.let { path ->
            val file = File(path)
            if (
                dao.pdfReferences(path) == 0 &&
                    dao.allTasks().none { task ->
                        task.resourceEntries().any {
                            File(it.path).canonicalFile == file.canonicalFile
                        }
                    } &&
                    (file.canonicalFile.parentFile == exports.canonicalFile ||
                        file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            ) {
                dao.removeOutput(path)
                file.delete()
            }
        }
    }

    suspend fun rename(doc: DocumentRow, title: String) {
        val name = safeFileName(title)
        OutputNames.remember(this, doc.id, name)
        dao.rename(doc.id, name, System.currentTimeMillis())
    }

    suspend fun saveOutput(file: File, uri: Uri) =
        withContext(Dispatchers.IO) {
            require(file.isFile && file.length() > 0) { "本地结果不存在或为空" }
            val expected = java.security.MessageDigest.getInstance("SHA-256")
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                java.security.DigestInputStream(file.inputStream(), expected).use {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = it.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            } ?: error("无法写入所选位置")
            val actual = java.security.MessageDigest.getInstance("SHA-256")
            val count =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.security.DigestInputStream(input, actual).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = stream.read(buffer)
                            if (size < 0) break
                            bytes += size
                        }
                        bytes
                    }
                } ?: error("无法核对目标文件，本地结果保留，可重试保存")
            check(count == file.length() && expected.digest().contentEquals(actual.digest())) {
                "目标文件内容不完整，本地结果保留，可重试保存"
            }
            dao.outputDestination(file.path, uri.toString())
        }

    suspend fun registerPdf(file: File, title: String, listed: Boolean = true): DocumentRow =
        database
            .withTransaction {
                dao.documentByPath(file.path)
                    ?: DocumentRow(
                            UUID.randomUUID().toString(),
                            title,
                            kind = "pdf",
                            pdfPath = file.path,
                            listed = listed,
                        )
                        .also { dao.putDocument(it) }
            }
            .also { LibraryIndexer.enqueue(context, it.id) }

    suspend fun recordOutput(file: File, source: DocumentRow? = null) {
        val existing = dao.output(file.path)
        val parentOutput =
            if (source?.listed == false) source.pdfPath?.let { dao.output(it) } else null
        val sourceId = parentOutput?.sourceDocumentId ?: source?.id ?: existing?.sourceDocumentId
        val sourceTitle =
            sourceId?.let { dao.document(it)?.title }
                ?: parentOutput?.sourceTitle
                ?: source?.title
                ?: existing?.sourceTitle
                ?: "独立导出"
        dao.putOutput(
            OutputRow(
                file.path,
                sourceId,
                sourceTitle,
                file.extension.lowercase(),
                existing?.created ?: System.currentTimeMillis(),
                existing?.externalUri,
                dao.documentByPath(file.path)?.id ?: existing?.outputDocumentId,
            )
        )
    }
}

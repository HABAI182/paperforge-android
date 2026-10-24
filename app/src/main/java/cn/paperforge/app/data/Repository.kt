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
import kotlinx.coroutines.Dispatchers
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
    ): PageRow =
        withContext(Dispatchers.IO) {
            val detection = ScanProcessor.detectFile(file, previewQuad)
            val page =
                PageRow(
                    UUID.randomUUID().toString(),
                    id,
                    dao.pages(id).size,
                    file.absolutePath,
                    filter = cn.paperforge.app.UserSettings.string("filter", "AUTO"),
                    brightness = cn.paperforge.app.UserSettings.number("brightness", 0f),
                    contrast = cn.paperforge.app.UserSettings.number("contrast", 1f),
                    enhanceStrength = cn.paperforge.app.UserSettings.number("enhanceStrength", 1f),
                    corners = (detection.quad ?: cn.paperforge.app.model.Quad.Full).encode(),
                    detectionConfidence = detection.confidence,
                    detectionMethod = detection.method,
                )
            database.withTransaction {
                dao.putPage(page)
                dao.touch(id, System.currentTimeMillis())
            }
            page
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
            val next = page.copy(revision = current.revision + 1, ocrText = null, ocrLayout = null)
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
            adjusted = adjusted.copy(sourceRevision = current.revision, deskewDegrees = 0f)
            val updated = current.copy(ocrText = adjusted.text, ocrLayout = adjusted.encode())
            dao.updatePage(updated)
            dao.touch(page.documentId, System.currentTimeMillis())
            updated to adjusted
        }

    suspend fun undo(id: String, redo: Boolean = false) =
        database.withTransaction {
            val history =
                (if (redo) dao.redoEntry(id) else dao.undoEntry(id)) ?: return@withTransaction
            val encoded = if (redo) history.afterJson else history.beforeJson
            val restored = HistoryState.pages(encoded)
            val current = dao.pages(id).associateBy { it.id }
            if (HistoryState.isSnapshot(encoded)) {
                current.keys.forEach { dao.deletePage(it) }
                restored.forEach { p ->
                    dao.putPage(
                        p.copy(
                            revision = (current[p.id]?.revision ?: p.revision) + 1,
                            ocrText = null,
                            ocrLayout = null,
                        )
                    )
                }
            } else {
                val p = restored.single()
                dao.putPage(
                    p.copy(
                        revision = (current[p.id]?.revision ?: p.revision) + 1,
                        ocrText = null,
                        ocrLayout = null,
                    )
                )
            }
            dao.updateHistory(history.copy(applied = redo))
            dao.touch(id, System.currentTimeMillis())
        }

    private suspend fun replacePages(
        id: String,
        before: List<PageRow>,
        after: List<PageRow>,
        pageId: String,
    ) {
        dao.clearRedo(id)
        val ordered = after.mapIndexed { index, p -> p.copy(position = index) }
        dao.addHistory(
            EditHistory(
                documentId = id,
                pageId = pageId,
                beforeJson = HistoryState.snapshot(before),
                afterJson = HistoryState.snapshot(ordered),
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
                replacePages(id, before, order, order.firstOrNull()?.id ?: "order")
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
        dao.deleteDocument(doc.id)
        dao.clearHistory(doc.id)
        // Only our UUID-named private directory is removed; external documents are never deleted.
        val directory = File(root, doc.id)
        require(directory.canonicalFile.parentFile == root.canonicalFile)
        directory.deleteRecursively()
        doc.pdfPath?.let { path ->
            val file = File(path)
            if (
                dao.pdfReferences(path) == 0 &&
                    (file.canonicalFile.parentFile == exports.canonicalFile ||
                        file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            ) {
                dao.removeOutput(path)
                file.delete()
            }
        }
    }

    suspend fun rename(doc: DocumentRow, title: String) {
        dao.rename(doc.id, safeFileName(title), System.currentTimeMillis())
    }

    suspend fun saveOutput(file: File, uri: Uri) =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
                output.flush()
            } ?: error("无法写入所选位置")
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

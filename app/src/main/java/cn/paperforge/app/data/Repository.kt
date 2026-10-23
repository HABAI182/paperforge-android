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
import org.json.JSONObject

class Repository(val context: Context, val database: PaperDatabase) {
    val dao = database.dao()
    val documents = dao.observeDocuments()
    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    val exports = File(context.filesDir, "exports").apply { mkdirs() }

    suspend fun newScan(): DocumentRow =
        DocumentRow(
                UUID.randomUUID().toString(),
                "扫描 ${java.text.SimpleDateFormat("MM-dd HH.mm",java.util.Locale.CHINA).format(java.util.Date())}",
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
            val detection = ScanProcessor.detectFile(file)
            val page =
                PageRow(
                    UUID.randomUUID().toString(),
                    id,
                    dao.pages(id).size,
                    file.absolutePath,
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
                    )
                    .also { dao.putDocument(it) }
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
            val p =
                DocumentCodec.page(JSONObject(if (redo) history.afterJson else history.beforeJson))
            val current = dao.pages(id).find { it.id == p.id } ?: return@withTransaction
            dao.updatePage(
                p.copy(revision = current.revision + 1, ocrText = null, ocrLayout = null)
            )
            dao.updateHistory(history.copy(applied = redo))
            dao.touch(id, System.currentTimeMillis())
        }

    suspend fun reorder(id: String, order: List<PageRow>) =
        database.withTransaction {
            order.forEachIndexed { index, p -> dao.updatePage(p.copy(position = index)) }
            dao.touch(id, System.currentTimeMillis())
        }

    suspend fun deletePage(page: PageRow) {
        dao.deletePage(page.id)
        if (dao.originalReferences(page.original) == 0) File(page.original).delete()
        dao.clearHistory(page.documentId)
        reorder(page.documentId, dao.pages(page.documentId))
    }

    suspend fun duplicate(page: PageRow) {
        val all = dao.pages(page.documentId).toMutableList()
        val copy = page.copy(id = UUID.randomUUID().toString())
        dao.putPage(copy)
        all.add(all.indexOfFirst { it.id == page.id } + 1, copy)
        reorder(page.documentId, all)
    }

    suspend fun splitBook(page: PageRow) {
        val p = cn.paperforge.app.model.Quad.decode(page.corners).points
        fun mid(a: cn.paperforge.app.model.PointN, b: cn.paperforge.app.model.PointN) =
            cn.paperforge.app.model.PointN((a.x + b.x) / 2, (a.y + b.y) / 2)
        val top = mid(p[0], p[1])
        val bottom = mid(p[3], p[2])
        val left =
            page.copy(
                id = UUID.randomUUID().toString(),
                corners = cn.paperforge.app.model.Quad(listOf(p[0], top, bottom, p[3])).encode(),
                ocrText = null,
                ocrLayout = null,
            )
        val right =
            page.copy(
                id = UUID.randomUUID().toString(),
                corners = cn.paperforge.app.model.Quad(listOf(top, p[1], p[2], bottom)).encode(),
                ocrText = null,
                ocrLayout = null,
            )
        val all = dao.pages(page.documentId).toMutableList()
        val index = all.indexOfFirst { it.id == page.id }
        database.withTransaction {
            dao.deletePage(page.id)
            dao.putPage(left)
            dao.putPage(right)
            all.removeAt(index)
            all.addAll(index, listOf(left, right))
            reorder(page.documentId, all)
            dao.clearHistory(page.documentId)
        }
    }

    suspend fun trash(doc: DocumentRow) {
        dao.updateDocument(doc.copy(trashedAt = System.currentTimeMillis()))
    }

    suspend fun restore(doc: DocumentRow) {
        dao.updateDocument(doc.copy(trashedAt = null, modified = System.currentTimeMillis()))
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
                file.canonicalFile.parentFile == exports.canonicalFile &&
                    dao.pdfReferences(path) == 0
            )
                file.delete()
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
        }

    suspend fun registerPdf(file: File, title: String): DocumentRow =
        database.withTransaction {
            dao.documentByPath(file.path)
                ?: DocumentRow(
                        UUID.randomUUID().toString(),
                        title,
                        kind = "pdf",
                        pdfPath = file.path,
                    )
                    .also { dao.putDocument(it) }
        }
}

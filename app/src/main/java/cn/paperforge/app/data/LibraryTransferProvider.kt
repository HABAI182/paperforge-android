package cn.paperforge.app.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.safeFileName
import cn.paperforge.app.pdf.PdfEngine
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Same-UID bridge for app imports and paired LAN browsing. Never exports filesystem paths. */
class LibraryTransferProvider : ContentProvider() {
    private val locks = ConcurrentHashMap<String, Any>()
    private val installation by lazy {
        requireNotNull(context)
            .packageManager
            .getPackageInfo(requireNotNull(context).packageName, 0)
            .lastUpdateTime
    }
    private val repo
        get() = (requireNotNull(context).applicationContext as PaperForgeApp).repository

    override fun onCreate() = true

    private suspend fun pageCount(doc: DocumentRow, detailed: Boolean): Int =
        if (doc.kind == "scan") repo.dao.pages(doc.id).size
        else
            repo.dao.pdfIndex(doc.id)?.pageCount?.takeIf { it > 0 }
                ?: if (!detailed) 0
                else
                    ParcelFileDescriptor.open(
                            File(requireNotNull(doc.pdfPath)),
                            ParcelFileDescriptor.MODE_READ_ONLY,
                        )
                        .use { fd -> PdfRenderer(fd).use { it.pageCount } }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        runBlocking(Dispatchers.IO) {
            if (uri.pathSegments == listOf("documents") || uri.lastPathSegment == "info") {
                val docs =
                    if (uri.lastPathSegment == "info") listOf(document(uri))
                    else
                        repo.dao
                            .allDocuments()
                            .filter {
                                it.trashedAt == null &&
                                    it.listed &&
                                    (it.kind == "scan" || it.pdfPath != null)
                            }
                            .sortedByDescending { it.modified }
                MatrixCursor(
                        arrayOf(
                            "id",
                            "title",
                            "kind",
                            "folder",
                            "tags",
                            "modified",
                            "pages",
                            "bytes",
                        )
                    )
                    .apply {
                        for (doc in docs) {
                            val count = pageCount(doc, uri.lastPathSegment == "info")
                            if (doc.kind != "scan" || count > 0) {
                                val bytes =
                                    if (doc.kind == "scan")
                                        repo.dao.pages(doc.id).sumOf { File(it.original).length() }
                                    else File(doc.pdfPath!!).length()
                                addRow(
                                    arrayOf<Any>(
                                        doc.id,
                                        doc.title,
                                        doc.kind,
                                        doc.folder,
                                        doc.tags,
                                        doc.modified,
                                        count,
                                        bytes,
                                    )
                                )
                            }
                        }
                    }
            } else {
                val doc = document(uri)
                val image = uri.pathSegments.getOrNull(2) == "image"
                val suffix =
                    if (image) "-${(uri.lastPathSegment?.toIntOrNull() ?: 0)+1}.png" else ".pdf"
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                    addRow(
                        arrayOf<Any?>(
                            safeFileName(doc.title) + suffix,
                            if (!image && doc.kind != "scan") File(doc.pdfPath!!).length() else null,
                        )
                    )
                }
            }
        }

    private suspend fun document(uri: Uri): DocumentRow {
        val id = uri.pathSegments.getOrNull(1) ?: throw FileNotFoundException()
        return repo.dao.document(id)?.takeIf {
            it.trashedAt == null && it.listed && (it.kind == "scan" || it.pdfPath != null)
        } ?: throw FileNotFoundException("文档已移除")
    }

    private fun pdfBitmap(file: File, index: Int, edge: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(index in 0 until renderer.pageCount) { "页码超出范围" }
                renderer.openPage(index).use { page ->
                    val scale = edge.toFloat() / maxOf(page.width, page.height)
                    Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        .apply {
                            eraseColor(Color.WHITE)
                            page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                }
            }
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "文档库只读" }
        val file =
            runBlocking(Dispatchers.IO) {
                val doc = document(uri)
                synchronized(locks.getOrPut(doc.id) { Any() }) {
                    runBlocking(Dispatchers.IO) {
                        val root =
                            File(requireNotNull(context).cacheDir, "library-transfer").apply {
                                mkdirs()
                            }
                        val key =
                            doc.id.filter { it.isLetterOrDigit() || it == '-' } +
                                "-" +
                                doc.modified +
                                "-" +
                                installation
                        val resource = uri.pathSegments.getOrNull(2) ?: "pdf"
                        if (resource == "pdf") {
                            if (doc.kind == "scan")
                                File(root, "$key.pdf").also {
                                    if (!it.exists())
                                        PdfEngine.scan(
                                            repo.dao.pages(doc.id),
                                            it,
                                            context = context,
                                            searchable = false,
                                        )
                                }
                            else
                                File(doc.pdfPath!!).also {
                                    if (!it.isFile) throw FileNotFoundException()
                                }
                        } else {
                            require(resource in setOf("cover", "preview", "image")) { "未知文档资源" }
                            val index =
                                if (resource == "cover") 0
                                else uri.pathSegments.getOrNull(3)?.toIntOrNull() ?: 0
                            val edge =
                                when (resource) {
                                    "cover" -> 360
                                    "image" -> ScanConfig.edge("HIGH")
                                    else -> 1400
                                }
                            val image = resource == "image"
                            val output =
                                File(root, "$key-$resource-$index.${if(image) "png" else "jpg"}")
                            if (!output.exists()) {
                                val bitmap =
                                    if (doc.kind == "scan")
                                        ScanProcessor.render(
                                            repo.dao.pages(doc.id).getOrNull(index)
                                                ?: throw FileNotFoundException("页码超出范围"),
                                            edge,
                                        )
                                    else pdfBitmap(File(doc.pdfPath!!), index, edge)
                                val part = File(output.path + ".part")
                                try {
                                    part.outputStream().use {
                                        check(
                                            bitmap.compress(
                                                if (image) Bitmap.CompressFormat.PNG
                                                else Bitmap.CompressFormat.JPEG,
                                                92,
                                                it,
                                            )
                                        )
                                    }
                                    check(part.renameTo(output))
                                } finally {
                                    bitmap.recycle()
                                    part.delete()
                                }
                            }
                            output
                        }
                    }
                }
            }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) =
        when (uri.pathSegments.getOrNull(2)) {
            "cover",
            "preview" -> "image/jpeg"
            "image" -> "image/png"
            else -> "application/pdf"
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = throw UnsupportedOperationException()
}

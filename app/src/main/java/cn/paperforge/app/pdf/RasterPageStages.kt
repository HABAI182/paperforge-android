package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Persist complete raster pages; cached pages skip both rendering and image encoding. */
object RasterPageStages {
    suspend fun convert(
        input: File,
        output: File,
        stages: JobFileStages,
        operation: String,
        edge: Int,
        rounded: Boolean = false,
        encode: (PDDocument, Bitmap) -> PDImageXObject,
        draw: (Bitmap, Int) -> Unit = { _, _ -> },
        pageSignature: (Int) -> String = { "" },
        progress: suspend (Int) -> Unit = {},
    ) {
        val signature = "raster-page-r1|${PdfAssembly.sha256(input)}|$operation|$edge|$rounded"
        val parts = mutableListOf<File>()
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (index in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    parts +=
                        stages.file(
                            "raster-page-$index",
                            "$signature|$index|${pageSignature(index)}",
                            "pdf",
                        ) { file ->
                            renderer.openPage(index).use { source ->
                                val scale = edge.toFloat() / maxOf(source.width, source.height)
                                fun dimension(value: Int): Int =
                                    (if (rounded) (value * scale).roundToInt()
                                        else (value * scale).toInt())
                                        .coerceAtLeast(1)
                                val bitmap =
                                    Bitmap.createBitmap(
                                        dimension(source.width),
                                        dimension(source.height),
                                        Bitmap.Config.ARGB_8888,
                                    )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    source.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                    )
                                    draw(bitmap, index)
                                    PDDocument(MemoryUsageSetting.setupTempFileOnly()).use {
                                        document ->
                                        val page =
                                            PDPage(
                                                PDRectangle(
                                                    source.width.toFloat(),
                                                    source.height.toFloat(),
                                                )
                                            )
                                        document.addPage(page)
                                        PDPageContentStream(document, page).use { stream ->
                                            stream.drawImage(
                                                encode(document, bitmap),
                                                0f,
                                                0f,
                                                page.mediaBox.width,
                                                page.mediaBox.height,
                                            )
                                        }
                                        document.save(file)
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                    progress((index + 1) * 90 / renderer.pageCount)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (parts.size == 1) PdfTools.write(parts.single(), output) {}
        else {
            val sources =
                parts.mapIndexed { index, _ -> AssemblySource(index.toString(), "", 1, "") }
            PdfAssembly.writeFiles(
                null,
                AssemblyPlan(
                    sources,
                    sources.map { AssemblyPage(sourceId = it.documentId) },
                    title = output.nameWithoutExtension,
                    keepPageLabels = false,
                    keepAttachments = false,
                    keepOriginalOutlines = false,
                ),
                parts.mapIndexed { index, file -> index.toString() to file }.toMap(),
                output,
            )
        }
        progress(100)
    }
}

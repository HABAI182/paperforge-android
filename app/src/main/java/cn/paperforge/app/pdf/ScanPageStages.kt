package cn.paperforge.app.pdf

import android.content.Context
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.OcrFrame
import cn.paperforge.app.ocr.OcrEngine
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One source page per checkpoint, or one front/back pair for ID-card paper. */
object ScanPageStages {
    suspend fun scan(
        context: Context,
        pages: List<PageRow>,
        output: File,
        stages: JobFileStages,
        paper: String,
        quality: Int,
        searchable: Boolean,
        resolution: String,
        engine: OcrEngine?,
        lossless: Boolean,
        progress: suspend (Int) -> Unit,
    ) {
        require(pages.isNotEmpty()) { "请先添加页面" }
        val resolved = "DPI:${cn.paperforge.app.imaging.ScanConfig.dpi(resolution)}"
        val groups = pages.chunked(if (paper == "ID") 2 else 1)
        val parts = mutableListOf<File>()
        for ((index, group) in groups.withIndex()) {
            currentCoroutineContext().ensureActive()
            val signature = buildString {
                append(
                    "scan-page-r1|$paper|$quality|$searchable|$resolved|$lossless|${PdfTextLayer.VERSION}|${engine?.id}"
                )
                for (page in group) {
                    append(
                        "|${PdfAssembly.sha256(File(page.original))}|${OcrFrame.signature(page)}"
                    )
                    append(
                        "|${page.filter}|${page.brightness}|${page.contrast}|${page.enhanceStrength}"
                    )
                    if (searchable)
                        append(
                            "|${JobFileStages.digest(page.ocrLayout.orEmpty())}|${engine?.signature(true, cn.paperforge.app.ocr.OcrOptions.current())}"
                        )
                }
            }
            parts +=
                stages.file("scan-page-$index", signature, "pdf") { file ->
                    PdfEngine.scan(
                        group,
                        file,
                        paper,
                        quality,
                        context,
                        searchable,
                        resolved,
                        engine,
                        lossless,
                    ) {}
                }
            progress((index + 1) * 90 / groups.size)
        }
        currentCoroutineContext().ensureActive()
        if (parts.size == 1)
            PdfTools.write(parts.single(), output) {
                it.documentInformation.title = output.nameWithoutExtension
            }
        else {
            val sources =
                parts.mapIndexed { index, file ->
                    AssemblySource(index.toString(), file.nameWithoutExtension, 1, "")
                }
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

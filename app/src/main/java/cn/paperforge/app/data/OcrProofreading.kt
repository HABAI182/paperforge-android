package cn.paperforge.app.data

import androidx.room.withTransaction
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

data class OcrRegionResult(
    val region: OcrRegion,
    val result: OcrLayout,
    val width: Int,
    val height: Int,
    val revision: Int,
    val pageId: String,
)

suspend fun OcrEngine.recognizeRegion(
    page: PageRow,
    region: OcrRegion,
    chinese: Boolean,
    options: OcrOptions = OcrOptions.current(),
): OcrRegionResult {
    require(region.valid()) { "请框选需要识别的区域" }
    val source =
        ScanProcessor.render(
            page.copy(filter = "ORIGINAL", brightness = 0f, contrast = 1f),
            options.mode.edge,
        )
    try {
        val left = floor(region.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = floor(region.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = ceil(region.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = ceil(region.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        val actual =
            OcrRegion(
                left.toFloat() / source.width,
                top.toFloat() / source.height,
                right.toFloat() / source.width,
                bottom.toFloat() / source.height,
            )
        val cropped =
            android.graphics.Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return try {
            OcrRegionResult(
                actual,
                recognizeBitmap(cropped, chinese, options),
                source.width,
                source.height,
                page.revision,
                page.id,
            )
        } finally {
            if (cropped !== source) cropped.recycle()
        }
    } finally {
        source.recycle()
    }
}

suspend fun Repository.correctOcrLine(
    page: PageRow,
    lineIndex: Int,
    word: Int?,
    text: String,
): PageRow =
    database.withTransaction {
        val current = dao.pages(page.documentId).firstOrNull { it.id == page.id } ?: error("页面不存在")
        require(current.revision == page.revision && current.ocrLayout == page.ocrLayout) {
            "文字已更新，请重新选择"
        }
        val layout = current.ocrLayout?.let(OcrLayout::decode) ?: error("请先识别本页文字")
        val line = layout.lines.getOrNull(lineIndex) ?: error("请选择文字行")
        require(!text.contains('\n')) { "逐行校正请保留当前行，换行可在完整文字中编辑" }
        val updated =
            if (word == null)
                line.copy(text = text, elements = emptyList(), corrected = true, confidence = null)
            else {
                val span = OcrRegionMerge.wordSpan(line, word) ?: error("该词无法单独替换，请选择整行校正")
                line.copy(
                    text = line.text.replaceRange(span.first, span.last + 1, text),
                    elements =
                        line.elements.mapIndexed { index, element ->
                            if (index == word) element.copy(text = text, confidence = null)
                            else element
                        },
                    corrected = true,
                    confidence = null,
                )
            }
        val corrected =
            layout.copy(
                lines =
                    layout.lines.mapIndexed { index, old ->
                        if (index == lineIndex) updated else old
                    }
            )
        correctOcr(current, corrected.text, corrected)
    }

suspend fun Repository.applyOcrRegion(page: PageRow, run: OcrRegionResult): PageRow =
    database.withTransaction {
        val current = dao.pages(page.documentId).firstOrNull { it.id == page.id } ?: error("页面不存在")
        require(current.id == run.pageId && current.revision == run.revision) { "页面已修改，请重新框选" }
        require(current.ocrLayout != null || current.ocrText.isNullOrEmpty()) {
            "当前是自由校正文字，可复制选区结果；合并需保留原有行位置"
        }
        val base =
            current.ocrLayout?.let(OcrLayout::decode)
                ?: OcrLayout(
                    run.width,
                    run.height,
                    emptyList(),
                    run.result.engineId,
                    run.result.engineVersion,
                    sourceRevision = current.revision,
                )
        val merged = OcrRegionMerge.apply(base, run.region, run.result)
        require(merged.canMerge) { "请扩大到完整文字行再应用，或复制选区结果以保留当前校正" }
        val layout =
            merged.layout.copy(
                sourceRevision = current.revision,
                recognitionMode = run.result.recognitionMode,
                attempts = run.result.attempts,
                rawRecognition = null,
            )
        val record =
            OcrRecords.read(current)
                ?: OcrRecords.create(current, base).apply {
                    if (base.lines.isEmpty()) remove("automatic")
                }
        val regions =
            record.optJSONArray("regions") ?: JSONArray().also { record.put("regions", it) }
        regions.put(
            JSONObject()
                .put(
                    "bounds",
                    JSONArray(
                        listOf(run.region.left, run.region.top, run.region.right, run.region.bottom)
                    ),
                )
                .put("created", System.currentTimeMillis())
                .put("result", JSONObject(run.result.encode()))
        )
        record
            .getJSONArray("edits")
            .put(
                JSONObject()
                    .put("created", System.currentTimeMillis())
                    .put("kind", "region")
                    .put("text", layout.text)
            )
        val file = OcrRecords.write(this@applyOcrRegion, current, record)
        val updated =
            current.copy(
                ocrText = layout.text,
                ocrLayout = layout.encode(),
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

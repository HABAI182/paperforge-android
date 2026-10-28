package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.*
import kotlinx.coroutines.ensureActive
import org.json.*

data class PdfMark(
    val page: Int,
    val kind: String,
    val points: List<PointN>,
    val color: Int = Color.RED,
    val width: Float = .004f,
    val text: String = "",
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    fun json() =
        JSONObject()
            .put("page", page)
            .put("kind", kind)
            .put("color", color)
            .put("width", width)
            .put("text", text)
            .put("id", id)
            .put("points", JSONArray(points.map { JSONArray(listOf(it.x, it.y)) }))

    companion object {
        fun decode(array: JSONArray) =
            List(array.length()) { n ->
                array.getJSONObject(n).let { o ->
                    val p = o.getJSONArray("points")
                    PdfMark(
                        o.getInt("page"),
                        o.getString("kind"),
                        List(p.length()) { i ->
                            PointN(
                                p.getJSONArray(i).getDouble(0).toFloat(),
                                p.getJSONArray(i).getDouble(1).toFloat(),
                            )
                        },
                        o.optInt("color", Color.RED),
                        o.optDouble("width", .004).toFloat(),
                        o.optString("text"),
                        o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    )
                }
            }
    }
}

object PdfMarks {
    /**
     * Ordinary marks support native annotations or a flattened copy. Redaction rebuilds pixels; no
     * original content streams, attachments, hidden text or metadata enter that output.
     */
    suspend fun save(
        context: Context,
        input: File,
        output: File,
        marks: List<PdfMark>,
        editable: Boolean = false,
        stages: JobFileStages? = null,
        progress: suspend (Int) -> Unit = {},
    ) {
        if (marks.any { it.kind == "redact" }) {
            val preview =
                File(
                    output.parentFile,
                    "marks-redaction-preview-${java.util.UUID.randomUUID()}.pdf",
                )
            try {
                if (stages == null) {
                    PdfNativeMarks.preview(input, preview)
                    raster(preview, output, marks, progress)
                } else {
                    val appearance =
                        stages.file(
                            "raster-appearance",
                            "native-mark-preview-r1|${PdfAssembly.sha256(input)}",
                            "pdf",
                        ) {
                            PdfNativeMarks.preview(input, it)
                        }
                    RasterPageStages.convert(
                        appearance,
                        output,
                        stages,
                        "redaction-r1",
                        2400,
                        rounded = true,
                        encode = { doc, bitmap -> JPEGFactory.createFromImage(doc, bitmap, .98f) },
                        draw = { bitmap, index ->
                            val canvas = Canvas(bitmap)
                            marks
                                .filter { it.page == index }
                                .forEach {
                                    draw(
                                        canvas,
                                        bitmap.width.toFloat(),
                                        bitmap.height.toFloat(),
                                        it,
                                    )
                                }
                        },
                        pageSignature = { index ->
                            marks
                                .filter { it.page == index }
                                .joinToString("|") { it.json().apply { remove("id") }.toString() }
                        },
                        progress = progress,
                    )
                }
            } finally {
                preview.delete()
            }
            return
        }
        PdfTools.write(input, output) { doc -> PdfNativeMarks.apply(context, doc, marks, editable) }
        progress(100)
    }

    private suspend fun raster(
        input: File,
        output: File,
        marks: List<PdfMark>,
        progress: suspend (Int) -> Unit,
    ) {
        val part = File(output.path + ".part")
        try {
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                        for (index in 0 until renderer.pageCount) {
                            coroutineContext.ensureActive()
                            renderer.openPage(index).use { source ->
                                val scale = 2400f / max(source.width, source.height)
                                val bitmap =
                                    Bitmap.createBitmap(
                                        (source.width * scale).roundToInt(),
                                        (source.height * scale).roundToInt(),
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
                                    val canvas = Canvas(bitmap)
                                    marks
                                        .filter { it.page == index }
                                        .forEach { mark ->
                                            draw(
                                                canvas,
                                                bitmap.width.toFloat(),
                                                bitmap.height.toFloat(),
                                                mark,
                                            )
                                        }
                                    val page =
                                        PDPage(
                                            PDRectangle(
                                                source.width.toFloat(),
                                                source.height.toFloat(),
                                            )
                                        )
                                    doc.addPage(page)
                                    PDPageContentStream(doc, page).use {
                                        it.drawImage(
                                            JPEGFactory.createFromImage(doc, bitmap, .98f),
                                            0f,
                                            0f,
                                            page.mediaBox.width,
                                            page.mediaBox.height,
                                        )
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            progress((index + 1) * 100 / renderer.pageCount)
                        }
                        doc.save(part)
                    }
                }
            }
            PDDocument.load(part).use { require(it.numberOfPages > 0) }
            check(part.renameTo(output))
        } finally {
            part.delete()
        }
    }

    private fun draw(canvas: Canvas, w: Float, h: Float, mark: PdfMark) {
        if (mark.points.isEmpty()) return
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mark.color
                strokeWidth = mark.width * min(w, h)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        val a = mark.points.first()
        val b = mark.points.last()
        when (mark.kind) {
            "rect",
            "highlight",
            "redact" -> {
                paint.style = if (mark.kind == "rect") Paint.Style.STROKE else Paint.Style.FILL
                if (mark.kind == "highlight") paint.alpha = 78
                if (mark.kind == "redact") {
                    paint.color = Color.BLACK
                    paint.alpha = 255
                }
                canvas.drawRect(
                    min(a.x, b.x) * w,
                    min(a.y, b.y) * h,
                    max(a.x, b.x) * w,
                    max(a.y, b.y) * h,
                    paint,
                )
            }
            "underline",
            "strikeout" -> {
                val y = if (mark.kind == "underline") max(a.y, b.y) else (a.y + b.y) / 2
                canvas.drawLine(min(a.x, b.x) * w, y * h, max(a.x, b.x) * w, y * h, paint)
            }
            "note",
            "label",
            "text" -> {
                paint.textSize = max(10f, mark.width * w * 5)
                canvas.drawText(
                    if (mark.kind == "note") "N" else mark.text,
                    a.x * w,
                    a.y * h,
                    paint,
                )
            }
            else -> {
                paint.style = Paint.Style.STROKE
                val path = Path()
                mark.points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h)
                }
                canvas.drawPath(path, paint)
            }
        }
    }
}

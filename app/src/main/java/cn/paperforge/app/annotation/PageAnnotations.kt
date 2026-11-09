package cn.paperforge.app.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.PageAnnotation
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Draws the annotation layer and bakes it into a brand-new page image.
 *
 * The same [drawInto] is used by the Compose preview and by [bake], so the preview is not a
 * separate renderer that can drift from the saved pixels. Annotations never touch
 * `page.original`; [bake] returns a new bitmap that the caller writes to a new file and registers
 * as the next page revision, which keeps the camera original and the existing undo history intact.
 */
object PageAnnotations {
    const val VERSION = "page-annotation-1"

    /** Long side of the baked page; matches a standard 220 DPI scan export closely. */
    const val BAKE_EDGE = 3000

    /** Pen / text colours offered by the toolbar (ARGB). */
    val colors = listOf(0xFF000000.toInt(), 0xFFD32F2F.toInt(), 0xFF1565C0.toInt())

    fun bake(page: PageRow, annotations: List<PageAnnotation>, edge: Int = BAKE_EDGE): Bitmap {
        require(annotations.isNotEmpty()) { "没有可写入的标注" }
        val rendered = ScanProcessor.render(page, edge)
        val base =
            if (rendered.isMutable && rendered.config == Bitmap.Config.ARGB_8888) rendered
            else
                rendered.copy(Bitmap.Config.ARGB_8888, true).also {
                    if (it !== rendered) rendered.recycle()
                }
        Canvas(base).let {
            drawInto(it, base, base.width.toFloat(), base.height.toFloat(), annotations)
        }
        return base
    }

    /**
     * Draws [annotations] into [canvas] whose logical page size is [width]x[height].
     * [source] supplies pixels for mosaic blocks; pass the same page bitmap that was drawn under
     * the canvas (preview: the loaded preview bitmap, bake: the target bitmap itself).
     */
    fun drawInto(
        canvas: Canvas,
        source: Bitmap,
        width: Float,
        height: Float,
        annotations: List<PageAnnotation>,
    ) {
        if (width <= 1f || height <= 1f) return
        val minSide = min(width, height)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        annotations.forEach { annotation ->
            when (annotation.kind) {
                "pen",
                "highlight" -> {
                    if (annotation.points.isEmpty()) return@forEach
                    val path = Path()
                    annotation.points.forEachIndexed { index, point ->
                        val x = point.x * width
                        val y = point.y * height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    if (annotation.points.size == 1) {
                        val point = annotation.points.first()
                        path.addCircle(point.x * width, point.y * height, .1f, Path.Direction.CW)
                    }
                    stroke.color =
                        if (annotation.kind == "highlight")
                            Color.argb(96, 255, 214, 10)
                        else annotation.color
                    stroke.strokeWidth =
                        max(1f, annotation.width * minSide) *
                            if (annotation.kind == "highlight") 3.4f else 1f
                    canvas.drawPath(path, stroke)
                }
                "mosaic" -> drawMosaic(canvas, source, width, height, annotation, minSide)
                "redact" -> {
                    val box = box(annotation, width, height)
                    if (box == null) return@forEach
                    val minimum = max(2f, annotation.width * minSide)
                    if (box.height() < minimum) {
                        val center = (box.top + box.bottom) / 2f
                        box.top = center - minimum / 2f
                        box.bottom = center + minimum / 2f
                    }
                    canvas.drawRect(box, Paint().apply { color = Color.BLACK })
                }
                "text" -> {
                    val anchor = annotation.points.firstOrNull() ?: return@forEach
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = annotation.color
                            textSize = max(10f, annotation.width * minSide)
                            typeface = Typeface.DEFAULT
                        }
                    val lineHeight = paint.textSize * 1.25f
                    annotation.text.lines().forEachIndexed { index, line ->
                        canvas.drawText(
                            line,
                            anchor.x * width,
                            anchor.y * height + paint.textSize + index * lineHeight,
                            paint,
                        )
                    }
                }
            }
        }
    }

    private fun drawMosaic(
        canvas: Canvas,
        source: Bitmap,
        width: Float,
        height: Float,
        annotation: PageAnnotation,
        minSide: Float,
    ) {
        val box = box(annotation, width, height) ?: return
        if (box.width() < 1f || box.height() < 1f) return
        val block = max(4f, annotation.width * minSide)
        val columns = ceil(box.width() / block).toInt().coerceAtLeast(1)
        val rows = ceil(box.height() / block).toInt().coerceAtLeast(1)
        val left = (box.left / width * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (box.top / height * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right =
            (box.right / width * source.width).roundToInt().coerceIn(left + 1, source.width)
        val bottom =
            (box.bottom / height * source.height).roundToInt().coerceIn(top + 1, source.height)
        val regionWidth = right - left
        val regionHeight = bottom - top
        val paint = Paint()
        // Average every covered pixel per block. Bitmap.createScaledBitmap(filter = true) only
        // bilinearly samples a 2x2 neighbourhood, which on fine print keeps a few near-black or
        // near-white blocks and leaves white gaps that read as "not redacted". A true box mean
        // removes the underlying glyphs at every block size.
        for (row in 0 until rows) {
            val sourceTop = top + row * regionHeight / rows
            val sourceBottom = top + (row + 1) * regionHeight / rows
            if (sourceBottom <= sourceTop) continue
            val targetTop = box.top + row * box.height() / rows
            val targetBottom = box.top + (row + 1) * box.height() / rows
            for (column in 0 until columns) {
                val sourceLeft = left + column * regionWidth / columns
                val sourceRight = left + (column + 1) * regionWidth / columns
                if (sourceRight <= sourceLeft) continue
                val blockWidth = sourceRight - sourceLeft
                val blockHeight = sourceBottom - sourceTop
                val pixels = IntArray(blockWidth * blockHeight)
                source.getPixels(pixels, 0, blockWidth, sourceLeft, sourceTop, blockWidth, blockHeight)
                var red = 0L
                var green = 0L
                var blue = 0L
                for (pixel in pixels) {
                    red += (pixel shr 16) and 0xFF
                    green += (pixel shr 8) and 0xFF
                    blue += pixel and 0xFF
                }
                val count = pixels.size.coerceAtLeast(1)
                paint.color =
                    Color.rgb(
                        (red / count).toInt(),
                        (green / count).toInt(),
                        (blue / count).toInt(),
                    )
                val targetLeft = box.left + column * box.width() / columns
                val targetRight = box.left + (column + 1) * box.width() / columns
                canvas.drawRect(targetLeft, targetTop, targetRight, targetBottom, paint)
            }
        }
    }

    private fun box(
        annotation: PageAnnotation,
        width: Float,
        height: Float,
    ): RectF? {
        val bounds = annotation.bounds()
        if (bounds[2] - bounds[0] < .001f && bounds[3] - bounds[1] < .001f) return null
        return RectF(
            bounds[0] * width,
            bounds[1] * height,
            bounds[2] * width,
            bounds[3] * height,
        )
    }

    /**
     * Metadata for the new page revision: the baked appearance is now an ORIGINAL image, so the
     * old `flatDocument` reuse hint must go (otherwise the renderer would re-route through the old
     * per-page recipe). A compact provenance record keeps the operation auditable.
     */
    fun metadata(page: PageRow, annotations: List<PageAnnotation>): String {
        val json = JSONObject(page.processingMetadata)
        json.remove("flatDocument")
        val kinds = JSONArray().apply { annotations.map { it.kind }.distinct().forEach { put(it) } }
        json.put(
            "pageAnnotations",
            JSONObject()
                .put("version", VERSION)
                .put("count", annotations.size)
                .put("kinds", kinds)
                .put("created", System.currentTimeMillis()),
        )
        return json.toString()
    }

    /**
     * Writes a new page image inside the document folder. The file name is random, so a retry can
     * never overwrite a previous version that the history still references.
     */
    fun write(bitmap: Bitmap, documentFolder: File): File {
        val file = File(documentFolder, "${UUID.randomUUID()}-annotation.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file
    }
}

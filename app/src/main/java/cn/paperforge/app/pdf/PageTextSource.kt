package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.ocr.OcrEngine
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PageTextSource {
    suspend fun load(
        context: Context,
        file: File,
        page: Int,
        engine: OcrEngine,
        recognize: Boolean = false,
    ): List<ReaderTextBlock> =
        withContext(Dispatchers.IO) {
            val key =
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        "${file.canonicalPath}:${file.length()}:${file.lastModified()}:$page"
                            .toByteArray()
                    )
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            val target = File(context.cacheDir, "reader-text/$key.json")
            if (!recognize && target.exists()) return@withContext decode(target.readText())
            val native =
                ReaderDocument.text(file, page).filter { block ->
                    block.text.count {
                        it == '\uFFFD' || Character.getType(it) == Character.PRIVATE_USE.toInt()
                    } <= block.text.length / 5
                }
            if (!recognize) return@withContext native
            val bitmap =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(page).use { source ->
                            val scale = 2400f / maxOf(source.width, source.height)
                            Bitmap.createBitmap(
                                    (source.width * scale).toInt().coerceAtLeast(1),
                                    (source.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888,
                                )
                                .also {
                                    it.eraseColor(Color.WHITE)
                                    source.render(
                                        it,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                    )
                                }
                        }
                    }
                }
            val ocr =
                try {
                    engine.snapshot().recognizeBitmap(bitmap)
                } finally {
                    bitmap.recycle()
                }
            val extra =
                ocr.lines
                    .mapNotNull { line ->
                        if (line.corners.isEmpty()) null
                        else
                            ReaderTextBlock(
                                line.text,
                                line.corners.minOf { it.x },
                                line.corners.minOf { it.y },
                                line.corners.maxOf { it.x },
                                line.corners.maxOf { it.y },
                                "ocr",
                            )
                    }
                    .filter { block -> native.none { known -> overlaps(known, block) } }
            val result = ReaderDocument.order(native + extra, 0)
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".part")
            temp.writeText(encode(result))
            check(temp.renameTo(target))
            result
        }

    private fun overlaps(a: ReaderTextBlock, b: ReaderTextBlock): Boolean {
        val intersection =
            (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
                (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val area =
            minOf((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top))
                .coerceAtLeast(.000001f)
        return intersection / area > .65f
    }

    private fun encode(blocks: List<ReaderTextBlock>) =
        JSONArray()
            .apply {
                blocks.forEach {
                    put(
                        JSONObject()
                            .put("text", it.text)
                            .put("l", it.left)
                            .put("t", it.top)
                            .put("r", it.right)
                            .put("b", it.bottom)
                            .put("source", it.source)
                    )
                }
            }
            .toString()

    private fun decode(value: String): List<ReaderTextBlock> =
        JSONArray(value).let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let {
                    ReaderTextBlock(
                        it.getString("text"),
                        it.getDouble("l").toFloat(),
                        it.getDouble("t").toFloat(),
                        it.getDouble("r").toFloat(),
                        it.getDouble("b").toFloat(),
                        it.optString("source", "ocr"),
                    )
                }
            }
        }
}

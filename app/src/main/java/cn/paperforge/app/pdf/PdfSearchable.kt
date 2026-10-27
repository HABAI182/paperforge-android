package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

object PdfSearchable {
    suspend fun convert(
        context: Context,
        input: File,
        output: File,
        ocrEngine: cn.paperforge.app.ocr.OcrEngine? = null,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        val temporary = File(output.path + ".part")
        val decisions = JSONArray()
        try {
            val router =
                ocrEngine
                    ?: (context.applicationContext as cn.paperforge.app.PaperForgeApp).ocrEngine
            val engine =
                if (ocrEngine != null) router.snapshot()
                else cn.paperforge.app.ocr.OcrRequest.current(router.id).bind(router)
            val mapping = PdfTextLayer.Mapping().useFonts(engine.fontFiles)
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font = PdfTextLayer.font(context, doc)
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        for (i in 0 until renderer.pageCount) {
                            coroutineContext.ensureActive()
                            val decision = NativeTextCoverage.assess(doc, i)
                            val native = decision.regions
                            var added = 0
                            if (decision.needsOcr)
                                renderer.openPage(i).use { view ->
                                    val scale = 2400f / maxOf(view.width, view.height)
                                    val bitmap =
                                        Bitmap.createBitmap(
                                            (view.width * scale).toInt().coerceAtLeast(1),
                                            (view.height * scale).toInt().coerceAtLeast(1),
                                            Bitmap.Config.ARGB_8888,
                                        )
                                    try {
                                        bitmap.eraseColor(Color.WHITE)
                                        view.render(
                                            bitmap,
                                            null,
                                            null,
                                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                        )
                                        val layout =
                                            NativeTextCoverage.removeCovered(
                                                engine.recognizeBitmap(
                                                    bitmap,
                                                    cn.paperforge.app.UserSettings.string(
                                                        "ocrLanguage",
                                                        "CHINESE",
                                                    ) != "LATIN",
                                                ),
                                                native,
                                            )
                                        val page = doc.getPage(i)
                                        added = layout.lines.size
                                        val box = page.cropBox
                                        // PdfRenderer returns displayed coordinates. Undo page
                                        // rotation before mapping.
                                        val normalized =
                                            PdfCoordinates.unrotate(layout, page.rotation)
                                        PdfTextLayer.append(
                                            doc,
                                            page,
                                            font,
                                            normalized,
                                            box.lowerLeftX,
                                            box.lowerLeftY,
                                            box.width,
                                            box.height,
                                            mapping,
                                        )
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            decisions.put(
                                JSONObject()
                                    .put("page", i + 1)
                                    .put("reason", decision.reason)
                                    .put("ocr", decision.needsOcr)
                                    .put("nativeRegions", native.size)
                                    .put("badGlyphs", decision.badGlyphs)
                                    .put("imageRegions", decision.images.size)
                                    .put("addedLines", added)
                            )
                            progress((i + 1) * 100 / renderer.pageCount)
                        }
                    }
                }
                doc.save(temporary)
            }
            mapping.finish(temporary)
            PDDocument.load(temporary).use { require(it.numberOfPages > 0) }
            check(temporary.renameTo(output))
            return JSONObject().put("policy", NativeTextCoverage.VERSION).put("pages", decisions)
        } finally {
            temporary.delete()
        }
    }
}

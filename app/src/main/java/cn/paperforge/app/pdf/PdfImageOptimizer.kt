package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.*
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resource-only rewriting. Native page/form operators, fonts, links and widgets are never
 * rasterized.
 */
object PdfImageOptimizer {
    private data class Binding(val resources: PDResources, val name: COSName)

    private class Asset(val image: PDImageXObject) {
        val bindings = mutableListOf<Binding>()
        var widthPt = 0f
        var heightPt = 0f
        var protected = false
    }

    private fun <T> identitySet(): MutableSet<T> =
        Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    suspend fun compress(
        input: File,
        output: File,
        quality: Int,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(input.canonicalFile != output.canonicalFile) { "请保存为新文件" }
        val temporary = File(output.path + ".part")
        val rows = JSONArray()
        var inline = 0
        var copied = false
        val report =
            JSONObject()
                .put("inputBytes", input.length())
                .put("images", rows)
                .put("flattened", false)
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (doc.signatureDictionaries.isNotEmpty()) {
                    input.copyTo(temporary, true)
                    copied = true
                    report.put("reason", "signed PDF preserved byte-for-byte")
                } else {
                    val assets = IdentityHashMap<COSStream, Asset>()
                    val resourcesSeen = identitySet<COSDictionary>()
                    val protectedSeen = identitySet<COSDictionary>()
                    var patterns = false
                    fun walk(resources: PDResources?, protected: Boolean = false, depth: Int = 0) {
                        if (resources == null || depth > 32) return
                        if (protected && !protectedSeen.add(resources.cosObject)) return
                        if (!protected && !resourcesSeen.add(resources.cosObject)) return
                        if (resources.patternNames.iterator().hasNext()) patterns = true
                        resources.xObjectNames.toList().forEach { name ->
                            when (val objectValue = resources.getXObject(name)) {
                                is PDImageXObject -> {
                                    val asset =
                                        assets.getOrPut(objectValue.cosObject) {
                                            Asset(objectValue)
                                        }
                                    if (protected) asset.protected = true
                                    else asset.bindings.add(Binding(resources, name))
                                }
                                is PDFormXObject ->
                                    if (depth < 32)
                                        walk(objectValue.resources, protected, depth + 1)
                            }
                        }
                    }
                    // Annotation appearance images are never resampled based on a page-content
                    // placement.
                    val appearanceSeen = identitySet<COSBase>()
                    fun appearance(value: COSBase?, depth: Int = 0) {
                        val base = if (value is COSObject) value.`object` else value
                        if (base == null || depth > 32 || !appearanceSeen.add(base)) return
                        when (base) {
                            is COSStream -> {
                                base.getCOSDictionary(COSName.RESOURCES)?.let {
                                    walk(PDResources(it), true)
                                }
                            }
                            is COSDictionary -> base.values.forEach { appearance(it, depth + 1) }
                            is COSArray ->
                                (0 until base.size()).forEach {
                                    appearance(base.getObject(it), depth + 1)
                                }
                        }
                    }
                    for (page in doc.pages) {
                        walk(page.resources)
                        page.annotations.forEach {
                            appearance(it.cosObject.getDictionaryObject(COSName.AP))
                        }
                    }
                    for (page in doc.pages) {
                        coroutineContext.ensureActive()
                        val engine =
                            PlacementProbe(
                                page,
                                { image, w, h ->
                                    if (image is PDImageXObject)
                                        assets[image.cosObject]?.let { a ->
                                            a.widthPt = max(a.widthPt, w * page.userUnit)
                                            a.heightPt = max(a.heightPt, h * page.userUnit)
                                        }
                                    else inline++
                                },
                            )
                        engine.processPage(page)
                    }
                    val dpi = if (quality >= 85) 220 else if (quality >= 60) 160 else 110
                    val dedupe = mutableMapOf<String, PDImageXObject>()
                    var index = 0
                    assets.values.forEach { asset ->
                        coroutineContext.ensureActive()
                        val image = asset.image
                        val stream = image.cosObject
                        val row =
                            JSONObject()
                                .put("index", ++index)
                                .put("width", image.width)
                                .put("height", image.height)
                                .put("inputBytes", stream.length)
                        rows.put(row)
                        val colorspace = runCatching { image.colorSpace.name }.getOrNull()
                        val reason =
                            when {
                                asset.protected -> "annotation appearance"
                                patterns -> "pattern resources present: placement budget incomplete"
                                asset.bindings.isEmpty() ||
                                    asset.widthPt <= 0 ||
                                    asset.heightPt <= 0 -> "unused or unmeasured placement"
                                image.isStencil || image.bitsPerComponent != 8 ->
                                    "stencil or non-8-bit image"
                                stream.containsKey(COSName.SMASK) ||
                                    stream.containsKey(COSName.MASK) ||
                                    stream.containsKey(COSName.getPDFName("SMaskInData")) ->
                                    "mask/transparency preserved"
                                colorspace !in listOf("DeviceRGB", "DeviceGray") ->
                                    "profile/CMYK/indexed colorspace preserved"
                                listOf(
                                        "Decode",
                                        "StructParent",
                                        "Metadata",
                                        "OC",
                                        "Alternates",
                                        "OPI",
                                    )
                                    .any { stream.containsKey(COSName.getPDFName(it)) } ->
                                    "special image semantics preserved"
                                else -> null
                            }
                        if (reason != null) row.put("skipped", reason)
                        else {
                            val targetScale =
                                min(
                                    1.0,
                                    max(
                                        asset.widthPt * dpi / 72.0 / image.width,
                                        asset.heightPt * dpi / 72.0 / image.height,
                                    ),
                                )
                            val targetW = max(1, (image.width * targetScale).roundToInt())
                            val targetH = max(1, (image.height * targetScale).roundToInt())
                            row.put("targetDpi", dpi)
                                .put("displayWidthPt", asset.widthPt)
                                .put("displayHeightPt", asset.heightPt)
                            var sample = 1
                            while (
                                image.width / (sample * 2) >= targetW &&
                                    image.height / (sample * 2) >= targetH
                            ) sample *= 2
                            var replacement: PDImageXObject? = null
                            val directJpeg =
                                stream.getDictionaryObject(COSName.FILTER) == COSName.DCT_DECODE &&
                                    !stream.containsKey(COSName.DECODE_PARMS)
                            if (
                                (!directJpeg &&
                                    image.width.toLong() * image.height > 18_000_000L) ||
                                    image.width.toLong() * image.height / sample / sample >
                                        18_000_000L
                            )
                                row.put("skipped", "decode pixel budget")
                            else {
                                // PdfBox-Android 2.0.27.0 getImage(null, subsampling) cropped
                                // beyond the decoded
                                // bitmap on the 2000x2600 fixture. Decode ordinary DCT directly;
                                // bounded fallback
                                // uses sample=1 for other supported streams. Do not rely on the
                                // broken API path.
                                var bitmap =
                                    if (directJpeg) {
                                        val bounds =
                                            BitmapFactory.Options().apply {
                                                inJustDecodeBounds = true
                                            }
                                        stream.createRawInputStream().use {
                                            BitmapFactory.decodeStream(it, null, bounds)
                                        }
                                        require(
                                            bounds.outWidth == image.width &&
                                                bounds.outHeight == image.height
                                        ) {
                                            "PDF 图片尺寸与编码头不一致"
                                        }
                                        stream.createRawInputStream().use {
                                            BitmapFactory.decodeStream(
                                                it,
                                                null,
                                                BitmapFactory.Options().apply {
                                                    inSampleSize = sample
                                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                                },
                                            )
                                        } ?: error("PDF JPEG 解码失败")
                                    } else image.image
                                try {
                                    if (bitmap.width != targetW || bitmap.height != targetH) {
                                        val scaled =
                                            Bitmap.createScaledBitmap(
                                                bitmap,
                                                targetW,
                                                targetH,
                                                true,
                                            )
                                        if (scaled !== bitmap) {
                                            bitmap.recycle()
                                            bitmap = scaled
                                        }
                                    }
                                    coroutineContext.ensureActive()
                                    val encoded =
                                        PdfImageEncoder.jpeg(
                                            doc,
                                            bitmap,
                                            colorspace == "DeviceGray",
                                            quality,
                                            dpi,
                                        )
                                    if (encoded.cosObject.length < stream.length) {
                                        replacement = encoded
                                        row.put("outputBytes", encoded.cosObject.length)
                                            .put("outputWidth", targetW)
                                            .put("outputHeight", targetH)
                                    } else row.put("skipped", "recompression was not smaller")
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            var selected = replacement ?: image
                            val digest = MessageDigest.getInstance("SHA-256")
                            selected.cosObject.createRawInputStream().use { source ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                }
                            }
                            val key =
                                "${selected.width}:${selected.height}:${selected.bitsPerComponent}:${selected.colorSpace.name}:${selected.interpolate}:" +
                                    digest.digest().joinToString("") { "%02x".format(it) }
                            val duplicate = dedupe[key]
                            if (duplicate != null) {
                                selected = duplicate
                                row.put("deduplicated", true)
                            } else dedupe[key] = selected
                            if (selected !== image)
                                asset.bindings.forEach { it.resources.put(it.name, selected) }
                        }
                        progress(index * 100 / max(1, assets.size))
                    }
                    report.put("inlineImagesSkipped", inline).put("targetDpi", dpi)
                    doc.save(temporary)
                }
            }
            coroutineContext.ensureActive()
            if (!copied && temporary.length() >= input.length()) {
                input.copyTo(temporary, true)
                report.put("reason", "rewrite did not reduce total bytes; original preserved")
                copied = true
            }
            PDDocument.load(temporary, MemoryUsageSetting.setupTempFileOnly()).use {
                require(it.numberOfPages > 0)
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            check(temporary.renameTo(output)) { "保存压缩文件失败" }
            report.put("outputBytes", output.length()).put("originalCopied", copied)
            // Optional diagnostics cannot turn a valid PDF into a failed export.
            runCatching { File(output.path + ".report.json").writeText(report.toString(2)) }
            progress(100)
            return report
        } finally {
            temporary.delete()
        }
    }

    private class PlacementProbe(page: PDPage, val image: (PDImage, Float, Float) -> Unit) :
        PDFGraphicsStreamEngine(page) {
        private val stack = identitySet<COSStream>()
        private var current = PointF()

        override fun drawImage(pdImage: PDImage) {
            val matrix = graphicsState.currentTransformationMatrix
            image(pdImage, abs(matrix.scalingFactorX), abs(matrix.scalingFactorY))
        }

        override fun showForm(form: PDFormXObject) {
            check(stack.size < 32 && stack.add(form.cosObject)) { "PDF 表单资源递归异常，已保留源文件" }
            try {
                super.showForm(form)
            } finally {
                stack.remove(form.cosObject)
            }
        }

        override fun appendRectangle(a: PointF, b: PointF, c: PointF, d: PointF) {
            current = a
        }

        override fun clip(rule: Path.FillType) {}

        override fun moveTo(x: Float, y: Float) {
            current = PointF(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            current = PointF(x, y)
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            current = PointF(x3, y3)
        }

        override fun getCurrentPoint() = current

        override fun closePath() {}

        override fun endPath() {}

        override fun strokePath() {}

        override fun fillPath(rule: Path.FillType) {}

        override fun fillAndStrokePath(rule: Path.FillType) {}

        override fun shadingFill(name: COSName) {}
    }
}

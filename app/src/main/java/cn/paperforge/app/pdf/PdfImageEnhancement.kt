package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import cn.paperforge.app.data.RuntimeBudget
import cn.paperforge.app.imaging.EnhancePipeline
import cn.paperforge.app.model.parsePageRange
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import java.io.File
import java.io.FileOutputStream
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Copy-on-write scan image replacement: never flatten page operators or mutate shared resources.
 */
object PdfImageEnhancement {
    val presets =
        linkedMapOf(
            "AUTO" to "自然",
            "TEXT" to "清晰文字",
            "WORKBOOK" to "练习册",
            "BW" to "柔和黑白",
            "CLEAN" to "强力去背景",
            "GRAY" to "灰度",
        )

    data class Inspection(
        val pages: Int,
        val annotations: Int,
        val hasForms: Boolean,
        val blocked: String?,
    )

    private fun inspect(doc: PDDocument) =
        Inspection(
            doc.numberOfPages,
            doc.pages.sumOf { it.annotations.size },
            doc.documentCatalog.acroForm != null,
            when {
                doc.signatureDictionaries.isNotEmpty() -> "此 PDF 含数字签名，改写图像会使签名失效。请使用未签名的原稿。"
                !doc.currentAccessPermission.canModify() -> "此 PDF 不允许修改，请先使用有修改权限的副本。"
                else -> null
            },
        )

    fun inspect(input: File): Inspection =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use(::inspect)

    private fun eligible(image: PDImageXObject): Boolean {
        val stream = image.cosObject
        if (
            !(image.width >= 128 &&
                image.height >= 128 &&
                !image.isStencil &&
                !stream.containsKey(COSName.MASK) &&
                !stream.containsKey(COSName.getPDFName("SMaskInData")))
        )
            return false
        // LosslessFactory emits an all-white soft mask even for opaque ARGB scan bitmaps.
        // Accept only a verified opaque mask; genuinely transparent artwork stays untouched.
        val mask = image.softMask ?: return true
        if (mask.width.toLong() * mask.height > RuntimeBudget.memoryPlan().pixels(4)) return false
        val bitmap = mask.image
        try {
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                if (row.any { Color.red(it) != 255 }) return false
            }
            return true
        } finally {
            bitmap.recycle()
        }
    }

    /** Decode within the same memory budget as OCR, with headroom for the flat-field buffers. */
    private fun enhanced(image: PDImageXObject, mode: String): Bitmap {
        val size = RuntimeBudget.memoryPlan().size(image.width, image.height, 40)
        var sample = 1
        while (image.width / sample > size.width || image.height / sample > size.height) sample *= 2
        val source = image.getImage(null, sample)
        val rgba = Mat()
        val rgb = Mat()
        val result = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            EnhancePipeline.apply(rgb, result, mode)
            return Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(result, it)
            }
        } finally {
            source.recycle()
            rgba.release()
            rgb.release()
            result.release()
        }
    }

    suspend fun enhance(
        input: File,
        output: File,
        preset: String,
        range: String = "",
        stages: JobFileStages? = null,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(preset in presets) { "请选择图像增强预设" }
        require(input.canonicalFile != output.canonicalFile) { "请另存副本" }
        val part = File(output.path + ".part")
        val report =
            JSONObject().put("preset", preset).put("flattened", false).put("textPreserved", true)
        val rows = JSONArray()
        report.put("pages", rows)
        val sourceHash = if (stages == null) "" else stages.sourceHash(input)
        var changed = 0
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                inspect(doc).blocked?.let { error(it) }
                val selected = parsePageRange(range, doc.numberOfPages).toSet()
                val replacements = IdentityHashMap<COSStream, PDImageXObject>()
                for ((index, page) in doc.pages.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (index !in selected) continue
                    val targets = IdentityHashMap<COSStream, PDImageXObject>()
                    var inline = 0
                    PdfImageOptimizer.PlacementProbe(page) { image, w, h ->
                            if (image is PDImageXObject) {
                                // Ignore logos and decorative thumbnails. Preserve inline/masked
                                // images.
                                if (
                                    w * h >= page.cropBox.width * page.cropBox.height * .35f &&
                                        eligible(image)
                                )
                                    targets[image.cosObject] = image
                            } else inline++
                        }
                        .processPage(page)
                    val row =
                        JSONObject()
                            .put("page", index + 1)
                            .put("scanImages", targets.size)
                            .put("inlineImagesPreserved", inline)
                    rows.put(row)
                    val visiting = IdentityHashMap<COSStream, Boolean>()
                    suspend fun copyResources(
                        resources: PDResources?,
                        path: String,
                        depth: Int,
                    ): PDResources? {
                        if (resources == null) return null
                        check(depth <= 32) { "PDF 图像资源嵌套过深" }
                        val dictionary = COSDictionary(resources.cosObject)
                        val xobjects = resources.cosObject.getCOSDictionary(COSName.XOBJECT)
                        if (xobjects != null)
                            dictionary.setItem(COSName.XOBJECT, COSDictionary(xobjects))
                        val copy = PDResources(dictionary)
                        for (name in resources.xObjectNames.toList()) {
                            currentCoroutineContext().ensureActive()
                            when (val obj = resources.getXObject(name)) {
                                is PDImageXObject ->
                                    if (targets.containsKey(obj.cosObject)) {
                                        val replacement =
                                            replacements[obj.cosObject]
                                                ?: run {
                                                    val bitmap =
                                                        if (stages == null) enhanced(obj, preset)
                                                        else {
                                                            val cached =
                                                                stages.file(
                                                                    "enhance-$index-$path-${name.name}",
                                                                    "scan-enhance-r1|$sourceHash|$index|$path|${name.name}|$preset",
                                                                    "png",
                                                                ) { file ->
                                                                    val b = enhanced(obj, preset)
                                                                    try {
                                                                        file.outputStream().use {
                                                                            check(
                                                                                b.compress(
                                                                                    Bitmap
                                                                                        .CompressFormat
                                                                                        .PNG,
                                                                                    100,
                                                                                    it,
                                                                                )
                                                                            )
                                                                        }
                                                                    } finally {
                                                                        b.recycle()
                                                                    }
                                                                }
                                                            BitmapFactory.decodeFile(cached.path)
                                                                ?: error("无法读取增强图像阶段")
                                                        }
                                                    try {
                                                        val image =
                                                            if (preset == "BW")
                                                                PdfImageEncoder.scan(
                                                                    doc,
                                                                    bitmap,
                                                                    preset,
                                                                    100,
                                                                    300,
                                                                )
                                                            else
                                                                LosslessFactory.createFromImage(
                                                                    doc,
                                                                    bitmap,
                                                                )
                                                        replacements[obj.cosObject] = image
                                                        image
                                                    } finally {
                                                        bitmap.recycle()
                                                    }
                                                }
                                        copy.put(name, replacement)
                                        changed++
                                    }
                                is PDFormXObject -> {
                                    check(visiting.put(obj.cosObject, true) == null) {
                                        "PDF 图像资源循环引用"
                                    }
                                    try {
                                        // Independent form stream and resource dictionary for
                                        // selected pages.
                                        val stream =
                                            obj.cosObject.createInputStream().use {
                                                PDStream(doc, it).cosObject
                                            }
                                        for (entry in obj.cosObject.entrySet()) if (
                                            entry.key !in
                                                setOf(
                                                    COSName.LENGTH,
                                                    COSName.FILTER,
                                                    COSName.DECODE_PARMS,
                                                    COSName.RESOURCES,
                                                )
                                        )
                                            stream.setItem(entry.key, entry.value)
                                        val form = PDFormXObject(stream)
                                        form.resources =
                                            copyResources(
                                                obj.resources ?: resources,
                                                "$path/${name.name}",
                                                depth + 1,
                                            )
                                        copy.put(name, form)
                                    } finally {
                                        visiting.remove(obj.cosObject)
                                    }
                                }
                            }
                        }
                        return copy
                    }
                    if (targets.isNotEmpty())
                        page.resources = copyResources(page.resources, "page", 0)
                    progress(selected.count { it <= index } * 90 / selected.size)
                }
                require(changed > 0) { "所选页面没有可增强的扫描底图。文字、矢量、小图标及透明图像已保留。" }
                report.put("imageBindings", changed).put("uniqueImages", replacements.size)
                currentCoroutineContext().ensureActive()
                doc.save(part)
            }
            PDDocument.load(part, MemoryUsageSetting.setupTempFileOnly()).use {
                require(it.numberOfPages > 0)
            }
            FileOutputStream(part, true).use { it.fd.sync() }
            currentCoroutineContext().ensureActive()
            check(part.renameTo(output)) { "无法保存增强副本" }
            progress(100)
            return report
        } finally {
            part.delete()
        }
    }
}

package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

data class PdfPageFacts(
    val page: Int,
    val width: Float,
    val height: Float,
    val rotation: Int,
    val characters: Int,
    val images: Int,
    val annotations: Int,
) {
    fun json() =
        JSONObject()
            .put("page", page)
            .put("widthPoints", width)
            .put("heightPoints", height)
            .put("rotation", rotation)
            .put("characters", characters)
            .put("imageResources", images)
            .put("annotations", annotations)
}

data class PdfFontFact(
    val name: String,
    val type: String,
    val embedded: Boolean,
    val pages: List<Int>,
) {
    fun json() =
        JSONObject()
            .put("name", name)
            .put("type", type)
            .put("embedded", embedded)
            .put("pages", JSONArray(pages))
}

data class PdfDiagnosticsReport(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val version: String,
    val metadata: Map<String, String>,
    val permissions: Map<String, Boolean>,
    val encrypted: Boolean,
    val pages: List<PdfPageFacts>,
    val fonts: List<PdfFontFact>,
    val uniqueImages: Int,
    val fieldTypes: Map<String, Int>,
    val xfa: Boolean,
    val annotationTypes: Map<String, Int>,
    val attachments: List<String>,
    val signatures: List<JSONObject>,
    val warnings: List<String>,
) {
    val textPages
        get() = pages.count { it.characters > 0 }

    val imagePages
        get() = pages.count { it.images > 0 }

    val ocrCandidates
        get() = pages.filter { it.characters < 24 && it.images > 0 }

    fun json() =
        JSONObject()
            .put("format", 1)
            .put("fileName", fileName)
            .put("bytes", bytes)
            .put("sha256", sha256)
            .put("pdfVersion", version)
            .put("metadata", JSONObject(metadata))
            .put("permissions", JSONObject(permissions))
            .put("encrypted", encrypted)
            .put("pages", JSONArray(pages.map { it.json() }))
            .put("fonts", JSONArray(fonts.map { it.json() }))
            .put("uniqueImageResources", uniqueImages)
            .put("fieldTypes", JSONObject(fieldTypes))
            .put("xfa", xfa)
            .put("annotationTypes", JSONObject(annotationTypes))
            .put("attachments", JSONArray(attachments))
            .put("signatureRecords", JSONArray(signatures))
            .put("warnings", JSONArray(warnings))
            .put("signatureValidation", "not-performed")
            .put("archiveValidation", "not-performed")
}

/**
 * Optional read-only inspection: never validates archival/signature status or gates normal opening.
 */
object PdfDiagnostics {
    suspend fun inspect(input: File, progress: suspend (Int) -> Unit = {}): PdfDiagnosticsReport {
        val warnings = mutableSetOf<String>()
        val pages = mutableListOf<PdfPageFacts>()
        val fonts = IdentityHashMap<COSDictionary, Pair<PdfFontFact, MutableSet<Int>>>()
        val uniqueImages =
            java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        val annotationTypes = mutableMapOf<String, Int>()
        val digest = MessageDigest.getInstance("SHA-256")
        input.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper = PDFTextStripper()
            for (index in 0 until doc.numberOfPages) {
                currentCoroutineContext().ensureActive()
                val page = doc.getPage(index)
                val visited =
                    java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
                val images =
                    java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
                fun resources(value: PDResources?) {
                    if (value == null || !visited.add(value.cosObject)) return
                    for (name in value.fontNames) runCatching {
                            val font = value.getFont(name)
                            val descriptor = font.fontDescriptor
                            val embedded =
                                descriptor?.let {
                                    it.fontFile != null ||
                                        it.fontFile2 != null ||
                                        it.fontFile3 != null
                                } ?: false
                            val pair =
                                fonts.getOrPut(font.cosObject) {
                                    PdfFontFact(
                                        font.name ?: name.name,
                                        font.subType ?: font.type,
                                        embedded,
                                        emptyList(),
                                    ) to mutableSetOf()
                                }
                            pair.second += index + 1
                        }
                        .onFailure { warnings += "部分字体资源无法读取：${it.message}" }
                    for (name in value.xObjectNames) runCatching {
                            when (val obj = value.getXObject(name)) {
                                is PDImageXObject -> {
                                    images += obj.cosObject
                                    uniqueImages += obj.cosObject
                                }
                                is PDFormXObject -> resources(obj.resources)
                            }
                        }
                        .onFailure { warnings += "部分图像或外部对象无法读取：${it.message}" }
                }
                resources(page.resources)
                val text =
                    runCatching {
                            stripper.startPage = index + 1
                            stripper.endPage = index + 1
                            stripper.getText(doc)
                        }
                        .onFailure { warnings += "第 ${index+1} 页文字未能读取：${it.message}" }
                        .getOrDefault("")
                val annotations =
                    runCatching { page.annotations }
                        .onFailure { warnings += "第 ${index+1} 页部分批注未能读取" }
                        .getOrDefault(emptyList())
                annotations.forEach { annotation ->
                    runCatching {
                            annotation.appearance?.normalAppearance?.let { normal ->
                                if (normal.isStream) resources(normal.appearanceStream.resources)
                                else normal.subDictionary.values.forEach { resources(it.resources) }
                            }
                        }
                        .onFailure { warnings += "第 ${index+1} 页部分批注外观资源未能读取" }
                }
                annotations.forEach { annotation ->
                    annotationTypes.merge(annotation.subtype ?: "Unknown", 1, Int::plus)
                }
                val crop = page.cropBox
                pages +=
                    PdfPageFacts(
                        index + 1,
                        crop.width,
                        crop.height,
                        page.rotation,
                        text.count { !it.isWhitespace() },
                        images.size,
                        annotations.size,
                    )
                progress((index + 1) * 100 / doc.numberOfPages)
            }
            val attachments = mutableSetOf<String>()
            fun associated(dictionary: COSDictionary) {
                (dictionary.getDictionaryObject(COSName.getPDFName("AF")) as? COSArray)?.let {
                    values ->
                    for (i in 0 until values.size()) (values.getObject(i) as? COSDictionary)?.let {
                        spec ->
                        (spec.getString(COSName.UF) ?: spec.getString(COSName.F))?.let {
                            attachments += it
                        }
                    }
                }
            }
            associated(doc.documentCatalog.cosObject)
            doc.pages.forEach { page ->
                associated(page.cosObject)
                page.annotations
                    .filterIsInstance<
                        com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
                    >()
                    .forEach { annotation ->
                        (annotation.file
                                as?
                                com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification)
                            ?.let { attachments += it.fileUnicode ?: it.file ?: "未命名附件" }
                    }
            }
            doc.documentCatalog.names?.embeddedFiles?.let { root ->
                val queue =
                    ArrayDeque<
                        com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<
                            com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
                        >
                    >()
                queue += root
                val visited =
                    java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (!visited.add(node.cosObject)) continue
                    attachments += node.names.orEmpty().keys
                    node.kids?.forEach { queue += it }
                }
            }
            val form = doc.documentCatalog.acroForm
            val types =
                form
                    ?.fieldTree
                    ?.filterIsInstance<PDTerminalField>()
                    ?.groupingBy { it.fieldType ?: "Unknown" }
                    ?.eachCount()
                    .orEmpty()
            val signatures =
                doc.signatureDictionaries.map { signature ->
                    val ranges = signature.byteRange
                    val covered =
                        ranges?.takeIf { it.size == 4 }?.let { it[2].toLong() + it[3].toLong() }
                    JSONObject()
                        .put("recordedName", signature.name ?: "")
                        .put("reason", signature.reason ?: "")
                        .put("byteRange", JSONArray(ranges?.toList().orEmpty()))
                        .put("coveredEndByte", covered ?: JSONObject.NULL)
                        .put("reachesFileEnd", covered == input.length())
                        .put("certificateVerified", false)
                }
            val info = doc.documentInformation
            val metadata =
                linkedMapOf(
                        "标题" to info.title,
                        "作者" to info.author,
                        "主题" to info.subject,
                        "创建程序" to info.creator,
                        "生成引擎" to info.producer,
                    )
                    .mapValues { it.value.orEmpty() }
                    .filterValues { it.isNotBlank() }
            val permission = doc.currentAccessPermission
            return PdfDiagnosticsReport(
                input.name,
                input.length(),
                hash,
                doc.version.toString(),
                metadata,
                linkedMapOf(
                    "打印" to permission.canPrint(),
                    "复制文字" to permission.canExtractContent(),
                    "修改" to permission.canModify(),
                    "填写表单" to permission.canFillInForm(),
                ),
                doc.isEncrypted,
                pages,
                fonts.values
                    .map { (font, pages) -> font.copy(pages = pages.sorted()) }
                    .sortedBy { it.name },
                uniqueImages.size,
                types,
                form?.hasXFA() == true,
                annotationTypes,
                attachments.sorted(),
                signatures,
                warnings.toList(),
            )
        }
    }
}

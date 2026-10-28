package cn.paperforge.app.pdf

import android.content.Context
import cn.paperforge.app.data.DocumentResources
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.Repository
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PdfForms {
    private val draftLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun saveDraft(repo: Repository, doc: DocumentRow, request: JSONObject) =
        withContext(NonCancellable + Dispatchers.IO) {
            draftLocks
                .getOrPut(doc.id) { Mutex() }
                .withLock {
                    val target =
                        File(
                            repo.folder(doc.id),
                            "form-draft-${request.getString("sourceSha256")}.json",
                        )
                    TableJobs.atomic(target, request)
                    DocumentResources.register(repo, doc.id, "form-draft", target)
                }
        }

    data class Option(val value: String, val label: String)

    data class Field(
        val name: String,
        val label: String,
        val kind: String,
        val values: List<String>,
        val options: List<Option>,
        val readOnly: Boolean,
        val required: Boolean,
        val multiline: Boolean = false,
        val password: Boolean = false,
        val maxLength: Int = 0,
        val editableChoice: Boolean = false,
        val multiSelect: Boolean = false,
    )

    data class Inspection(
        val sha256: String,
        val pages: Int,
        val fields: List<Field>,
        val xfa: String,
        val signatures: Int,
        val mdpPermission: Int,
    )

    suspend fun inspect(input: File): Inspection =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val form = doc.documentCatalog.getAcroForm(null)
            Inspection(
                PdfAssembly.sha256(input),
                doc.numberOfPages,
                form
                    ?.fieldTree
                    ?.filterIsInstance<PDTerminalField>()
                    ?.map { field ->
                        val options =
                            when (field) {
                                is PDChoice ->
                                    field.optionsExportValues.mapIndexed { i, export ->
                                        Option(
                                            export,
                                            field.optionsDisplayValues.getOrElse(i) { export },
                                        )
                                    }
                                is PDButton -> field.onValues.sorted().map { Option(it, it) }
                                else -> emptyList()
                            }
                        val kind =
                            when (field) {
                                is PDTextField -> "text"
                                is PDCheckBox -> "checkbox"
                                is PDRadioButton -> "radio"
                                is PDChoice -> "choice"
                                is PDSignatureField -> "signature"
                                else -> "unsupported"
                            }
                        Field(
                            field.fullyQualifiedName,
                            field.alternateFieldName?.takeIf { it.isNotBlank() }
                                ?: field.fullyQualifiedName,
                            kind,
                            if (field is PDChoice) field.value else listOf(field.valueAsString),
                            options,
                            field.isReadOnly ||
                                kind == "unsupported" ||
                                field is PDSignatureField && field.signature != null,
                            field.isRequired,
                            (field as? PDTextField)?.isMultiline ?: false,
                            (field as? PDTextField)?.isPassword ?: false,
                            (field as? PDTextField)?.maxLen ?: 0,
                            (field as? PDComboBox)?.isEdit ?: false,
                            (field as? PDChoice)?.isMultiSelect ?: false,
                        )
                    }
                    .orEmpty(),
                when {
                    form?.hasXFA() != true -> "none"
                    form.xfaIsDynamic() -> "dynamic"
                    else -> "static-hybrid"
                },
                doc.signatureDictionaries.size,
                PdfCertificates.mdpPermission(doc),
            )
        }

    fun request(
        info: Inspection,
        values: Map<String, List<String>>,
        dropStaticXfa: Boolean,
    ): JSONObject =
        JSONObject()
            .put("schema", 1)
            .put("sourceSha256", info.sha256)
            .put("dropStaticXfa", dropStaticXfa)
            .put(
                "values",
                JSONObject().apply {
                    info.fields
                        .filter { !it.readOnly && it.kind !in listOf("signature", "unsupported") }
                        .forEach { field ->
                            val value = values[field.name] ?: field.values
                            if (value != field.values) put(field.name, JSONArray(value))
                        }
                },
            )

    suspend fun fill(context: Context, input: File, output: File, request: JSONObject): JSONObject {
        require(input.canonicalFile != output.canonicalFile) { "表单输出不能覆盖原件" }
        require(PdfAssembly.sha256(input) == request.getString("sourceSha256")) { "原表单已变化，请重新打开" }
        val info = inspect(input)
        require(info.mdpPermission != 1) { "文档认证签名禁止填写" }
        require(info.xfa != "dynamic") { "动态 XFA 需要专用表单引擎；原文件保留" }
        require(info.xfa == "none" || request.optBoolean("dropStaticXfa")) {
            "请先选择另存 AcroForm 兼容副本"
        }
        val part = File(output.path + ".part")
        output.parentFile?.mkdirs()
        val changed = request.getJSONObject("values")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val form = doc.documentCatalog.getAcroForm(null) ?: error("PDF 没有 AcroForm 字段")
                if (form.hasXFA()) form.cosObject.removeItem(COSName.XFA)
                var cjk: String? = null
                for (key in changed.keys()) {
                    val descriptor =
                        info.fields.firstOrNull { it.name == key } ?: error("字段 $key 不存在")
                    require(
                        !descriptor.readOnly &&
                            descriptor.kind !in listOf("signature", "unsupported")
                    ) {
                        "字段 $key 不可填写"
                    }
                    val values =
                        changed.getJSONArray(key).let { array ->
                            List(array.length()) { array.getString(it) }
                        }
                    val field = form.getField(key)
                    if (
                        field is PDVariableText &&
                            values.any { value -> value.any { it.code > 255 } }
                    ) {
                        if (cjk == null) {
                            val resources =
                                form.defaultResources
                                    ?: PDResources().also { form.defaultResources = it }
                            context.assets.open("fonts/PaperForgeCJK-Regular.ttf").use { stream ->
                                val font = PDType0Font.load(doc, stream, false)
                                cjk = resources.add(font).name
                            }
                        }
                        // Only the changed text/choice gets a fallback font; all other appearances
                        // survive.
                        val old = field.defaultAppearance ?: form.defaultAppearance.orEmpty()
                        val size =
                            Regex("/\\S+\\s+([0-9.]+)\\s+Tf").find(old)?.groupValues?.get(1) ?: "0"
                        field.defaultAppearance = "/$cjk $size Tf 0 g"
                    }
                    when (field) {
                        is PDTextField -> {
                            val text = values.singleOrNull() ?: ""
                            require(
                                field.maxLen <= 0 ||
                                    text.codePointCount(0, text.length) <= field.maxLen
                            ) {
                                "$key 超出字段长度"
                            }
                            field.value = text
                        }
                        is PDCheckBox -> {
                            val value = values.singleOrNull() ?: "Off"
                            require(value == "Off" || value in field.onValues) { "$key 选项已变化" }
                            field.value = value
                        }
                        is PDRadioButton -> {
                            val value = values.singleOrNull() ?: "Off"
                            require(
                                value == "Off" ||
                                    value in field.onValues ||
                                    value in field.exportValues
                            ) {
                                "$key 选项已变化"
                            }
                            field.value = value
                        }
                        is PDChoice -> {
                            require(field.isMultiSelect || values.size <= 1) { "$key 不允许多选" }
                            if ((field as? PDComboBox)?.isEdit != true)
                                require(values.all { it in field.optionsExportValues }) {
                                    "$key 选项已变化"
                                }
                            if (values.size == 1) field.setValue(values.first())
                            else field.setValue(values)
                        }
                        else -> error("$key 的字段类型不可填写")
                    }
                }
                // Only modified form objects are rewritten. Rewriting an old /Sig dictionary
                // would make PDFBox treat its existing ByteRange as a new signature placeholder.
                val dictionaries =
                    mutableSetOf<COSDictionary>(form.cosObject, doc.documentCatalog.cosObject)
                form.defaultResources?.let { dictionaries += it.cosObject }
                val seen =
                    java.util.Collections.newSetFromMap(
                        java.util.IdentityHashMap<COSBase, Boolean>()
                    )
                fun appearanceObjects(value: COSBase?) {
                    if (value == null || !seen.add(value)) return
                    when (value) {
                        is COSObject -> appearanceObjects(value.`object`)
                        is COSDictionary -> {
                            dictionaries += value
                            value.values.forEach(::appearanceObjects)
                        }
                        is COSArray -> value.forEach(::appearanceObjects)
                    }
                }
                for (key in changed.keys()) {
                    val field = form.getField(key)
                    var parent: PDField? = field
                    while (parent != null) {
                        dictionaries += parent.cosObject
                        parent = parent.parent
                    }
                    field.widgets.forEach { widget ->
                        dictionaries += widget.cosObject
                        appearanceObjects(widget.cosObject.getDictionaryObject(COSName.AP))
                    }
                }
                dictionaries.forEach { it.isNeedToBeUpdated = true }
                part.outputStream().use { doc.saveIncremental(it, dictionaries) }
            }
            check(part.renameTo(output)) { "无法保存表单副本" }
            return JSONObject()
                .put("sourceSha256", info.sha256)
                .put("changedFields", changed.length())
                .put("removedStaticXfa", info.xfa == "static-hybrid")
                .put("previousSignatures", info.signatures)
                .put("signaturePolicyRevalidated", false)
        } finally {
            part.delete()
        }
    }
}

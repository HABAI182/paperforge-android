package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.*
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import org.json.JSONObject

/** Copies only semantics whose page targets survived selection. Never clones a source page tree. */
internal class PdfSemantics(
    private val destination: PDDocument,
    private val inputs: Map<String, PDDocument>,
    private val copies: List<Pair<AssemblyPage, PDPage>>,
    private val cloner: PDFCloneUtility,
    private val plan: AssemblyPlan,
) {
    private val targets =
        copies.filter { it.first.sourceId != null }.groupBy { it.first.sourceId!! to it.first.page }
    private val widgets = IdentityHashMap<COSDictionary, MutableList<PDAnnotationWidget>>()
    private val fieldByWidget = IdentityHashMap<COSDictionary, PDTerminalField>()
    private val clonedFields = IdentityHashMap<COSDictionary, MutableList<COSDictionary>>()
    private val fieldNames = mutableMapOf<Pair<String, Int>, MutableMap<String, String>>()
    private val sourceFontNames = mutableMapOf<String, Map<COSName, COSName>>()
    private var omittedLinks = 0
    private var preservedFields = 0
    private var copiedAttachments = 0
    private var signatureAppearances = 0

    init {
        inputs.values.forEach { doc ->
            doc.documentCatalog.acroForm?.fieldTree?.filterIsInstance<PDTerminalField>()?.forEach {
                field ->
                field.widgets.forEach { fieldByWidget[it.cosObject] = field }
            }
        }
    }

    private fun resolved(source: String, value: PDDestination?): PDPageDestination? {
        val actual =
            if (value is PDNamedDestination)
                inputs.getValue(source).documentCatalog.findNamedDestinationPage(value)
            else value
        return actual as? PDPageDestination
    }

    private fun pageIndex(source: String, value: PDPageDestination): Int? {
        val input = inputs.getValue(source)
        return (value.page?.let { input.pages.indexOf(it) } ?: value.retrievePageNumber()).takeIf {
            it in 0 until input.numberOfPages
        }
    }

    private fun target(
        source: String,
        value: PDDestination?,
        self: Pair<AssemblyPage, PDPage>? = null,
    ): PDPageDestination? {
        val original = resolved(source, value) ?: return null
        val index = pageIndex(source, original) ?: return null
        val page =
            if (self?.first?.page == index && self.first.sourceId == source) self.second
            else targets[source to index]?.firstOrNull()?.second ?: return null
        val array =
            COSArray().apply {
                add(page.cosObject)
                for (n in 1 until original.cosObject.size()) add(
                    cloner.cloneForNewDocument(original.cosObject.getObject(n))
                )
            }
        return PDDestination.create(array) as PDPageDestination
    }

    private fun action(
        source: String,
        original: COSDictionary,
        self: Pair<AssemblyPage, PDPage>? = null,
        depth: Int = 0,
    ): COSDictionary? {
        if (depth > 32) return null
        val type = original.getNameAsString(COSName.S)
        val safe = COSDictionary(original)
        safe.removeItem(COSName.NEXT)
        if (type == "GoTo") {
            val old = original.getDictionaryObject(COSName.D) ?: return null
            val mapped = target(source, PDDestination.create(old), self) ?: return null
            safe.removeItem(COSName.D)
            val result = cloner.cloneForNewDocument(safe) as COSDictionary
            result.setItem(COSName.D, mapped)
            copyNext(source, original, result, self, depth)
            return result
        }
        val result = cloner.cloneForNewDocument(safe) as COSDictionary
        copyNext(source, original, result, self, depth)
        return result
    }

    private fun copyNext(
        source: String,
        old: COSDictionary,
        out: COSDictionary,
        self: Pair<AssemblyPage, PDPage>?,
        depth: Int,
    ) {
        when (val next = old.getDictionaryObject(COSName.NEXT)) {
            is COSDictionary ->
                action(source, next, self, depth + 1)?.let { out.setItem(COSName.NEXT, it) }
            is COSArray -> {
                val array = COSArray()
                for (i in 0 until next.size()) (next.getObject(i) as? COSDictionary)
                    ?.let { action(source, it, self, depth + 1) }
                    ?.let(array::add)
                if (array.size() > 0) out.setItem(COSName.NEXT, array)
            }
        }
    }

    private fun additionalActions(
        source: String,
        old: COSDictionary?,
        self: Pair<AssemblyPage, PDPage>? = null,
        allowed: Set<String>? = null,
    ): COSDictionary? {
        if (old == null) return null
        val result = COSDictionary()
        for (key in old.keySet()) if (allowed == null || key.name in allowed)
            (old.getDictionaryObject(key) as? COSDictionary)
                ?.let { action(source, it, self) }
                ?.let { result.setItem(key, it) }
        return result.takeIf { it.size() > 0 }
    }

    fun copyAnnotationsAndForms() {
        for (copy in copies) {
            val (entry, page) = copy
            val source = entry.sourceId ?: continue
            val original = inputs.getValue(source).getPage(entry.page)
            val annotations = mutableListOf<PDAnnotation>()
            for (annotation in original.annotations) {
                if (annotation is PDAnnotationFileAttachment && !plan.keepAttachments) continue
                val dictionary = COSDictionary(annotation.cosObject)
                listOf("P", "Parent", "Popup", "IRT", "A", "AA", "Dest", "AP").forEach {
                    dictionary.removeItem(COSName.getPDFName(it))
                }
                val field = fieldByWidget[annotation.cosObject]
                val editable =
                    annotation is PDAnnotationWidget &&
                        plan.formMode == "EDITABLE" &&
                        field != null &&
                        field !is PDSignatureField
                if (annotation is PDAnnotationWidget) {
                    if (!editable) {
                        require(annotation.cosObject.getDictionaryObject(COSName.AP) != null) {
                            "来源“${plan.sources.first {it.documentId==source}.title}”的表单缺少可见外观"
                        }
                        dictionary.setName(COSName.SUBTYPE, "Stamp")
                        if (field is PDSignatureField) signatureAppearances++
                    }
                    listOf(
                            "T",
                            "TU",
                            "TM",
                            "FT",
                            "Ff",
                            "V",
                            "DV",
                            "DA",
                            "Q",
                            "Opt",
                            "MaxLen",
                            "Kids",
                        )
                        .forEach { dictionary.removeItem(COSName.getPDFName(it)) }
                }
                val link = annotation as? PDAnnotationLink
                if (link?.destination != null) {
                    val mapped = target(source, link.destination, copy)
                    if (mapped == null) {
                        omittedLinks++
                        continue
                    }
                }
                val copied = PDAnnotation.createAnnotation(cloner.cloneForNewDocument(dictionary))
                annotation.cosObject.getDictionaryObject(COSName.AP)?.let {
                    copied.cosObject.setItem(COSName.AP, cloneAppearance(it))
                }
                // A destination already owned by the output must not be fed through the source
                // cloner.
                if (link?.destination != null)
                    (copied as PDAnnotationLink).destination =
                        target(source, link.destination, copy)
                val oldAction =
                    annotation.cosObject.getDictionaryObject(COSName.A) as? COSDictionary
                if (oldAction != null) {
                    val mapped = action(source, oldAction, copy)
                    if (mapped == null && link != null) {
                        omittedLinks++
                        continue
                    }
                    mapped?.let { copied.cosObject.setItem(COSName.A, it) }
                }
                additionalActions(
                        source,
                        annotation.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary,
                        copy,
                        if (annotation is PDAnnotationWidget)
                            setOf("E", "X", "D", "U", "Fo", "Bl", "PO", "PC", "PV", "PI")
                        else null,
                    )
                    ?.let { copied.cosObject.setItem(COSName.AA, it) }
                copied.page = page
                annotations += copied
                if (editable)
                    widgets
                        .getOrPut(annotation.cosObject) { mutableListOf() }
                        .add(copied as PDAnnotationWidget)
            }
            page.annotations = annotations
        }
        if (plan.formMode == "EDITABLE") copyForms()
    }

    // Each widget owns its appearance content: editing a duplicated field must never redraw its
    // sibling. Fonts/images remain shared; only the mutable streams and resource maps are copied.
    private fun cloneAppearance(value: COSBase, depth: Int = 0): COSBase {
        if (value is COSStream) {
            val stream =
                value.createInputStream().use {
                    PDStream(destination, it, COSName.FLATE_DECODE).cosObject
                }
            for (key in value.keySet()) {
                if (key in setOf(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS)) continue
                val copied = cloner.cloneForNewDocument(value.getDictionaryObject(key))
                stream.setItem(
                    key,
                    if (key == COSName.RESOURCES && copied is COSDictionary) {
                        COSDictionary(copied).apply {
                            for (resourceKey in copied.keySet()) {
                                (copied.getDictionaryObject(resourceKey) as? COSDictionary)?.let {
                                    setItem(resourceKey, COSDictionary(it))
                                }
                            }
                        }
                    } else copied,
                )
            }
            return stream
        }
        if (value is COSDictionary && depth < 4)
            return COSDictionary().apply {
                for (key in value.keySet()) setItem(
                    key,
                    cloneAppearance(value.getDictionaryObject(key), depth + 1),
                )
            }
        return cloner.cloneForNewDocument(value)
    }

    private fun copyForms() {
        val form = PDAcroForm(destination)
        val resources = PDResources()
        resources.put(COSName.getPDFName("PFDefault"), PDType1Font.HELVETICA)
        form.defaultResources = resources
        form.defaultAppearance = "/PFDefault 12 Tf 0 g"
        form.needAppearances = false
        val roots = COSArray()
        val occupied = mutableSetOf<String>()
        val calculationOrder = COSArray()
        for ((number, source) in plan.chapters.withIndex()) {
            val input = inputs.getValue(source.documentId)
            val original = input.documentCatalog.acroForm ?: continue
            require(!original.hasXFA()) { "“${source.title}”含动态 XFA 表单，请先另存为普通 PDF" }
            sourceFontNames[source.documentId] = mergeFonts(resources, original, number + 1)
            for (root in original.fields) {
                val maximum =
                    terminalFields(root)
                        .flatMap { field -> field.widgets.map { widgets[it.cosObject]?.size ?: 0 } }
                        .maxOrNull() ?: 0
                for (generation in 0 until maximum) {
                    var rootName = root.partialName?.ifBlank { "field" } ?: "field"
                    if (generation > 0) rootName += "_copy${generation+1}"
                    val base = rootName
                    var suffix = 2
                    while (!occupied.add(rootName)) {
                        rootName = "${base}_$suffix"
                        suffix++
                    }
                    val cloned =
                        cloneField(source.documentId, root, null, generation, rootName, rootName)
                            ?: continue
                    if (!cloned.containsKey(COSName.DA))
                        cloned.setString(
                            COSName.DA,
                            renameAppearance(
                                source.documentId,
                                original.defaultAppearance ?: form.defaultAppearance,
                            ),
                        )
                    if (!cloned.containsKey(COSName.Q)) cloned.setInt(COSName.Q, original.q)
                    roots.add(cloned)
                }
            }
            (original.cosObject.getDictionaryObject(COSName.CO) as? COSArray)?.let { order ->
                for (i in 0 until order.size()) (order.getObject(i) as? COSDictionary)
                    ?.let { clonedFields[it] }
                    .orEmpty()
                    .forEach(calculationOrder::add)
            }
        }
        if (roots.size() == 0) return
        form.cosObject.setItem(COSName.FIELDS, roots)
        if (calculationOrder.size() > 0) form.cosObject.setItem(COSName.CO, calculationOrder)
        destination.documentCatalog.acroForm = form
        // Rewrite common literal getField references after all collision-renamed names are known.
        for ((sourceAndGeneration, names) in fieldNames) {
            val all = sourceAndGeneration.first
            val references = fieldNames[all to 0].orEmpty() + names
            for (field in form.fieldTree) {
                if (field.fullyQualifiedName !in names.values) continue
                rewriteScripts(field.cosObject, references)
                (field as? PDTerminalField)?.widgets?.forEach {
                    rewriteScripts(it.cosObject, references)
                }
            }
        }
    }

    private fun terminalFields(field: PDField): List<PDTerminalField> =
        if (field is PDNonTerminalField) field.children.flatMap(::terminalFields)
        else listOfNotNull(field as? PDTerminalField)

    private fun cloneField(
        source: String,
        field: PDField,
        parent: COSDictionary?,
        generation: Int,
        name: String,
        fullName: String,
    ): COSDictionary? {
        if (field is PDSignatureField) return null
        val children = if (field is PDNonTerminalField) field.children else emptyList()
        if (
            children.isNotEmpty() &&
                terminalFields(field).none { leaf ->
                    leaf.widgets.any { widgets[it.cosObject]?.getOrNull(generation) != null }
                }
        )
            return null
        val selected =
            if (field is PDTerminalField)
                field.widgets.mapNotNull { widgets[it.cosObject]?.getOrNull(generation) }
            else emptyList()
        if (field is PDTerminalField && selected.isEmpty()) return null
        val safe = COSDictionary(field.cosObject)
        listOf(
                "Parent",
                "Kids",
                "P",
                "Subtype",
                "Rect",
                "AP",
                "AS",
                "MK",
                "F",
                "H",
                "BS",
                "Border",
                "A",
                "AA",
            )
            .forEach { safe.removeItem(COSName.getPDFName(it)) }
        val out = cloner.cloneForNewDocument(safe) as COSDictionary
        out.setString(COSName.T, name)
        parent?.let { out.setItem(COSName.PARENT, it) }
        out.getString(COSName.DA)?.let { out.setString(COSName.DA, renameAppearance(source, it)) }
        additionalActions(
                source,
                field.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary,
                allowed = setOf("K", "F", "V", "C"),
            )
            ?.let { out.setItem(COSName.AA, it) }
        val kids = COSArray()
        children.forEach { child ->
            val part = child.partialName ?: "field"
            cloneField(source, child, out, generation, part, "$fullName.$part")?.let(kids::add)
        }
        selected.forEach { widget ->
            widget.cosObject.setItem(COSName.PARENT, out)
            kids.add(widget)
        }
        if (kids.size() == 0) return null
        out.setItem(COSName.KIDS, kids)
        if (field is PDTerminalField) {
            fieldNames.getOrPut(source to generation) { mutableMapOf() }[field.fullyQualifiedName] =
                fullName
            clonedFields.getOrPut(field.cosObject) { mutableListOf() }.add(out)
            preservedFields++
        }
        return out
    }

    private fun mergeFonts(
        target: PDResources,
        source: PDAcroForm,
        number: Int,
    ): Map<COSName, COSName> {
        val fontDictionary =
            (target.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary)
                ?: COSDictionary().also { target.cosObject.setItem(COSName.FONT, it) }
        val original =
            source.defaultResources?.cosObject?.getDictionaryObject(COSName.FONT) as? COSDictionary
                ?: return emptyMap()
        return original.keySet().associateWith { old ->
            COSName.getPDFName("S${number}_${old.name}").also { name ->
                fontDictionary.setItem(
                    name,
                    cloner.cloneForNewDocument(original.getDictionaryObject(old)),
                )
            }
        }
    }

    private fun renameAppearance(source: String, value: String): String {
        val parser = PDFStreamParser(value.toByteArray(Charsets.ISO_8859_1))
        parser.parse()
        val names = sourceFontNames[source].orEmpty()
        val tokens = parser.tokens.map { if (it is COSName) names[it] ?: it else it }
        return ByteArrayOutputStream().use { output ->
            ContentStreamWriter(output).writeTokens(tokens)
            output.toString("ISO-8859-1")
        }
    }

    private fun rewriteScripts(dictionary: COSDictionary, names: Map<String, String>) {
        val actions = mutableListOf<COSDictionary>()
        (dictionary.getDictionaryObject(COSName.A) as? COSDictionary)?.let(actions::add)
        (dictionary.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { aa ->
            aa.keySet().forEach { key ->
                (aa.getDictionaryObject(key) as? COSDictionary)?.let(actions::add)
            }
        }
        actions
            .filter { it.getNameAsString(COSName.S) == "JavaScript" }
            .forEach { action ->
                val old = action.getDictionaryObject(COSName.JS)
                val script =
                    when (old) {
                        is COSString -> old.string
                        is COSStream ->
                            old.createInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
                        else -> null
                    } ?: return@forEach
                action.setString(
                    COSName.JS,
                    Regex("getField\\(\\s*(['\"])(.*?)\\1\\s*\\)").replace(script) { match ->
                        names[match.groupValues[2]]?.let {
                            "getField(${match.groupValues[1]}$it${match.groupValues[1]})"
                        } ?: match.value
                    },
                )
            }
    }

    fun copyOutline(source: String, parent: PDOutlineItem) {
        val visited = java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun copy(start: PDOutlineItem?, into: PDOutlineNode, depth: Int) {
            if (depth > 32) return
            var current = start
            while (current != null && visited.size < 10000 && visited.add(current.cosObject)) {
                val old = current
                val mapped =
                    target(source, old.destination ?: (old.action as? PDActionGoTo)?.destination)
                val node =
                    PDOutlineItem().apply {
                        title = old.title
                        isBold = old.isBold
                        isItalic = old.isItalic
                    }
                mapped?.let(node::setDestination)
                val external =
                    old.action
                        ?.cosObject
                        ?.takeIf { it.getNameAsString(COSName.S) != "GoTo" }
                        ?.let { action(source, it) }
                external?.let { node.cosObject.setItem(COSName.A, it) }
                copy(old.firstChild, node, depth + 1)
                if (mapped != null || external != null || node.firstChild != null) {
                    into.addLast(node)
                    if (old.isNodeOpen) node.openNode()
                }
                current = old.nextSibling
            }
        }
        copy(inputs.getValue(source).documentCatalog.documentOutline?.firstChild, parent, 0)
    }

    fun finishCatalog(): JSONObject {
        if (plan.keepPageLabels) {
            val labels = PDPageLabels(destination)
            for (index in 0 until plan.prefixPages) labels.setLabelItem(
                index,
                PDPageLabelRange().apply {
                    prefix =
                        if (plan.cover && index == 0) "封面"
                        else "目录${index-(if(plan.cover) 1 else 0)+1}"
                },
            )
            val sourceLabels =
                inputs.mapValues { (_, doc) -> doc.documentCatalog.pageLabels?.labelsByPageIndices }
            for ((index, entry) in copies.withIndex()) {
                val value =
                    entry.first.sourceId?.let {
                        sourceLabels[it]?.getOrNull(entry.first.page)
                            ?: (entry.first.page + 1).toString()
                    } ?: "空白"
                labels.setLabelItem(
                    plan.prefixPages + index,
                    PDPageLabelRange().apply { prefix = value },
                )
            }
            destination.documentCatalog.pageLabels = labels
        }
        copyNames()
        return JSONObject()
            .put("editableFields", preservedFields)
            .put("attachments", copiedAttachments)
            .put("omittedLinks", omittedLinks)
            .put("signatureAppearances", signatureAppearances)
    }

    private fun copyNames() {
        val names = PDDocumentNameDictionary(destination.documentCatalog)
        val destinations = sortedMapOf<String, PDPageDestination>()
        val attachments = sortedMapOf<String, PDComplexFileSpecification>()
        val mappedFiles = IdentityHashMap<COSDictionary, PDComplexFileSpecification>()
        val associated = COSArray()
        val af = COSName.getPDFName("AF")
        fun unique(value: String, used: Set<String>, file: Boolean = false): String {
            var result = value
            var count = 2
            val dot = if (file) value.lastIndexOf('.').takeIf { it > 0 } else null
            while (used.any { it.equals(result, true) }) {
                result =
                    if (dot == null) "$value ($count)"
                    else value.take(dot) + " ($count)" + value.substring(dot)
                count++
            }
            return result
        }
        fun copyFile(
            original: PDComplexFileSpecification,
            label: String,
        ): PDComplexFileSpecification =
            mappedFiles.getOrPut(original.cosObject) {
                val name = unique(label.ifBlank { "attachment" }, attachments.keys, true)
                PDComplexFileSpecification(
                        cloner.cloneForNewDocument(original.cosObject) as COSDictionary
                    )
                    .also { out ->
                        out.file = name
                        out.fileUnicode = name
                        attachments[name] = out
                        copiedAttachments++
                    }
            }
        fun copyAssociated(original: COSArray?): COSArray {
            val result = COSArray()
            if (original != null)
                for (index in 0 until original.size()) (original.getObject(index) as? COSDictionary)
                    ?.let { dictionary ->
                        val spec = PDComplexFileSpecification(dictionary)
                        result.add(copyFile(spec, spec.fileUnicode ?: spec.file ?: "attachment"))
                    }
            return result
        }
        for (source in plan.chapters) {
            val catalog = inputs.getValue(source.documentId).documentCatalog
            fun destTree(node: PDNameTreeNode<PDPageDestination>?, depth: Int = 0) {
                if (node == null || depth > 32) return
                node.names.orEmpty().forEach { (name, value) ->
                    target(source.documentId, value)?.let {
                        destinations[unique(name, destinations.keys)] = it
                    }
                }
                node.kids.orEmpty().forEach { destTree(it, depth + 1) }
            }
            destTree(catalog.names?.dests)
            (catalog.cosObject.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { legacy
                ->
                legacy.keySet().forEach { key ->
                    catalog
                        .findNamedDestinationPage(PDNamedDestination(key.name))
                        ?.let { target(source.documentId, it) }
                        ?.let { destinations[unique(key.name, destinations.keys)] = it }
                }
            }
            if (plan.keepAttachments) {
                fun fileTree(node: PDNameTreeNode<PDComplexFileSpecification>?, depth: Int = 0) {
                    if (node == null || depth > 32) return
                    node.names.orEmpty().forEach { (name, value) -> copyFile(value, name) }
                    node.kids.orEmpty().forEach { fileTree(it, depth + 1) }
                }
                fileTree(catalog.names?.embeddedFiles)
                copyAssociated(catalog.cosObject.getDictionaryObject(af) as? COSArray).let { array
                    ->
                    for (index in 0 until array.size()) associated.add(array.getObject(index))
                }
                for ((ref, page) in copies.filter { it.first.sourceId == source.documentId }) {
                    val original = inputs.getValue(source.documentId).getPage(ref.page)
                    val list =
                        copyAssociated(original.cosObject.getDictionaryObject(af) as? COSArray)
                    if (list.size() > 0) page.cosObject.setItem(af, list)
                    original.annotations.filterIsInstance<PDAnnotationFileAttachment>().forEach {
                        annotation ->
                        (annotation.file as? PDComplexFileSpecification)?.let { spec ->
                            copyFile(spec, spec.fileUnicode ?: spec.file ?: "attachment")
                        }
                    }
                }
            }
        }
        if (associated.size() > 0) destination.documentCatalog.cosObject.setItem(af, associated)
        if (destinations.isNotEmpty())
            names.dests = PDDestinationNameTreeNode().apply { this.names = destinations }
        if (attachments.isNotEmpty())
            names.embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { this.names = attachments }
        if (destinations.isNotEmpty() || attachments.isNotEmpty())
            destination.documentCatalog.names = names
    }
}

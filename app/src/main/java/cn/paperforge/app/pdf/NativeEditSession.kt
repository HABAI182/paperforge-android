package cn.paperforge.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A character range of one text object, in the object's current (edited) text. */
data class NativeSegment(val key: String, val start: Int, val end: Int)

/**
 * Draft operations over selections. Selections are made on the shown page, whose objects carry their current text;
 * edits keep the original text in [NativePdfEditor.Edit.before].
 */
object NativeEditOps {
    fun runs(edits: List<NativePdfEditor.Edit>, item: NativePdfEditor.TextObject): List<NativeRun> =
        edits.firstOrNull { it.key == item.key }?.effectiveRuns ?: NativeTextRuns.initial(item.text)

    private fun put(
        edits: List<NativePdfEditor.Edit>,
        item: NativePdfEditor.TextObject,
        runs: List<NativeRun>,
        fit: Boolean,
    ): List<NativePdfEditor.Edit> {
        val edit = NativePdfEditor.Edit.of(item, runs, fit)
        if (edit == null) return edits.filter { it.key != item.key }
        return if (edits.any { it.key == item.key }) edits.map { if (it.key == item.key) edit else it }
        else edits + edit
    }

    /** Restyles every segment. [originals] are the source page's objects (for the original text). */
    fun restyle(
        edits: List<NativePdfEditor.Edit>,
        originals: Map<String, NativePdfEditor.TextObject>,
        segments: List<NativeSegment>,
        style: NativeStyle,
    ): List<NativePdfEditor.Edit> {
        var out = edits
        for (segment in segments) {
            val item = originals[segment.key] ?: continue
            if (!item.editable) continue
            val current = runs(out, item)
            val next =
                NativeTextRuns.restyle(current, segment.start, segment.end, style) { item.size }
            val fit = out.firstOrNull { it.key == item.key }?.fit ?: (style.size == null && style.sizeFactor == null)
            out = put(out, item, next, if (style.size != null || style.sizeFactor != null) false else fit)
        }
        return out
    }

    /**
     * Replaces the selected text. With as many lines as segments, line i replaces segment i (a selection over several
     * lines stays on those lines); otherwise everything goes into the first segment and the rest is removed.
     */
    fun replace(
        edits: List<NativePdfEditor.Edit>,
        originals: Map<String, NativePdfEditor.TextObject>,
        segments: List<NativeSegment>,
        text: String,
        style: (NativeSegment) -> NativeStyle? = { null },
    ): List<NativePdfEditor.Edit> {
        val usable = segments.filter { originals[it.key]?.editable == true }
        if (usable.isEmpty()) return edits
        val lines = text.split('\n')
        val pieces =
            if (lines.size == usable.size) lines
            else listOf(lines.joinToString(" ")) + List(usable.size - 1) { "" }
        var out = edits
        // Later segments of one object first, so earlier offsets stay valid.
        val ordered = usable.zip(pieces).sortedWith(compareBy({ it.first.key }, { -it.first.start }))
        for ((segment, piece) in ordered) {
            val item = originals.getValue(segment.key)
            val current = runs(out, item)
            val clean = java.text.Normalizer.normalize(piece, java.text.Normalizer.Form.NFC)
            // Only what changed is replaced: unchanged characters at either end keep their original glyphs.
            val old = NativeTextRuns.text(current).let { t -> t.substring(segment.start.coerceIn(0, t.length), segment.end.coerceIn(0, t.length)) }
            var head = 0
            while (head < old.length && head < clean.length && old[head] == clean[head]) head++
            var tail = 0
            while (tail < old.length - head && tail < clean.length - head && old[old.length - 1 - tail] == clean[clean.length - 1 - tail]) tail++
            if (head == old.length && head == clean.length) continue
            val next =
                NativeTextRuns.replace(
                    current,
                    segment.start + head,
                    segment.end - tail,
                    clean.substring(head, clean.length - tail),
                    style(segment),
                )
            out = put(out, item, next, out.firstOrNull { it.key == item.key }?.fit ?: true)
        }
        return out
    }

    /** Restores the selected objects to the original. */
    fun restore(edits: List<NativePdfEditor.Edit>, keys: Set<String>) = edits.filter { it.key !in keys }
}

/** Selection support over shown objects. */
object NativeSelection {
    fun blocks(objects: List<NativePdfEditor.TextObject>): List<ReaderTextBlock> =
        objects
            .filter { it.units.isNotEmpty() && it.text.isNotBlank() && !it.invisible }
            .map { o ->
                ReaderTextBlock(
                    o.text,
                    o.units.minOf { it.left },
                    o.units.minOf { it.top },
                    o.units.maxOf { it.right },
                    o.units.maxOf { it.bottom },
                    "obj:${o.key}",
                    o.units,
                )
            }

    fun page(page: Int, objects: List<NativePdfEditor.TextObject>) = ReaderSelectionPage(page, blocks(objects))

    /** The object segments a selection covers, in reading order. */
    fun segments(selection: ReaderTextSelection): List<NativeSegment> =
        selection.model.units
            .subList(selection.first, selection.last + 1)
            .groupBy { it.line }
            .map { (line, units) ->
                val key = selection.model.ordered[line].source.removePrefix("obj:")
                NativeSegment(key, units.minOf { it.span.start }, units.maxOf { it.span.end })
            }

    /** Selects all characters of the objects [keys]. */
    fun whole(objects: List<NativePdfEditor.TextObject>, keys: Collection<String>) =
        objects.filter { it.key in keys }.map { NativeSegment(it.key, 0, it.text.length) }

    /** The selection covering one whole object. */
    fun objectSelection(model: ReaderSelectionPage, key: String): ReaderTextSelection? {
        val indices = model.units.indices.filter { model.ordered[model.units[it].line].source == "obj:$key" }
        if (indices.isEmpty()) return null
        return ReaderTextSelection(model, indices.first(), indices.last())
    }

    fun text(objects: List<NativePdfEditor.TextObject>, segments: List<NativeSegment>): String =
        segments.joinToString("\n") { s ->
            objects.firstOrNull { it.key == s.key }?.text?.let { t ->
                t.substring(s.start.coerceIn(0, t.length), s.end.coerceIn(0, t.length))
            }.orEmpty()
        }
}

/**
 * One open editing session: the source stays open on a single thread, its pages are parsed once, and previews render
 * only the edited page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeEditSession(private val context: Context, val input: File) : Closeable {
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private var pdf: PDDocument? = null
    private val parsed = mutableMapOf<Int, NativePdfEditor.Page>()
    private val inspected = mutableMapOf<Int, List<NativePdfEditor.TextObject>>()
    private val previews = mutableListOf<File>()
    var count = 0
        private set

    private fun doc(): PDDocument =
        pdf ?: PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).also {
            pdf = it
            count = it.numberOfPages
        }

    suspend fun open(): Int = withContext(thread) { doc().numberOfPages }

    /** The source page's objects (original text). */
    suspend fun original(page: Int): List<NativePdfEditor.TextObject> =
        withContext(thread) {
            inspected.getOrPut(page) {
                val d = doc()
                require(page in 0 until d.numberOfPages) { "页码无效" }
                val p = parsed.getOrPut(page) { NativePdfEditor.page(d, page) }
                NativePdfEditor.objects(d, page, p)
            }
        }

    /** Characters of [text] the object's own (often subset) font cannot show. */
    suspend fun missingInOriginal(key: String, text: String): List<String> =
        withContext(thread) {
            val page = key.substringBefore(':').toInt()
            val index = key.substringAfter(':').toInt()
            val p = parsed.getOrPut(page) { NativePdfEditor.page(doc(), page) }
            val font = p.spans.firstOrNull { it.objectInfo.index == index }?.base?.font ?: return@withContext listOf(text)
            NativePdfEditor.missing(font, text)
        }

    class Shown(
        val file: File,
        val objects: List<NativePdfEditor.TextObject>,
        val outcomes: List<NativePdfEditor.Outcome>,
        val millis: Long,
    )

    /** Renders [page] with [edits] into a one-page PDF and reads its objects back. */
    suspend fun preview(page: Int, edits: List<NativePdfEditor.Edit>, fontFiles: Map<String, File>): Shown =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val file = File(context.cacheDir, "native-shown-${UUID.randomUUID()}.pdf")
            synchronized(previews) { previews += file }
            val result = NativePdfEditor.preview(context, input, page, edits, file, fontFiles)
            val objects =
                PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { shown ->
                    NativePdfEditor.objects(shown, 0).map { it.copy(page = page) }
                }
            Shown(file, objects, result.outcomes, (System.nanoTime() - started) / 1_000_000)
        }

    fun discard(file: File) {
        synchronized(previews) { previews.remove(file) }
        file.delete()
    }

    override fun close() {
        synchronized(previews) {
            previews.forEach { it.delete() }
            previews.clear()
        }
        runCatching { pdf?.close() }
        pdf = null
    }
}

package cn.paperforge.app.data

import android.graphics.Bitmap
import android.graphics.Color
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.ui.PdfSession
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.*
import org.json.JSONArray

data class ComparePage(val documentId: String, val pageId: String?, val index: Int)

data class SimilarPage(
    val first: ComparePage,
    val second: ComparePage,
    val distance: Int,
    val sameOriginal: Boolean,
) {
    val key
        get() =
            listOf("${first.documentId}:${first.index}", "${second.documentId}:${second.index}")
                .sorted()
                .joinToString("|")
}

data class ComparisonReport(
    val exact: List<List<String>>,
    val similar: List<SimilarPage>,
    val documents: Int,
    val pages: Int,
    val skipped: List<String>,
    val limited: Boolean = false,
)

data class VisualFingerprint(
    val bits: Long,
    val aspect: Float,
    val mean: Float,
    val deviation: Float,
)

/** A candidate cue, never a decision to delete or merge pages. Flat pages have no useful cue. */
fun visualFingerprint(bitmap: Bitmap): VisualFingerprint {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val values = FloatArray(72)
    val counts = IntArray(72)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val cell = (y * 8 / bitmap.height) * 9 + x * 9 / bitmap.width
        val c = pixels[y * bitmap.width + x]
        values[cell] += Color.red(c) * .299f + Color.green(c) * .587f + Color.blue(c) * .114f
        counts[cell]++
    }
    for (i in values.indices) values[i] /= counts[i].coerceAtLeast(1)
    var bits = 0L
    for (y in 0 until 8) for (x in 0 until 8) if (values[y * 9 + x] > values[y * 9 + x + 1] + 1f)
        bits = bits or (1L shl (y * 8 + x))
    val mean = values.average().toFloat()
    return VisualFingerprint(
        bits,
        bitmap.width.toFloat() / bitmap.height,
        mean,
        sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size).toFloat(),
    )
}

private class FingerprintIndex {
    class Node(
        val hash: Long,
        val values: MutableList<Int> = mutableListOf(),
        val children: MutableMap<Int, Node> = mutableMapOf(),
    )

    private var root: Node? = null

    fun add(hash: Long, index: Int) {
        if (root == null) {
            root = Node(hash, mutableListOf(index))
            return
        }
        var node = root!!
        while (true) {
            val distance = java.lang.Long.bitCount(hash xor node.hash)
            if (distance == 0) {
                node.values += index
                return
            }
            val next = node.children[distance]
            if (next == null) {
                node.children[distance] = Node(hash, mutableListOf(index))
                return
            }
            node = next
        }
    }

    fun find(hash: Long, limit: Int): List<Int> {
        val result = mutableListOf<Int>()
        val pending = java.util.ArrayDeque<Node>()
        root?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val distance = java.lang.Long.bitCount(hash xor node.hash)
            if (distance <= limit) result += node.values
            node.children
                .filterKeys { it in (distance - limit)..(distance + limit) }
                .values
                .forEach(pending::add)
        }
        return result
    }
}

suspend fun Repository.compareDocuments(
    ids: Set<String>? = null,
    onProgress: (String) -> Unit = {},
): ComparisonReport =
    withContext(Dispatchers.IO) {
        val documents =
            dao.allDocuments()
                .filter { it.listed && it.trashedAt == null && (ids == null || it.id in ids) }
                .sortedBy { it.id }
        val pages = documents.filter { it.kind == "scan" }.associate { it.id to dao.pages(it.id) }
        val hashes = mutableMapOf<String, String>()
        val exact = mutableMapOf<String, MutableList<String>>()
        val fingerprints = mutableListOf<Triple<ComparePage, String?, VisualFingerprint>>()
        val index = FingerprintIndex()
        val originals = mutableMapOf<String, MutableList<Int>>()
        val similar = mutableListOf<SimilarPage>()
        val keys = mutableSetOf<String>()
        val issues = mutableListOf<String>()
        val indexedDocuments = mutableSetOf<String>()
        var counted = 0
        var limited = false
        suspend fun hash(path: String): String {
            hashes[path]?.let {
                return it
            }
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest
                .digest()
                .joinToString("") { "%02x".format(it.toInt() and 255) }
                .also { hashes[path] = it }
        }
        val representatives = mutableSetOf<String>()
        for ((n, doc) in documents.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress("核对原件 ${n+1} / ${documents.size} · ${doc.title}")
            try {
                val key =
                    if (doc.kind == "pdf") "pdf:${hash(checkNotNull(doc.pdfPath))}"
                    else {
                        val ordered = pages[doc.id].orEmpty().sortedBy { it.position }
                        if (ordered.isEmpty()) continue
                        val state = JSONArray()
                        for (page in ordered) state.put(
                            JSONArray(
                                listOf(
                                    hash(page.original),
                                    page.corners,
                                    page.filter,
                                    page.rotation,
                                    page.brightness,
                                    page.contrast,
                                    page.enhanceStrength,
                                    page.deskewDegrees,
                                    page.outputAspect,
                                    page.ocrText,
                                )
                            )
                        )
                        "scan:" +
                            MessageDigest.getInstance("SHA-256")
                                .digest(state.toString().toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it.toInt() and 255) }
                    }
                val group = exact.getOrPut(key) { mutableListOf() }
                if (group.isEmpty()) representatives += doc.id
                group += doc.id
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                issues += "${doc.title}：${error.message ?: "未能读取原件"}"
            }
        }
        fun add(ref: ComparePage, original: String?, bitmap: Bitmap) {
            val fingerprint = visualFingerprint(bitmap)
            val hasOther =
                indexedDocuments.size > 1 ||
                    indexedDocuments.firstOrNull()?.let { it != ref.documentId } == true
            val candidates =
                if (hasOther)
                    (original?.let { originals[it] }.orEmpty() +
                            if (fingerprint.deviation >= 3f) index.find(fingerprint.bits, 6)
                            else emptyList())
                        .distinct()
                else emptyList()
            for (prior in candidates) {
                val (other, raw, visual) = fingerprints[prior]
                if (other.documentId == ref.documentId) continue
                val same = original != null && raw == original
                val distance = java.lang.Long.bitCount(fingerprint.bits xor visual.bits)
                if (
                    !same &&
                        (visual.deviation < 3f ||
                            abs(fingerprint.aspect / visual.aspect - 1f) > .08f ||
                            abs(fingerprint.mean - visual.mean) > 24f)
                )
                    continue
                val pair = SimilarPage(other, ref, distance, same)
                if (similar.size >= 300) {
                    limited = true
                    break
                }
                if (keys.add(pair.key)) {
                    similar += pair
                }
            }
            val position = fingerprints.size
            fingerprints += Triple(ref, original, fingerprint)
            if (fingerprint.deviation >= 3f) index.add(fingerprint.bits, position)
            original?.let { originals.getOrPut(it) { mutableListOf() }.add(position) }
            indexedDocuments += ref.documentId
            counted++
        }
        for ((n, doc) in documents.withIndex()) {
            if (limited || representatives.size < 2) break
            if (doc.id !in representatives) continue
            currentCoroutineContext().ensureActive()
            onProgress("比较文档 ${n+1} / ${documents.size} · ${doc.title}")
            try {
                if (doc.kind == "pdf") {
                    val file = File(checkNotNull(doc.pdfPath))
                    PdfSession(file).use { pdf ->
                        for (page in 0 until pdf.count) {
                            if (limited) break
                            currentCoroutineContext().ensureActive()
                            val bitmap = pdf.render(page, 256)
                            add(ComparePage(doc.id, null, page), null, bitmap)
                        }
                    }
                } else {
                    val ordered = pages[doc.id].orEmpty().sortedBy { it.position }
                    ordered.forEachIndexed { position, page ->
                        if (limited) return@forEachIndexed
                        currentCoroutineContext().ensureActive()
                        val raw = hashes.getValue(page.original)
                        try {
                            val bitmap = ScanProcessor.render(page, 256)
                            try {
                                add(ComparePage(doc.id, page.id, position), raw, bitmap)
                            } finally {
                                bitmap.recycle()
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            issues +=
                                "${doc.title} · 第 ${position+1} 页：${error.message ?: "未能比较预览"}"
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                issues += "${doc.title}：${error.message ?: "未能读取原件"}"
            }
        }
        val groups = exact.values.filter { it.size > 1 }
        ComparisonReport(
            groups,
            similar.sortedWith(
                compareByDescending<SimilarPage> { it.sameOriginal }.thenBy { it.distance }
            ),
            documents.size,
            counted,
            issues,
            limited,
        )
    }

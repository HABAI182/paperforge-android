package cn.paperforge.app.pdf

import org.json.JSONArray
import org.json.JSONObject

/**
 * The edited content of one native text object, as runs over its current text.
 *
 * A run with [origStart] >= 0 and font NativeRun.ORIGINAL re-uses the original glyph codes [origStart, origEnd) of the object
 * (exact bytes, kerning and per-glyph font state), optionally at another size or colour. Every other run is new text
 * encoded with [font]. Offsets are character offsets in the object's original text.
 */
data class NativeRun(
    val text: String,
    val origStart: Int = -1,
    val origEnd: Int = -1,
    val font: String = ORIGINAL,
    val fontSha256: String = "",
    val size: Float? = null,
    val color: Int? = null,
) {
    val keepsGlyphs
        get() = origStart >= 0 && font == NativeRun.ORIGINAL

    val styled
        get() = font != NativeRun.ORIGINAL || size != null || color != null

    fun sameStyle(other: NativeRun) =
        font == other.font && fontSha256 == other.fontSha256 && size == other.size && color == other.color

    fun json(): JSONObject =
        JSONObject()
            .put("text", text)
            .put("origStart", origStart)
            .put("origEnd", origEnd)
            .put("font", font)
            .put("fontSha256", fontSha256)
            .put("size", size ?: JSONObject.NULL)
            .put("color", color ?: JSONObject.NULL)

    companion object {
        const val ORIGINAL = "ORIGINAL"

        fun decode(j: JSONObject) =
            NativeRun(
                j.getString("text"),
                j.optInt("origStart", -1),
                j.optInt("origEnd", -1),
                j.optString("font", NativeRun.ORIGINAL),
                j.optString("fontSha256"),
                if (j.isNull("size") || !j.has("size")) null else j.getDouble("size").toFloat(),
                if (j.isNull("color") || !j.has("color")) null else j.getLong("color").toInt(),
            )
    }
}

/** A style change; null fields stay as they are. [font] together with [fontSha256]. */
data class NativeStyle(
    val font: String? = null,
    val fontSha256: String = "",
    val size: Float? = null,
    val color: Int? = null,
    /** Multiplies the current (or original) size, for "bigger / smaller" on mixed selections. */
    val sizeFactor: Float? = null,
)

object NativeTextRuns {
    fun initial(before: String) =
        if (before.isEmpty()) emptyList() else listOf(NativeRun(before, 0, before.length))

    fun text(runs: List<NativeRun>) = runs.joinToString("") { it.text }

    fun unchanged(before: String, runs: List<NativeRun>) = normalize(runs) == initial(before)

    /** Splits so that a run boundary lies at [offset] (an offset in the current text). */
    private fun split(runs: List<NativeRun>, offset: Int): List<NativeRun> {
        var at = 0
        val out = ArrayList<NativeRun>(runs.size + 1)
        for (run in runs) {
            val end = at + run.text.length
            if (offset in (at + 1) until end) {
                val k = offset - at
                if (run.origStart >= 0 && run.origEnd - run.origStart == run.text.length) {
                    out += run.copy(text = run.text.substring(0, k), origEnd = run.origStart + k)
                    out += run.copy(text = run.text.substring(k), origStart = run.origStart + k)
                } else {
                    // New text has no original glyphs to keep apart; the halves stay new text.
                    out += run.copy(text = run.text.substring(0, k), origStart = -1, origEnd = -1)
                    out += run.copy(text = run.text.substring(k), origStart = -1, origEnd = -1)
                }
            } else out += run
            at = end
        }
        return out
    }

    private fun window(
        runs: List<NativeRun>,
        start: Int,
        end: Int,
    ): Triple<List<NativeRun>, List<NativeRun>, List<NativeRun>> {
        val total = text(runs).length
        val s = start.coerceIn(0, total)
        val e = end.coerceIn(s, total)
        val parts = split(split(runs, s), e)
        val before = mutableListOf<NativeRun>()
        val inside = mutableListOf<NativeRun>()
        val after = mutableListOf<NativeRun>()
        var at = 0
        for (run in parts) {
            val runEnd = at + run.text.length
            when {
                runEnd <= s && !(run.text.isEmpty() && at == s && s == e) -> before += run
                at >= e && run.text.isNotEmpty() -> after += run
                else -> inside += run
            }
            at = runEnd
        }
        return Triple(before, inside, after)
    }

    /** Replaces [start, end) of the current text. New text takes [style] over the style at [start]. */
    fun replace(
        runs: List<NativeRun>,
        start: Int,
        end: Int,
        text: String,
        style: NativeStyle? = null,
    ): List<NativeRun> {
        val (before, inside, after) = window(runs, start, end)
        val base = inside.firstOrNull() ?: before.lastOrNull() ?: after.firstOrNull() ?: NativeRun("")
        val added =
            if (text.isEmpty()) emptyList()
            else
                listOf(
                    NativeRun(
                        text,
                        -1,
                        -1,
                        style?.font ?: base.font,
                        if (style?.font != null) style.fontSha256 else base.fontSha256,
                        style?.size ?: style?.sizeFactor?.let { f -> base.size?.times(f) } ?: base.size,
                        style?.color ?: base.color,
                    )
                )
        return normalize(before + added + after)
    }

    /** Applies [style] to [start, end). Kept glyphs stay kept unless the font changes. */
    fun restyle(
        runs: List<NativeRun>,
        start: Int,
        end: Int,
        style: NativeStyle,
        originalSize: (NativeRun) -> Float? = { null },
    ): List<NativeRun> {
        if (end <= start) return runs
        val (before, inside, after) = window(runs, start, end)
        val changed =
            inside.map { run ->
                val size =
                    style.size
                        ?: style.sizeFactor?.let { f -> (run.size ?: originalSize(run))?.times(f) }
                        ?: run.size
                if (style.font != null && style.font != NativeRun.ORIGINAL)
                    run.copy(
                        font = style.font,
                        fontSha256 = style.fontSha256,
                        origStart = -1,
                        origEnd = -1,
                        size = size,
                        color = style.color ?: run.color,
                    )
                else if (style.font == NativeRun.ORIGINAL && run.font != NativeRun.ORIGINAL)
                    // Back to the original font: only kept glyphs can return exactly; new text is re-encoded.
                    run.copy(font = NativeRun.ORIGINAL, fontSha256 = "", size = size, color = style.color ?: run.color)
                else run.copy(size = size, color = style.color ?: run.color)
            }
        return normalize(before + changed + after)
    }

    /** Merges neighbours with one style and contiguous original glyphs; drops empty runs. */
    fun normalize(runs: List<NativeRun>): List<NativeRun> {
        val out = mutableListOf<NativeRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.sameStyle(run)) {
                val bothKept =
                    last.origStart >= 0 && run.origStart >= 0 && last.origEnd == run.origStart
                val bothNew = last.origStart < 0 && run.origStart < 0
                if (bothKept) {
                    out[out.lastIndex] = last.copy(text = last.text + run.text, origEnd = run.origEnd)
                    continue
                }
                if (bothNew) {
                    out[out.lastIndex] = last.copy(text = last.text + run.text)
                    continue
                }
            }
            out += run
        }
        return out
    }

    fun json(runs: List<NativeRun>) = JSONArray(runs.map { it.json() })

    fun decode(a: JSONArray) = List(a.length()) { NativeRun.decode(a.getJSONObject(it)) }
}

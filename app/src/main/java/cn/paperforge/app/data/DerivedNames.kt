package cn.paperforge.app.data

import android.content.Context
import cn.paperforge.app.R
import java.io.File

/**
 * Names of documents made from another one (annotated, text-edited, watermark-removed copies).
 *
 * The suffix comes from string resources, so it follows the language the app runs in (the system language, or the
 * per-app language when one is chosen). A copy of a copy does not stack suffixes: every known suffix, in any
 * language, and a trailing " (n)" are taken off the source name first. Names already used in the library or on disk
 * get " (2)", " (3)", …; an existing document or file is never renamed or overwritten.
 */
object DerivedNames {
    enum class Kind(val resource: Int, val chinese: String, val english: String) {
        ANNOTATED(R.string.suffix_annotated, "已批注", "Annotated"),
        EDITED(R.string.suffix_edited, "已编辑", "Edited"),
        WATERMARK_REMOVED(R.string.suffix_watermark_removed, "已去水印", "No Watermark"),
    }

    private val counter = Regex("""\s*\((\d+)\)$""")

    private val separators = listOf(" · ", " - ", "-", " ", "_", "·")

    /** [stem] without one known suffix at its end, or null when it has none. */
    private fun dropSuffix(stem: String): String? {
        for (kind in Kind.entries) for (suffix in listOf(kind.chinese, kind.english)) {
            if (stem.equals(suffix, ignoreCase = true)) return ""
            for (separator in separators)
                if (stem.endsWith(separator + suffix, ignoreCase = true)) return stem.dropLast(separator.length + suffix.length).trim()
        }
        return null
    }

    /**
     * The source name without its extension and known suffixes (any language, repeated). A " (n)" is taken off only
     * where it follows such a suffix (a counter this app added), so "Exam (1)" and "Exam (2)" stay distinct.
     */
    fun base(name: String?): String {
        var stem = OutputNames.sourceStem(name?.trim().orEmpty()).trim()
        while (true) {
            stem = dropSuffix(stem) ?: dropSuffix(stem.replace(counter, "").trim()) ?: break
        }
        return stem
    }

    /** "<source>-已去水印" / "<source> No Watermark", in the app's language. */
    fun title(context: Context, source: String?, kind: Kind, fallback: String = "文档"): String {
        val stem = base(source).ifEmpty { fallback }
        val suffix = context.getString(kind.resource)
        // Chinese suffixes join with a hyphen (as the app has always written them), Latin ones with a space.
        val joiner = if (suffix.firstOrNull()?.let { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } == true) "-" else " "
        return stem + joiner + suffix
    }

    /** [title] made unique among the library's listed documents: " (2)", " (3)", … */
    suspend fun unique(repo: Repository, title: String): String {
        val taken = repo.dao.allDocuments().filter { it.listed }.map { it.title.trim().lowercase() }.toSet()
        if (title.trim().lowercase() !in taken) return title
        var n = 2
        while ("$title ($n)".lowercase() in taken) n++
        return "$title ($n)"
    }

    /** [file] if free, else the same name with " (2)", " (3)", … before the extension. */
    fun freeFile(file: File): File {
        if (!file.exists()) return file
        val stem = file.nameWithoutExtension
        val extension = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (File(file.parentFile, "$stem ($n)$extension").exists()) n++
        return File(file.parentFile, "$stem ($n)$extension")
    }
}

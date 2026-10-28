package cn.paperforge.app.data

import cn.paperforge.app.model.safeFileName
import cn.paperforge.app.pdf.TableJobs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit document renames affect public filenames; original paths remain stable for history/jobs.
 */
object OutputNames {
    private const val role = "document-display-names"

    fun sourceStem(name: String): String =
        if (
            name.substringAfterLast('.', "").lowercase() in
                setOf(
                    "pdf",
                    "doc",
                    "docx",
                    "odt",
                    "rtf",
                    "xls",
                    "xlsx",
                    "ods",
                    "csv",
                    "ppt",
                    "pptx",
                    "odp",
                    "txt",
                    "md",
                    "html",
                    "htm",
                    "jpg",
                    "jpeg",
                    "png",
                    "webp",
                    "tif",
                    "tiff",
                    "ofd",
                    "djvu",
                    "djv",
                )
        )
            name.substringBeforeLast('.')
        else name

    fun create(repo: Repository, bucket: String, title: String, extension: String) =
        File(File(repo.exports, bucket).apply { mkdirs() }, withExtension(title, extension))

    private fun stored(repo: Repository, id: String): JSONObject {
        val file =
            File(repo.folder(id), "display-names.json").takeIf { it.isFile }
                ?: DocumentResources.entries(repo, id)
                    .lastOrNull { it.role == role }
                    ?.path
                    ?.let(::File)
        return file?.let { JSONObject(it.readText()) } ?: JSONObject().put("names", JSONArray())
    }

    fun remember(repo: Repository, id: String, name: String) {
        val json = stored(repo, id)
        val array = json.getJSONArray("names")
        if ((0 until array.length()).none { array.getString(it) == name }) array.put(name)
        val file = File(repo.folder(id), "display-names.json")
        TableJobs.atomic(file, json)
        DocumentResources.register(repo, id, role, file)
    }

    fun withExtension(title: String, extension: String): String {
        val suffix = ".$extension"
        val stem = if (title.endsWith(suffix, true)) title.dropLast(suffix.length) else title
        return safeFileName(stem) + if (extension.isEmpty()) "" else suffix
    }

    suspend fun display(repo: Repository, file: File): String {
        val doc =
            repo.dao.documentByPath(file.path)
                ?: repo.dao.output(file.path)?.outputDocumentId?.let { repo.dao.document(it) }
                ?: return file.name
        val names = stored(repo, doc.id).getJSONArray("names")
        // Older app versions changed only the library title; recover that public name as well.
        val legacy =
            file.name.equals("original.pdf", true) ||
                Regex(".+-(?:[0-9a-f]{8}|[0-9]{13})\\.[^.]+", RegexOption.IGNORE_CASE)
                    .matches(file.name)
        return if ((0 until names.length()).any { names.getString(it) == doc.title } || legacy)
            withExtension(doc.title, file.extension)
        else file.name
    }
}

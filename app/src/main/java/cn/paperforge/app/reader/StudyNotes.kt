package cn.paperforge.app.reader

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class StudyNote(
    val id: String,
    val documentId: String,
    val title: String,
    val page: Int,
    val quote: String,
    val note: String,
    val created: Long,
)

object StudyNotes {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "study-notes.json"))

    @Synchronized
    fun load(context: Context): List<StudyNote> {
        val storage = file(context)
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists())
            return emptyList()
        val array = JSONArray(storage.openRead().bufferedReader().use { it.readText() })
        return (0 until array.length()).map { index ->
            array.getJSONObject(index).let {
                StudyNote(
                    it.getString("id"),
                    it.getString("documentId"),
                    it.getString("title"),
                    it.getInt("page"),
                    it.getString("quote"),
                    it.optString("note"),
                    it.getLong("created"),
                )
            }
        }
    }

    @Synchronized
    fun add(
        context: Context,
        documentId: String,
        title: String,
        page: Int,
        quote: String,
        note: String,
    ) {
        require(quote.isNotBlank())
        write(
            context,
            load(context) +
                StudyNote(
                    UUID.randomUUID().toString(),
                    documentId,
                    title,
                    page,
                    quote,
                    note,
                    System.currentTimeMillis(),
                ),
        )
    }

    @Synchronized
    fun remove(context: Context, id: String) = write(context, load(context).filter { it.id != id })

    @Synchronized
    fun update(context: Context, id: String, quote: String, note: String) =
        write(
            context,
            load(context).map { if (it.id == id) it.copy(quote = quote, note = note) else it },
        )

    @Synchronized
    private fun write(context: Context, notes: List<StudyNote>) {
        val storage = file(context)
        val stream = storage.startWrite()
        try {
            stream.write(encode(notes).toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(stream)
        } catch (e: Exception) {
            storage.failWrite(stream)
            throw e
        }
    }

    fun encode(notes: List<StudyNote>) =
        JSONArray().apply {
            notes.forEach {
                put(
                    JSONObject()
                        .put("id", it.id)
                        .put("documentId", it.documentId)
                        .put("title", it.title)
                        .put("page", it.page)
                        .put("quote", it.quote)
                        .put("note", it.note)
                        .put("created", it.created)
                )
            }
        }

    @Synchronized
    fun restore(context: Context, array: JSONArray, ids: Map<String, String>) {
        val imported =
            (0 until array.length()).mapNotNull { i ->
                array.getJSONObject(i).let { entry ->
                    val id = ids[entry.getString("documentId")] ?: entry.getString("documentId")
                    StudyNote(
                        UUID.randomUUID().toString(),
                        id,
                        entry.getString("title"),
                        entry.getInt("page"),
                        entry.getString("quote"),
                        entry.optString("note"),
                        entry.getLong("created"),
                    )
                }
            }
        write(context, load(context) + imported)
    }

    fun source(note: StudyNote) =
        "${note.title} · 第 ${note.page + 1} 页 · paperforge://document/${note.documentId}?page=${note.page + 1}"

    fun export(context: Context, directory: File, format: String): File {
        val notes = load(context)
        require(notes.isNotEmpty()) { "还没有摘录" }
        require(format in setOf("md", "docx", "tsv"))
        val target = File(directory, "PaperForge-notes-${System.currentTimeMillis()}.$format")
        when (format) {
            "md" ->
                target.writeText(
                    notes.joinToString("\n\n---\n\n", "# 阅读摘录\n\n") {
                        "## ${it.title} · 第 ${it.page + 1} 页\n\n${it.quote.lines().joinToString("\n") { line -> "> $line" }}\n\n${it.note}\n\n来源：${source(it)}"
                    }
                )
            "tsv" ->
                target.writeText(
                    notes.joinToString("\n", "#separator:tab\n#html:false\n") {
                        listOf(it.quote, it.note + "\n" + source(it)).joinToString("\t") { value ->
                            value.replace('\t', ' ').replace('\n', ' ')
                        }
                    }
                )
            "docx" -> {
                fun xml(value: String) =
                    value
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                fun paragraph(value: String) =
                    "<w:p><w:r><w:t xml:space=\"preserve\">${xml(value)}</w:t></w:r></w:p>"
                val document =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                        notes.joinToString("") {
                            paragraph(it.title) +
                                it.quote.lines().joinToString("") { line -> paragraph(line) } +
                                paragraph(it.note) +
                                paragraph(source(it)) +
                                paragraph("")
                        } +
                        "<w:sectPr/></w:body></w:document>"
                ZipOutputStream(target.outputStream()).use { zip ->
                    fun entry(name: String, text: String) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    entry(
                        "[Content_Types].xml",
                        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                    )
                    entry(
                        "_rels/.rels",
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
                    )
                    entry("word/document.xml", document)
                }
            }
        }
        return target
    }
}

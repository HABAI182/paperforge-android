package cn.paperforge.app.pdf

import android.net.Uri
import cn.paperforge.app.data.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.util.UUID
import org.json.*

/** Small text-only local history. Exported PDFs remain separate immutable history results. */
data class NativeEditDraft(
    val sourceSha256: String,
    val states: List<List<NativePdfEditor.Edit>> = listOf(emptyList()),
    val cursor: Int = 0,
) {
    val edits
        get() = states[cursor]

    val canUndo
        get() = cursor > 0

    val canRedo
        get() = cursor < states.lastIndex

    fun change(value: List<NativePdfEditor.Edit>) =
        if (value == edits) this
        else
            copy(
                states = (states.take(cursor + 1) + listOf(value)).takeLast(201),
                cursor = (cursor + 1).coerceAtMost(200),
            )

    fun undo() = if (canUndo) copy(cursor = cursor - 1) else this

    fun redo() = if (canRedo) copy(cursor = cursor + 1) else this

    fun request() =
        JSONObject()
            .put("sourceSha256", sourceSha256)
            .put("edits", JSONArray(edits.map { it.json() }))

    fun json() =
        JSONObject()
            .put("sourceSha256", sourceSha256)
            .put("cursor", cursor)
            .put("states", JSONArray(states.map { s -> JSONArray(s.map { it.json() }) }))

    companion object {
        fun decode(json: JSONObject): NativeEditDraft {
            val states = json.getJSONArray("states")
            return NativeEditDraft(
                json.getString("sourceSha256"),
                List(states.length()) { index ->
                    val list = states.getJSONArray(index)
                    List(list.length()) { NativePdfEditor.Edit.decode(list.getJSONObject(it)) }
                },
                json.getInt("cursor"),
            )
        }

        fun file(repo: Repository, doc: DocumentRow, sha: String) =
            File(repo.folder(doc.id), "native-edit-$sha.json")

        fun load(repo: Repository, doc: DocumentRow, sha: String): NativeEditDraft {
            val file = file(repo, doc, sha)
            if (file.isFile) return decode(JSONObject(file.readText()))
            val restored =
                DocumentResources.entries(repo, doc.id)
                    .asReversed()
                    .filter { it.role == "native-edit-draft" }
                    .map { JSONObject(File(it.path).readText()) }
                    .firstOrNull { it.optString("sourceSha256") == sha }
            return restored?.let(::decode) ?: NativeEditDraft(sha)
        }

        suspend fun save(repo: Repository, doc: DocumentRow, draft: NativeEditDraft) {
            val file = file(repo, doc, draft.sourceSha256)
            TableJobs.atomic(file, draft.json())
            DocumentResources.register(repo, doc.id, "native-edit-draft", file)
            draft.states
                .flatten()
                .filter { it.fontSha256.isNotEmpty() }
                .distinctBy { it.fontSha256 }
                .forEach { edit ->
                    DocumentResources.register(
                        repo,
                        doc.id,
                        "native-edit-font",
                        NativeEditFonts.resolve(repo, doc, edit.fontSha256),
                    )
                }
        }
    }
}

data class NativeFontChoice(
    val id: String,
    val label: String,
    val path: String = "",
    val sha256: String = "",
)

object NativeEditFonts {
    private fun name(file: File) =
        PDDocument().use { pdf -> file.inputStream().use { PDType0Font.load(pdf, it, true).name } }

    suspend fun resolve(repo: Repository, doc: DocumentRow, sha: String): File {
        val global = File(repo.context.filesDir, "native-fonts/$sha.ttf")
        if (global.isFile) return global
        return DocumentResources.entries(repo, doc.id)
            .asSequence()
            .filter { it.role == "native-edit-font" }
            .map { File(it.path) }
            .toList()
            .firstOrNull { PdfAssembly.sha256(it) == sha } ?: error("找不到所选字体，请重新导入")
    }

    suspend fun list(repo: Repository, doc: DocumentRow): List<NativeFontChoice> {
        val folder = File(repo.context.filesDir, "native-fonts")
        val resources =
            DocumentResources.entries(repo, doc.id).filter { it.role == "native-edit-font" }
        val files =
            (folder.listFiles()?.filter { it.extension == "ttf" }.orEmpty() +
                    resources.map { File(it.path) })
                .filter { it.isFile }
                .distinctBy { it.path }
        val choices =
            mutableListOf(
                NativeFontChoice("ORIGINAL", "沿用原字体"),
                NativeFontChoice("CJK", "PaperForge 中英字体"),
            )
        for (file in files) {
            val sha = PdfAssembly.sha256(file)
            if (choices.none { it.id == sha })
                choices += NativeFontChoice(sha, name(file), file.path, sha)
        }
        return choices
    }

    suspend fun install(repo: Repository, doc: DocumentRow, uri: Uri): NativeFontChoice {
        val folder = File(repo.context.filesDir, "native-fonts").apply { mkdirs() }
        val part = File(folder, "import-${UUID.randomUUID()}.part")
        try {
            repo.context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取字体" }
                part.outputStream().use { input.copyTo(it) }
            }
            val label = name(part)
            val sha = PdfAssembly.sha256(part)
            val target = File(folder, "$sha.ttf")
            if (!target.exists()) check(part.renameTo(target)) { "无法保存字体" }
            DocumentResources.register(repo, doc.id, "native-edit-font", target)
            return NativeFontChoice(sha, label, target.path, sha)
        } finally {
            part.delete()
        }
    }
}

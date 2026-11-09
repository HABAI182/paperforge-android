package cn.paperforge.network.lan

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** No dependency on the app module; the full APK provides a private, read-only library bridge. */
internal class LanDocumentLibrary(private val context: Context) {
    private val root
        get() = Uri.parse("content://${context.packageName}.library/documents")

    val available
        get() =
            context.packageManager.resolveContentProvider("${context.packageName}.library", 0) !=
                null

    fun uri(id: String, cover: Boolean = false): Uri =
        root.buildUpon().appendPath(id).appendPath(if (cover) "cover" else "pdf").build()

    fun name(id: String): String =
        context.contentResolver.query(uri(id), null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst())
                cursor.getString(
                    cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)
                )
            else null
        } ?: throw java.io.FileNotFoundException("文档已移除")

    fun previewUri(id: String, page: Int): Uri =
        root.buildUpon().appendPath(id).appendPath("preview").appendPath(page.toString()).build()

    fun info(id: String): JSONObject =
        context.contentResolver
            .query(
                root.buildUpon().appendPath(id).appendPath("info").build(),
                null,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                if (!cursor.moveToFirst()) error("文档已移除")
                row(cursor)
            } ?: error("文档已移除")

    private fun row(cursor: android.database.Cursor): JSONObject =
        JSONObject().apply {
            for (name in listOf("id", "title", "kind", "folder", "tags")) put(
                name,
                cursor.getString(cursor.getColumnIndexOrThrow(name)),
            )
            for (name in listOf("modified", "pages", "bytes")) put(
                name,
                cursor.getLong(cursor.getColumnIndexOrThrow(name)),
            )
        }

    fun list(): JSONArray {
        val rows = JSONArray()
        if (!available) return rows
        context.contentResolver.query(root, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) rows.put(row(cursor))
        }
        return rows
    }
}

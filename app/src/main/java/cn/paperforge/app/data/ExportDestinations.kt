package cn.paperforge.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import cn.paperforge.app.model.fileStemPrefix
import cn.paperforge.app.model.safeFileName
import java.security.MessageDigest
import org.json.JSONObject

data class ExportDestination(val id: String, val name: String, val uri: Uri)

/** Only logical IDs travel with presets. SAF grants and device URIs stay on this device. */
class ExportDestinations(private val context: Context) {
    private val prefs = context.getSharedPreferences("export-destinations", 0)

    fun get(id: String): ExportDestination? =
        prefs.getString(id, null)?.let {
            val j = JSONObject(it)
            ExportDestination(id, j.getString("name"), Uri.parse(j.getString("uri")))
        }

    fun remember(uri: Uri): ExportDestination {
        context.contentResolver.takePersistableUriPermission(uri, FLAGS)
        val document =
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
        val name =
            context.contentResolver
                .query(
                    document,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "所选目录"
        val id =
            MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
        prefs
            .edit()
            .putString(id, JSONObject().put("name", name).put("uri", uri.toString()).toString())
            .apply()
        return ExportDestination(id, name, uri)
    }

    fun available(destination: ExportDestination) =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == destination.uri && it.isWritePermission && it.isReadPermission
        }

    companion object {
        const val FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun create(context: Context, tree: Uri, fileName: String, mime: String): Uri {
            val parent =
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            val children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            val names =
                context.contentResolver
                    .query(
                        children,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        buildSet<String> { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    } ?: error("无法读取保存目录，请重新选择")
            val stem = safeFileName(fileName.substringBeforeLast('.', fileName))
            val extension = fileName.substringAfterLast('.', "")
            var name = stem + if (extension.isEmpty()) "" else ".$extension"
            var counter = 2
            while (name in names) {
                name =
                    "${fileStemPrefix(stem, 180, 90)} (${counter++})" +
                        if (extension.isEmpty()) "" else ".$extension"
            }
            return DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                ?: error("无法在此目录创建文件，请重新选择")
        }
    }
}

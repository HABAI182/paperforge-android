package cn.paperforge.app.data

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Document-level originals and attachments; paths are remapped just like page dependencies. */
object DocumentResources {
    private fun manifest(repo: Repository, id: String) =
        AtomicFile(File(repo.folder(id), "document-resources.json"))

    fun entries(repo: Repository, id: String): List<PageResource> {
        val storage = manifest(repo, id)
        val items =
            if (storage.baseFile.exists() || File(storage.baseFile.path + ".bak").exists())
                decode(JSONArray(storage.openRead().bufferedReader().use { it.readText() }))
            else emptyList()
        val encrypted = File(repo.folder(id), "encrypted-original.pdf")
        return items +
            if (
                encrypted.exists() &&
                    items.none { File(it.path).canonicalFile == encrypted.canonicalFile }
            )
                listOf(PageResource("encrypted-original", "encrypted-original", encrypted.path))
            else emptyList()
    }

    fun encode(items: List<PageResource>, map: (String) -> String = { it }) =
        JSONArray().apply {
            items.forEach {
                put(JSONObject().put("id", it.id).put("role", it.role).put("path", map(it.path)))
            }
        }

    fun decode(array: JSONArray) =
        (0 until array.length()).map {
            array.getJSONObject(it).let { j ->
                PageResource(j.getString("id"), j.getString("role"), j.getString("path"))
            }
        }

    @Synchronized
    fun register(repo: Repository, id: String, role: String, file: File) {
        write(
            repo,
            id,
            entries(repo, id).filter { it.path != file.path } +
                PageResource(UUID.randomUUID().toString(), role, file.path),
        )
    }

    @Synchronized
    fun write(repo: Repository, id: String, items: List<PageResource>): File {
        val storage = manifest(repo, id)
        val stream = storage.startWrite()
        try {
            stream.write(encode(items).toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(stream)
        } catch (e: Exception) {
            storage.failWrite(stream)
            throw e
        }
        return storage.baseFile
    }
}

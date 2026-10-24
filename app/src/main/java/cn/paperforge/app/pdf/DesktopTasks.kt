package cn.paperforge.app.pdf

import android.content.Context
import android.net.Uri
import cn.paperforge.app.model.safeFileName
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Versioned file handoff; no server, account or network permission is required. */
object DesktopTasks {
    fun create(
        context: Context,
        uri: Uri,
        name: String,
        mode: String,
        output: File,
        options: JSONObject = JSONObject(),
    ) {
        val extension =
            name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(12)
        val input = "input.$extension"
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(input))
                context.contentResolver.openInputStream(uri)!!.use { source ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                val manifest =
                    JSONObject()
                        .put("format", "PaperForge-task-1")
                        .put("id", UUID.randomUUID().toString())
                        .put("mode", mode)
                        .put("input", input)
                        .put("originalName", name)
                        .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                        .put(
                            "options",
                            options.apply {
                                if (!has("ocr")) put("ocr", true)
                                if (!has("tableStrategy")) put("tableStrategy", "auto")
                            },
                        )
                zip.putNextEntry(ZipEntry("task.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    fun import(context: Context, uri: Uri, directory: File): List<File> {
        val temporary = File(directory, "result-${UUID.randomUUID()}.zip")
        val created = mutableListOf<File>()
        try {
            context.contentResolver.openInputStream(uri)!!.use { source ->
                temporary.outputStream().use { source.copyTo(it) }
            }
            java.util.zip.ZipFile(temporary).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: error("请选择电脑助手生成的结果包")
                require(manifestEntry.size in 1..1024 * 1024) { "结果清单过大" }
                val manifest =
                    JSONObject(
                        zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                    )
                require(manifest.optString("format") == "PaperForge-result-1") { "结果包版本不受支持" }
                val outputs = manifest.getJSONArray("outputs")
                require(outputs.length() in 1..1000) { "结果包中没有可导入文件" }
                repeat(outputs.length()) { index ->
                    val record = outputs.getJSONObject(index)
                    val name = record.getString("path")
                    require(
                        name == File(name).name && !name.contains('\\') && !name.contains('/')
                    ) {
                        "结果文件名无效"
                    }
                    val entry = zip.getEntry(name) ?: error("结果缺少 $name")
                    require(entry.size in 1..1024L * 1024 * 1024) { "结果文件为空或超过 1 GB" }
                    val output =
                        File(
                            directory,
                            "${UUID.randomUUID().toString().take(8)}-${safeFileName(name)}",
                        )
                    created += output
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        java.security.DigestInputStream(input, digest).use { stream ->
                            output.outputStream().use { stream.copyTo(it) }
                        }
                    }
                    require(
                        output.length() == entry.size &&
                            digest.digest().joinToString("") { "%02x".format(it) } ==
                                record.getString("sha256")
                    ) {
                        "结果校验失败：$name"
                    }
                }
            }
            return created
        } catch (error: Throwable) {
            created.forEach { it.delete() }
            throw error
        } finally {
            temporary.delete()
        }
    }
}

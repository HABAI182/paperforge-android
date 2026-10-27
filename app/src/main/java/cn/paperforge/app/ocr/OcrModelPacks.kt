package cn.paperforge.app.ocr

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class OcrModelPack(
    val id: String,
    val manifest: JSONObject,
    val directory: File,
    val fingerprint: String,
) {
    val name
        get() = manifest.optString("name", id)

    val version
        get() = manifest.optString("version", "unknown")

    val languages
        get() =
            manifest
                .optJSONArray("languages")
                ?.let { a -> List(a.length()) { a.getString(it) } }
                .orEmpty()

    val bytes
        get() = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    val languageSummary
        get() =
            languages.joinToString(" / ") { code ->
                java.util.Locale.forLanguageTag(code)
                    .getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
                    .ifBlank { code }
            }

    fun file(name: String) = File(directory, name)

    val fontFiles
        get() =
            if (manifest.getJSONObject("files").has("font.ttf")) listOf(file("font.ttf"))
            else emptyList()
}

/** Sideloaded model data stays outside the APK and outside disposable caches. */
class OcrModelPacks(val context: Context) {
    val root = File(context.filesDir, "ocr-model-packs").apply { mkdirs() }
    val installed = MutableStateFlow<List<OcrModelPack>>(emptyList())

    init {
        refresh()
    }

    fun refresh() {
        installed.value =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .mapNotNull { directory -> runCatching { read(directory) }.getOrNull() }
                .sortedBy { it.name }
    }

    fun find(id: String) = installed.value.firstOrNull { it.id == id }

    suspend fun install(uri: Uri): OcrModelPack =
        withContext(Dispatchers.IO) {
            val staging = File(root, ".incoming-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val names = mutableSetOf<String>()
                var total = 0L
                context.contentResolver.openInputStream(uri)?.use { source ->
                    ZipInputStream(source.buffered()).use { zip ->
                        while (true) {
                            ensureActive()
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory) continue
                            require(name.matches(Regex("[A-Za-z0-9_.-]{1,80}")) && name !in names) {
                                "模型包文件名或重复条目不合适"
                            }
                            names += name
                            File(staging, name).outputStream().use { dest ->
                                val buffer = ByteArray(65536)
                                var bytes = 0L
                                while (true) {
                                    ensureActive()
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    total += count
                                    require(
                                        bytes <= 160L * 1024 * 1024 && total <= 400L * 1024 * 1024
                                    ) {
                                        "模型包超出本机模型容量"
                                    }
                                    dest.write(buffer, 0, count)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                } ?: error("无法读取模型包")
                val pack = read(staging)
                val specs = pack.manifest.getJSONObject("files")
                for (name in specs.keys()) {
                    ensureActive()
                    val spec = specs.getJSONObject(name)
                    val file = File(staging, name)
                    require(
                        file.isFile &&
                            file.length() == spec.getLong("bytes") &&
                            hash(file) == spec.getString("sha256")
                    ) {
                        "模型文件校验不一致：$name"
                    }
                }
                val destination = File(root, pack.id)
                if (destination.exists()) {
                    val existing = read(destination)
                    require(existing.fingerprint == pack.fingerprint)
                    val metadata = JSONObject(existing.manifest.toString())
                    listOf("name", "languages").forEach { key ->
                        if (pack.manifest.has(key)) metadata.put(key, pack.manifest.get(key))
                    }
                    val atomic = android.util.AtomicFile(File(destination, "manifest.json"))
                    val stream = atomic.startWrite()
                    try {
                        stream.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(stream)
                    } catch (error: Throwable) {
                        atomic.failWrite(stream)
                        throw error
                    }
                    refresh()
                    return@withContext checkNotNull(find(existing.id))
                }
                withContext(NonCancellable) {
                    check(staging.renameTo(destination)) { "无法保存模型包" }
                    refresh()
                }
                checkNotNull(find(pack.id))
            } finally {
                check(staging.canonicalFile.parentFile == root.canonicalFile)
                staging.deleteRecursively()
            }
        }

    /** The caller first releases any model runtime leases. */
    fun removeFiles(pack: OcrModelPack) {
        check(pack.directory.canonicalFile.parentFile == root.canonicalFile)
        check(pack.directory.deleteRecursively()) { "模型文件未能移除" }
        refresh()
    }

    private fun read(directory: File): OcrModelPack {
        val manifestFile = File(directory, "manifest.json")
        require(manifestFile.length() in 1..262144)
        val manifest = JSONObject(manifestFile.readText())
        require(
            manifest.optInt("format") == 1 && manifest.optString("runtime") == "paddle-onnx-v1"
        ) {
            "不支持的模型包格式"
        }
        val base = manifest.getString("id")
        require(base.matches(Regex("[a-z0-9][a-z0-9-]{0,47}"))) { "模型包标识不合适" }
        val files = manifest.getJSONObject("files")
        require(setOf("det.onnx", "rec.onnx", "rec.yml", "LICENSE").all { files.has(it) }) {
            "模型包缺少检测、识别、字典或来源许可文件"
        }
        require(!files.has("font.ttf") || files.has("font-LICENSE")) { "模型包的导出字体缺少许可文件" }
        for (name in files.keys()) require(
            name.matches(Regex("[A-Za-z0-9_.-]{1,80}")) &&
                files.getJSONObject(name).getString("sha256").matches(Regex("[0-9a-f]{64}"))
        )
        val hashes =
            (listOf("det.onnx", "rec.onnx", "rec.yml") +
                    if (files.has("font.ttf")) listOf("font.ttf") else emptyList())
                .joinToString(":") { files.getJSONObject(it).getString("sha256") }
        val fingerprint =
            MessageDigest.getInstance("SHA-256").digest(hashes.toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        return OcrModelPack("$base-${fingerprint.take(8)}", manifest, directory, fingerprint)
    }

    companion object {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    digest.update(bytes, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

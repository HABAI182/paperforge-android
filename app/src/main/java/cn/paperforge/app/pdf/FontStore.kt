package cn.paperforge.app.pdf

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.tom_roush.fontbox.ttf.TTFParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fonts the user adds for PDF editing: imported from the phone or downloaded from the catalog. Both end up in the
 * editor's store (files/native-fonts/<sha256>.ttf) with a small record beside it, so the editor, saving and the font
 * manager see the same fonts.
 *
 * Downloads come from google/fonts pinned at one commit (assets/fonts/catalog.json). Several independent sources serve
 * those exact bytes (jsDelivr's CDNs, GitHub); they are tried in turn, and what arrives is accepted only when its size
 * and git blob SHA-1 match the catalog, whichever source it came from. Only OFL / Apache fonts are listed; for
 * proprietary fonts (Arial, SimSun, Microsoft YaHei, …) the catalog names open alternatives, never the fonts themselves.
 * A font whose embedding licence forbids embedding, or an OTF with CFF outlines (which the PDF writer cannot embed),
 * is refused with the reason.
 */
object FontStore {
    class Family(
        val id: String,
        val nameZh: String,
        val nameEn: String,
        val category: String,
        val coverageZh: String,
        val coverageEn: String,
        val license: String,
        val licensePath: String?,
        val replaces: List<String>,
        val files: List<FileEntry>,
    ) {
        val bytes get() = files.sumOf { it.size }
    }

    class FileEntry(val name: String, val path: String, val size: Long, val gitSha1: String)

    class Catalog(val commit: String, val sources: List<String>, val families: List<Family>)

    /** An installed font: its file, the name inside it, and where it came from. */
    class Installed(val file: File, val sha256: String, val name: String, val family: String?, val license: String?, val origin: String)

    class Refused(message: String) : Exception(message)

    private var cached: Catalog? = null

    fun catalog(context: Context): Catalog =
        cached ?: JSONObject(context.assets.open("fonts/catalog.json").bufferedReader().use { it.readText() }).let { json ->
            val families = json.getJSONArray("families")
            Catalog(
                json.getString("commit"),
                json.getJSONArray("sources").let { a -> (0 until a.length()).map { a.getString(it) } },
                (0 until families.length()).map { i ->
                    val f = families.getJSONObject(i)
                    val files = f.getJSONArray("files")
                    Family(
                        f.getString("id"), f.getString("nameZh"), f.getString("nameEn"), f.getString("category"),
                        f.getString("coverageZh"), f.getString("coverageEn"), f.getString("license"),
                        f.optString("licensePath").takeIf { it.isNotBlank() && it != "null" },
                        f.getJSONArray("replaces").let { a -> (0 until a.length()).map { a.getString(it) } },
                        (0 until files.length()).map { k ->
                            val e = files.getJSONObject(k)
                            FileEntry(e.getString("name"), e.getString("path"), e.getLong("size"), e.getString("gitSha1"))
                        },
                    )
                },
            )
        }.also { cached = it }

    fun folder(context: Context) = File(context.filesDir, "native-fonts").apply { mkdirs() }

    private fun key(name: String) = name.substringAfter('+').lowercase().filter { it.isLetterOrDigit() }

    /** Open alternatives in the catalog for a PDF font name such as "ABCDEF+ArialMT" or "SimSun". */
    fun alternatives(context: Context, pdfFontName: String): List<Family> {
        val k = key(pdfFontName)
        if (k.isEmpty()) return emptyList()
        return catalog(context).families.filter { f -> f.replaces.any { r -> k.contains(r) } }
    }

    fun installed(context: Context): List<Installed> =
        folder(context).listFiles().orEmpty().filter { it.extension == "ttf" }.sortedByDescending { it.lastModified() }.map { file ->
            val meta = File(file.parentFile, file.nameWithoutExtension + ".json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            Installed(
                file,
                file.nameWithoutExtension,
                meta?.optString("name")?.takeIf { it.isNotBlank() } ?: runCatching { TTFParser().parse(file).use { it.name } }.getOrDefault(file.nameWithoutExtension.take(8)),
                meta?.optString("family")?.takeIf { it.isNotBlank() },
                meta?.optString("license")?.takeIf { it.isNotBlank() },
                meta?.optString("origin") ?: "import",
            )
        }

    /** Installed families from the catalog (all of their files present). */
    fun installedFamilies(context: Context): Set<String> {
        val have = installed(context).mapNotNull { it.family }.groupingBy { it }.eachCount()
        return catalog(context).families.filter { (have[it.id] ?: 0) >= it.files.size }.map { it.id }.toSet()
    }

    fun delete(context: Context, sha256: String) {
        File(folder(context), "$sha256.ttf").delete()
        File(folder(context), "$sha256.json").delete()
    }

    /**
     * Checks that [file] is a font the PDF writer can embed and the font's licence allows embedding; returns the name
     * inside it. OpenType with CFF outlines and fonts marked "restricted licence embedding" are refused.
     */
    fun embeddable(file: File): String {
        val head = file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (String(head, Charsets.ISO_8859_1) == "OTTO")
            throw Refused("这是 CFF 轮廓的 OpenType 字体（.otf），PDF 嵌入暂不支持。请改用同一字体的 TrueType（.ttf）版本。")
        if (String(head, Charsets.ISO_8859_1) == "ttcf")
            throw Refused("这是字体集合（.ttc），请导入其中单个字体的 .ttf 文件。")
        val font = runCatching { TTFParser().parse(file) }.getOrElse { throw Refused("无法读取这个字体文件：" + (it.message ?: "格式不支持")) }
        font.use {
            val fsType = runCatching { it.oS2Windows?.fsType?.toInt() ?: 0 }.getOrDefault(0)
            // OS/2 fsType bit 1 (value 2): restricted licence embedding; the font must not be embedded.
            if (fsType and 0x000F == 0x0002) throw Refused("这个字体的授权禁止嵌入到文档中，不能用于编辑 PDF。可在“下载字体”中选择开源替代字体。")
            return it.name ?: file.nameWithoutExtension
        }
    }

    private fun record(target: File, name: String, family: String?, license: String?, origin: String, source: String?) {
        val meta = JSONObject().put("name", name).put("origin", origin).put("installedAt", System.currentTimeMillis())
        family?.let { meta.put("family", it) }
        license?.let { meta.put("license", it) }
        source?.let { meta.put("source", it) }
        File(target.parentFile, target.nameWithoutExtension + ".json").writeText(meta.toString())
    }

    /** Imports a TTF/OTF chosen by the user; refuses what cannot be embedded. Returns the installed font. */
    suspend fun import(context: Context, uri: Uri): Installed {
        val folder = folder(context)
        val part = File(folder, "import-${UUID.randomUUID()}.part")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取字体" }
                part.outputStream().use { input.copyTo(it) }
            }
            val name = embeddable(part)
            val sha = PdfAssembly.sha256(part)
            val target = File(folder, "$sha.ttf")
            if (!target.exists()) check(part.renameTo(target)) { "无法保存字体" }
            record(target, name, null, null, "import", null)
            return Installed(target, sha, name, null, null, "import")
        } finally {
            part.delete()
        }
    }

    fun canDownload(context: Context) = context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    /**
     * Downloads every file of [family], each from the first source that delivers the catalog's exact bytes. [progress]
     * gets (bytes done, bytes total, source host). Throws with a readable reason when no source works.
     */
    suspend fun download(context: Context, family: Family, progress: (Long, Long, String) -> Unit): List<Installed> {
        if (!canDownload(context)) throw Refused("这个版本不含联网功能，不能下载字体。可从手机导入 .ttf 字体文件。")
        val catalog = catalog(context)
        val folder = folder(context)
        val total = family.bytes
        var done = 0L
        val installed = ArrayList<Installed>()
        for (entry in family.files) {
            val failures = ArrayList<String>()
            var got: Installed? = null
            for (template in catalog.sources) {
                currentCoroutineContext().ensureActive()
                val url = template.replace("{commit}", catalog.commit).replace("{path}", entry.path)
                val host = runCatching { URL(url).host }.getOrDefault(url)
                val part = File(folder, "dl-${UUID.randomUUID()}.part")
                try {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 20000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "PaperForge")
                    }
                    try {
                        if (connection.responseCode != 200) { failures += "$host: HTTP ${connection.responseCode}"; continue }
                        // git blob SHA-1: sha1("blob <size>\0" + data), computed while the bytes arrive.
                        val digest = MessageDigest.getInstance("SHA-1").apply { update("blob ${entry.size}\u0000".toByteArray()) }
                        var received = 0L
                        connection.inputStream.use { input ->
                            part.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    received += n
                                    if (received > entry.size) break
                                    digest.update(buffer, 0, n)
                                    out.write(buffer, 0, n)
                                    progress(done + received, total, host)
                                }
                            }
                        }
                        val sha1 = digest.digest().joinToString("") { "%02x".format(it) }
                        if (received != entry.size || sha1 != entry.gitSha1) { failures += "$host: 校验不符"; continue }
                    } finally {
                        connection.disconnect()
                    }
                    val name = embeddable(part)
                    val sha = PdfAssembly.sha256(part)
                    val target = File(folder, "$sha.ttf")
                    if (!target.exists()) check(part.renameTo(target)) { "无法保存字体" }
                    record(target, name, family.id, family.license, "download", host)
                    got = Installed(target, sha, name, family.id, family.license, "download")
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Refused) {
                    throw e
                } catch (e: Exception) {
                    failures += "$host: " + (e.message ?: e.javaClass.simpleName)
                } finally {
                    part.delete()
                }
            }
            installed += got ?: throw Refused("无法下载 ${entry.name}。已尝试 ${catalog.sources.size} 个来源：" + failures.joinToString("；").take(300) + "。请检查网络后重试，或导入本地字体。")
            done += entry.size
        }
        return installed
    }

    fun describe(family: Family, english: Boolean): JSONObject =
        JSONObject().put("id", family.id).put("name", if (english) family.nameEn else family.nameZh).put("files", JSONArray(family.files.map { it.name }))
}

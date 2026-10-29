package cn.paperforge.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

/**
 * Private patch PDFs contain a rewritten stream and new fonts, with original resources left in
 * place.
 */
object NativeEditStages {
    suspend fun apply(
        context: Context,
        input: File,
        output: File,
        request: JSONObject,
        fontFiles: Map<String, File>,
        stages: JobFileStages,
        progress: suspend (Int) -> Unit,
    ): JSONObject {
        require(input.canonicalFile != output.canonicalFile) { "请保存为新副本" }
        val sourceHash = stages.sourceHash(input)
        require(sourceHash == request.getString("sourceSha256")) { "来源已改变，请重新打开编辑器" }
        val changes = NativePdfEditor.edits(request)
        require(changes.isNotEmpty() && changes.map { it.key }.distinct().size == changes.size) {
            "请选择要修改的对象"
        }
        val fontVersion =
            if (changes.any { it.font == "CJK" }) {
                val hash = MessageDigest.getInstance("SHA-256")
                context.assets.open("fonts/PaperForgeCJK-Regular.ttf").use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        hash.update(buffer, 0, n)
                    }
                }
                hash.digest().joinToString("") { "%02x".format(it) }
            } else "original-or-imported"
        val folder =
            File(output.parentFile, "native-patches-${UUID.randomUUID()}").apply { mkdirs() }
        val part = File(output.path + ".part")
        val edits = JSONArray()
        var done = 0
        try {
            val pending = mutableListOf<Pair<Int, File>>()
            for ((number, selected) in changes.groupBy { it.page }) {
                currentCoroutineContext().ensureActive()
                require(number >= 0) { "页码无效" }
                val rows = JSONArray(selected.map { it.json() })
                val signature = "native-stream-patch-v1:$sourceHash:$number:$fontVersion:$rows"
                val bundle =
                    stages.file("native-edit-page-$number", signature, "zip") { destination ->
                        val patch = File(folder, "patch.pdf")
                        val detail =
                            NativePdfEditor.rewrite(
                                context,
                                input,
                                patch,
                                JSONObject().put("sourceSha256", sourceHash).put("edits", rows),
                                fontFiles,
                                true,
                            ) {}
                        FileOutputStream(destination).use { out ->
                            ZipOutputStream(out)
                                .apply { setLevel(Deflater.NO_COMPRESSION) }
                                .use { zip ->
                                    zip.putNextEntry(ZipEntry("patch.pdf"))
                                    patch.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                    zip.putNextEntry(ZipEntry("report.json"))
                                    zip.write(detail.toString().toByteArray(Charsets.UTF_8))
                                    zip.closeEntry()
                                    zip.finish()
                                    out.fd.sync()
                                }
                        }
                        patch.delete()
                    }
                pending += number to bundle
                done += selected.size
                progress(done * 90 / changes.size)
            }
            // Generation holds one source document at a time. Final merge opens it only afterward.
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                for ((number, bundle) in pending) {
                    currentCoroutineContext().ensureActive()
                    require(number in 0 until pdf.numberOfPages) { "页码无效" }
                    ZipFile(bundle).use { zip ->
                        val patch = File(folder, "read.pdf")
                        zip.getInputStream(zip.getEntry("patch.pdf") ?: error("缺少文字流阶段")).use {
                            stream ->
                            patch.outputStream().use { stream.copyTo(it) }
                        }
                        val detail =
                            zip.getInputStream(zip.getEntry("report.json") ?: error("缺少修改记录"))
                                .bufferedReader()
                                .use { JSONObject(it.readText()) }
                        val entries = detail.getJSONArray("edits")
                        for (i in 0 until entries.length()) edits.put(entries.getJSONObject(i))
                        PDDocument.load(patch, MemoryUsageSetting.setupTempFileOnly()).use { cached
                            ->
                            val source = cached.getPage(0)
                            val target = pdf.getPage(number)
                            val resources =
                                COSDictionary(target.resources?.cosObject ?: COSDictionary())
                            val fonts =
                                COSDictionary(
                                    resources.getCOSDictionary(COSName.FONT) ?: COSDictionary()
                                )
                            val cloner = PDFCloneUtility(pdf)
                            source.resources.fontNames.forEach { name ->
                                require(!fonts.containsKey(name)) { "缓存字体与原页发生冲突" }
                                fonts.setItem(
                                    name,
                                    cloner.cloneForNewDocument(
                                        source.resources.getFont(name).cosObject
                                    ),
                                )
                            }
                            resources.setItem(COSName.FONT, fonts)
                            target.resources = PDResources(resources)
                            source.contents.use {
                                target.setContents(PDStream(pdf, it, COSName.FLATE_DECODE))
                            }
                        }
                        patch.delete()
                    }
                }
                pdf.save(part)
            }
            currentCoroutineContext().ensureActive()
            FileOutputStream(part, true).use { it.fd.sync() }
            check(part.renameTo(output)) { "无法保存修改副本" }
            progress(100)
            return JSONObject()
                .put("sourceSha256", sourceHash)
                .put("operation", "native-text-edit")
                .put("edits", edits)
                .put("outputBytes", output.length())
                .put("originalRetained", true)
                .put("checkpointReused", stages.reused)
                .put("checkpointGenerated", stages.generated)
                .put("checkpointEntries", stages.entries)
        } finally {
            part.delete()
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }
}

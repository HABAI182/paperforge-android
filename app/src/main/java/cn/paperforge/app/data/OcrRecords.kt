package cn.paperforge.app.data

import cn.paperforge.app.model.OcrLayout
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class OcrTextVersion(val label: String, val text: String)

/** Immutable compressed records live in the page resource graph, including historical versions. */
object OcrRecords {
    fun read(page: PageRow): JSONObject? {
        val id = PageResources.metadata(page).optString("ocrRecord")
        val resource =
            PageResources.entries(page).firstOrNull { it.id == id && it.role == "ocr-record" }
                ?: return null
        return GZIPInputStream(File(resource.path).inputStream()).bufferedReader().use {
            JSONObject(it.readText())
        }
    }

    fun create(page: PageRow, result: OcrLayout) =
        JSONObject()
            .put("format", 1)
            .put("created", System.currentTimeMillis())
            .put(
                "sourceGeometry",
                JSONObject()
                    .put("sourceRevision", page.revision)
                    .put("corners", page.corners)
                    .put("rotation", page.rotation)
                    .put("deskewDegrees", page.deskewDegrees)
                    .put("outputAspect", page.outputAspect),
            )
            .apply { result.rawRecognition?.let { put("raw", JSONObject(it)) } }
            .put("automatic", JSONObject(result.copy(rawRecognition = null).encode()))
            .put("edits", JSONArray())

    fun write(repo: Repository, page: PageRow, record: JSONObject): File {
        val file = File(repo.folder(page.documentId), "${UUID.randomUUID()}.pfocr.json.gz")
        val temp = File(file.path + ".part")
        try {
            FileOutputStream(temp).use { stream ->
                GZIPOutputStream(stream).use { zipped ->
                    zipped.write(record.toString().toByteArray(Charsets.UTF_8))
                    zipped.finish()
                    zipped.flush()
                    stream.fd.sync()
                }
            }
            check(temp.renameTo(file)) { "无法保存识别记录" }
            return file
        } finally {
            temp.delete()
        }
    }

    fun corrected(page: PageRow, text: String): JSONObject {
        val record =
            read(page)
                ?: page.ocrLayout?.let { create(page, OcrLayout.decode(it)) }
                ?: JSONObject()
                    .put("format", 1)
                    .put("legacyText", page.ocrText ?: "")
                    .put("edits", JSONArray())
        record
            .getJSONArray("edits")
            .put(JSONObject().put("created", System.currentTimeMillis()).put("text", text))
        return record
    }

    fun versions(page: PageRow): List<OcrTextVersion> {
        val record = read(page) ?: return emptyList()
        return buildList {
            record.optJSONObject("raw")?.let {
                add(OcrTextVersion("原始识别", OcrLayout.decode(it.toString()).text))
            }
            record.optJSONObject("automatic")?.let {
                add(OcrTextVersion("自动选定", OcrLayout.decode(it.toString()).text))
            }
            if (record.has("legacyText"))
                add(OcrTextVersion("历史文字", record.getString("legacyText")))
            record.optJSONArray("regions")?.let { regions ->
                for (i in 0 until regions.length()) {
                    val local =
                        OcrLayout.decode(
                            regions.getJSONObject(i).getJSONObject("result").toString()
                        )
                    local.rawRecognition?.let {
                        add(OcrTextVersion("选区 ${i+1} 原始识别", OcrLayout.decode(it).text))
                    }
                }
            }
            record.optJSONArray("edits")?.let { edits ->
                for (i in 0 until edits.length()) add(
                    OcrTextVersion(
                        if (edits.getJSONObject(i).optString("kind") == "region") "局部重识别 ${i+1}"
                        else "第 ${i+1} 次校正",
                        edits.getJSONObject(i).getString("text"),
                    )
                )
            }
        }
    }
}

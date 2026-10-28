package cn.paperforge.app.pdf

import android.content.Context
import android.content.pm.PackageManager
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object PdfArchive {
    const val ENGINE = "verapdf-1.30.3-android1"
    val targets = listOf("1b", "2b", "2u", "3b", "3u", "4", "4f", "4e", "1a", "2a", "3a")

    @Suppress("DEPRECATION")
    fun available(context: Context): Boolean =
        runCatching {
                context.packageManager
                    .getApplicationInfo("cn.paperforge.formats", PackageManager.GET_META_DATA)
                    .metaData
                    ?.getString("paperforge.pdfa.validator") == ENGINE
            }
            .getOrDefault(false)

    suspend fun verify(
        context: Context,
        input: File,
        report: File,
        target: String,
        id: String,
    ): JSONObject {
        require(target in targets) { "请选择 PDF/A 目标等级" }
        require(available(context)) { "请安装包含 PDF/A 校验器的新版手机格式引擎" }
        report.parentFile?.mkdirs()
        val part = File(report.path + ".part")
        try {
            withContext(Dispatchers.IO) {
                part.outputStream().use { stream ->
                    FormatBridge.convert(
                        context,
                        id,
                        input,
                        stream,
                        JSONObject()
                            .put("operation", "validate-pdfa")
                            .put("target", target)
                            .toString(),
                    ) { _, _ ->
                    }
                }
            }
            val result = withContext(Dispatchers.IO) { JSONObject(part.readText()) }
            require(
                result.getString("kind") == "paperforge-pdfa-report" &&
                    result.getString("engine") == ENGINE &&
                    result.getString("target") == target
            ) {
                "归档校验响应不匹配"
            }
            require(result.getString("sourceSha256") == PdfAssembly.sha256(input)) { "校验期间原文件发生变化" }
            require(result.getString("jobEndStatus") == "NORMAL") { "校验未完整完成" }
            check(part.renameTo(report)) { "无法保存校验报告" }
            return result
        } finally {
            part.delete()
        }
    }

    /** Explicit appearance-only copy; no claim that source text, forms or signatures survive. */
    suspend fun compatible(
        context: Context,
        input: File,
        output: File,
        title: String,
        stages: JobFileStages? = null,
        progress: suspend (Int) -> Unit = {},
    ) {
        val folder = File(output.parentFile, "archive-work").apply { mkdirs() }
        val raster = File(folder, "raster.pdf")
        try {
            PdfEngine.flatten(input, raster, 90, stages) { progress(it * 65 / 100) }
            val now =
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    set(Calendar.MILLISECOND, 0)
                }
            val time = java.time.Instant.ofEpochMilli(now.timeInMillis).toString()
            val producer = "PaperForge PDF/A appearance copy"
            fun xml(value: String) =
                value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val xmp =
                """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" pdfaid:part="2" pdfaid:conformance="B"/>
<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" dc:format="application/pdf"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">${xml(title)}</rdf:li></rdf:Alt></dc:title></rdf:Description>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="PaperForge" xmp:CreateDate="$time" xmp:ModifyDate="$time" xmp:MetadataDate="$time"/>
<rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/" pdf:Producer="$producer"/>
</rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
            PdfTools.write(raster, output) { pdf ->
                pdf.version = 1.7f
                pdf.documentInformation.apply {
                    this.title = title
                    creator = "PaperForge"
                    this.producer = producer
                    creationDate = now
                    modificationDate = now
                }
                pdf.documentCatalog.metadata =
                    PDMetadata(pdf).apply { importXMPMetadata(xmp.toByteArray(Charsets.UTF_8)) }
                context.assets.open("color/sRGB-LittleCMS.icc").use { color ->
                    val intent =
                        PDOutputIntent(pdf, color).apply {
                            info = "sRGB"
                            outputCondition = "sRGB IEC61966-2.1"
                            outputConditionIdentifier = "sRGB"
                            registryName = "http://www.color.org"
                        }
                    pdf.documentCatalog.addOutputIntent(intent)
                }
            }
            progress(75)
        } finally {
            raster.delete()
            folder.delete()
        }
    }
}

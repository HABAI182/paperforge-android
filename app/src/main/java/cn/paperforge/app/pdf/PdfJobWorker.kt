package cn.paperforge.app.pdf

import android.content.Context
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.model.safeFileName
import kotlinx.coroutines.*
import java.io.File
import org.json.JSONObject

class PdfJobWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result=withContext(Dispatchers.IO) {
        var output: File?=null
        try {
            val repo=(applicationContext as PaperForgeApp).repository
            val mode=inputData.getString("mode") ?: error("缺少工具")
            val doc=repo.dao.document(inputData.getString("document") ?: "")
            val source=doc?.pdfPath?.let { File(it) }
            val p=JSONObject(inputData.getString("options") ?: "{}")
            val extension=when(mode) { "images-jpg","images-png","extract-images","split-each" -> "zip"; "txt","md","html","docx" -> mode; else -> "pdf" }
            val file=File(repo.exports,"${safeFileName(doc?.title ?: "合并文档")}-$mode-${id.toString().take(8)}.$extension"); output=file
            val input=source ?: file
            val range=p.optString("range")
            when(mode) {
                "merge" -> PdfEngine.merge(inputData.getStringArray("sources")!!.map { File(it) },file)
                "extract" -> PdfEngine.extract(input,range,file,p.optInt("rotation"))
                "organize" -> { val order=p.getJSONArray("order"); val rotations=p.getJSONObject("rotations"); PdfEngine.organize(input,file,List(order.length()) { order.getInt(it) },rotations.keys().asSequence().associate { it.toInt() to rotations.getInt(it) }) }
                "crop" -> PdfTools.crop(input,file,p.optDouble("percent",5.0).toFloat(),range)
                "blank" -> PdfTools.insertBlank(input,file,p.optInt("after",1))
                "nup2","nup4" -> PdfTools.nup(input,file,if(mode=="nup2") 2 else 4)
                "watermark" -> PdfTools.watermark(applicationContext,input,file,p.optString("text"),range,p.optDouble("opacity",.18).toFloat())
                "number" -> PdfTools.watermark(applicationContext,input,file,"",range,numbered=true)
                "metadata" -> PdfTools.metadata(input,file,p.optString("title",doc?.title ?: ""),p.optString("author"))
                "optimize" -> PdfTools.optimize(input,file)
                "images-jpg","images-png" -> PdfTools.images(input,file,range,if(mode.endsWith("png")) "png" else "jpg",p.optInt("edge",2200)) { setProgress(workDataOf("progress" to it)) }
                "extract-images" -> PdfTools.extractImages(input,file)
                "searchable" -> PdfSearchable.convert(applicationContext,input,file) { setProgress(workDataOf("progress" to it)) }
                "txt","md","html","docx" -> PdfTools.exportText(input,file,mode)
                "compress" -> PdfEngine.compress(input,file,p.optInt("quality",75)) { setProgress(workDataOf("progress" to it)) }
                "flatten" -> PdfEngine.flatten(input,file,p.optInt("quality",75)) { setProgress(workDataOf("progress" to it)) }
                "split-each" -> PdfTools.splitEach(input,file)
                "marks" -> {
                    val data=File(p.getString("marksFile"))
                    try { PdfMarks.save(applicationContext,input,file,PdfMark.decode(org.json.JSONArray(data.readText()))) { setProgress(workDataOf("progress" to it)) } }
                    finally { data.delete() }
                }
                else -> error("未知工具")
            }
            if(mode in listOf("compress","optimize")) setProgress(workDataOf("progress" to 100))
            if(extension=="pdf") repo.registerPdf(file,(doc?.title ?: "合并文档")+" · "+mode)
            Result.success(workDataOf("path" to file.path,"inputBytes" to (source?.length() ?: 0),"outputBytes" to file.length(),"mode" to mode))
        } catch(e: CancellationException) { output?.delete(); throw e }
        catch(e: Exception) { output?.delete(); Result.failure(workDataOf("error" to (e.message ?: "处理失败"))) }
    }
}

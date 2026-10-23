package cn.paperforge.app.pdf

import android.content.Context
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.model.safeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File

class ExportWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result=withContext(Dispatchers.IO) {
        try {
            val repo=(applicationContext as PaperForgeApp).repository
            var doc=repo.dao.document(inputData.getString("document") ?: error("缺少文档")) ?: error("草稿不存在")
            inputData.getString("title")?.let { title -> repo.rename(doc,title); doc=doc.copy(title=safeFileName(title)) }
            val file=File(repo.exports,"${safeFileName(doc.title)}-${id.toString().take(8)}.pdf")
            PdfEngine.scan(repo.dao.pages(doc.id),file,inputData.getString("paper") ?: "Auto",inputData.getInt("quality",90),applicationContext,inputData.getBoolean("searchable",false),inputData.getString("resolution") ?: "STANDARD") { setProgress(workDataOf("progress" to it)) }
            repo.registerPdf(file,doc.title)
            Result.success(workDataOf("path" to file.path))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { Result.failure(workDataOf("error" to (e.message ?: "PDF 导出失败"))) }
    }
}

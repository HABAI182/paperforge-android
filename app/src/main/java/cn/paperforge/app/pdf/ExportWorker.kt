package cn.paperforge.app.pdf

import android.content.Context
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.model.safeFileName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                val repo = (applicationContext as PaperForgeApp).repository
                var doc =
                    repo.dao.document(inputData.getString("document") ?: error("缺少文档"))
                        ?: error("草稿不存在")
                inputData.getString("title")?.let { title ->
                    repo.rename(doc, title)
                    doc = doc.copy(title = safeFileName(title))
                }
                val file =
                    File(repo.exports, "${safeFileName(doc.title)}-${id.toString().take(8)}.pdf")
                if (inputData.getBoolean("searchable", false)) {
                    val engine = (applicationContext as PaperForgeApp).ocrEngine
                    val pages = repo.dao.pages(doc.id)
                    pages.forEachIndexed { index, page ->
                        val cached =
                            page.ocrLayout?.let {
                                runCatching { cn.paperforge.app.model.OcrLayout.decode(it) }
                                    .getOrNull()
                            }
                        if (
                            cached == null ||
                                cached.sourceRevision != page.revision ||
                                (cached.engineId != engine.id && cached.lines.none { it.corrected })
                        )
                            repo.cacheOcr(page, engine.layout(page))
                        setProgress(workDataOf("progress" to (index + 1) * 30 / pages.size))
                    }
                }
                PdfEngine.scan(
                    repo.dao.pages(doc.id),
                    file,
                    inputData.getString("paper") ?: "Auto",
                    inputData.getInt("quality", 90),
                    applicationContext,
                    inputData.getBoolean("searchable", false),
                    inputData.getString("resolution") ?: "STANDARD",
                ) {
                    setProgress(workDataOf("progress" to it))
                }
                repo.registerPdf(file, doc.title)
                Result.success(workDataOf("path" to file.path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(workDataOf("error" to (e.message ?: "PDF 导出失败")))
            }
        }
}

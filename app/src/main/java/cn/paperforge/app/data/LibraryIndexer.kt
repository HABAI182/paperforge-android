package cn.paperforge.app.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.model.CjkSearch
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.text.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LibraryIndexer {
    private val serial = Mutex()

    fun enqueue(context: Context, id: String) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "library-index:$id",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibraryIndexWorker>()
                    .setInputData(workDataOf("document" to id))
                    .addTag("library-index")
                    .build(),
            )
    }

    suspend fun index(repo: Repository, doc: DocumentRow) =
        serial.withLock {
            withContext(Dispatchers.IO) {
                val file = doc.pdfPath?.let(::File) ?: return@withContext
                if (doc.kind != "pdf" || !file.exists()) return@withContext
                fun fingerprint() = "${file.path}:${file.length()}:${file.lastModified()}"
                val fingerprint = fingerprint()
                if (repo.dao.pdfIndex(doc.id)?.fingerprint == fingerprint) return@withContext
                val job = currentCoroutineContext()
                val rows = mutableListOf<PdfLibraryText>()
                var count = 0
                var annotations = 0
                PDDocument.load(file).use { pdf ->
                    count = pdf.numberOfPages
                    val text = StringBuilder()
                    val stripper =
                        object : PDFTextStripper() {
                            override fun writeString(
                                value: String,
                                positions: MutableList<TextPosition>,
                            ) {
                                text.append(value)
                            }

                            override fun writeLineSeparator() {
                                text.append('\n')
                            }

                            override fun endPage(page: PDPage) {
                                job.ensureActive()
                                val content = text.toString().trim()
                                text.clear()
                                rows.add(
                                    PdfLibraryText(
                                        doc.id,
                                        currentPageNo - 1,
                                        content,
                                        CjkSearch.tokens(content),
                                    )
                                )
                                annotations +=
                                    page.annotations.count {
                                        it !is PDAnnotationLink && it !is PDAnnotationWidget
                                    }
                                super.endPage(page)
                            }
                        }
                    stripper.sortByPosition = true
                    stripper.getText(pdf)
                }
                check(fingerprint == fingerprint()) { "PDF 已变化，稍后重建索引" }
                repo.database.withTransaction {
                    if (repo.dao.document(doc.id)?.pdfPath != doc.pdfPath) return@withTransaction
                    repo.dao.deletePdfTexts(doc.id)
                    repo.dao.putPdfTexts(rows)
                    repo.dao.putPdfIndex(
                        PdfIndexRow(
                            doc.id,
                            fingerprint,
                            count,
                            rows.count { it.text.isNotBlank() },
                            annotations,
                        )
                    )
                }
            }
        }
}

class LibraryIndexWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PaperForgeApp).repository
        val doc =
            repo.dao.document(inputData.getString("document") ?: return Result.failure())
                ?: return Result.success()
        return try {
            LibraryIndexer.index(repo, doc)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "索引未完成")))
        }
    }
}

package cn.paperforge.app.pdf

import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.Repository
import java.io.File

/**
 * Library registration of a finished export.
 *
 * v200 feedback 1: the PDF existed in the private exports folder but was registered with
 * `listed = false`, and every library query (`observeDocuments`, `search`, trash) filters
 * `listed = 1`. The file was therefore invisible in "最近" and "全部" and the only way to reach it
 * was the system file picker. The export is now a first-class library document under its export
 * name, so a stored file and a library entry always agree.
 */
object ExportRegistration {
    suspend fun pdf(repository: Repository, file: File, title: String): DocumentRow =
        repository.registerPdf(file, title, listed = true)
}

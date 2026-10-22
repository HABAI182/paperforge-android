package cn.paperforge.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.safeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class Repository(val context: Context,val database: PaperDatabase) {
    val dao=database.dao()
    val documents=dao.observeDocuments()
    private val root=File(context.filesDir,"documents").apply { mkdirs() }
    val exports=File(context.filesDir,"exports").apply { mkdirs() }
    suspend fun newScan(): DocumentRow = DocumentRow(UUID.randomUUID().toString(),"扫描 ${java.text.SimpleDateFormat("MM-dd HH.mm",java.util.Locale.CHINA).format(java.util.Date())}").also { dao.putDocument(it) }
    fun folder(id: String)=File(root,id).apply { mkdirs() }
    fun captureFile(id: String)=File(folder(id),"${UUID.randomUUID()}.jpg")
    suspend fun addCapture(id: String,file: File): PageRow=withContext(Dispatchers.IO) {
        val bitmap=ScanProcessor.decode(file,1000)
        val quad=try { ScanProcessor.detect(bitmap) } finally { bitmap.recycle() }
        val page=PageRow(UUID.randomUUID().toString(),id,dao.pages(id).size,file.absolutePath,corners=(quad?:cn.paperforge.app.model.Quad.Full).encode())
        database.withTransaction { dao.putPage(page); dao.touch(id,System.currentTimeMillis()) }; page
    }
    suspend fun importImages(id: String,uris: List<Uri>)=withContext(Dispatchers.IO) {
        uris.forEach { uri ->
            val file=captureFile(id)
            try { copyUri(uri,file); addCapture(id,file) } catch(e: Exception) { file.delete(); throw e }
        }
    }
    suspend fun importPdf(uri: Uri): DocumentRow=withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString(); val file=File(folder(id),"source.pdf")
        try {
            copyUri(uri,file)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { require(it.numberOfPages>0) { "PDF 没有页面" } }
            DocumentRow(id,displayName(uri).removeSuffix(".pdf"),kind="pdf",pdfPath=file.path).also { dao.putDocument(it) }
        } catch(e: Exception) { file.delete(); throw e }
    }
    private fun copyUri(uri: Uri,target: File) {
        val temp=File(target.path+".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output); output.fd.sync() } } ?: error("文件提供程序无法打开此文件")
            check(temp.renameTo(target)) { "无法保存本地副本" }
        } finally { temp.delete() }
    }
    fun displayName(uri: Uri): String=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "导入文档"
    suspend fun savePage(page: PageRow)=database.withTransaction {
        val current=dao.pages(page.documentId).firstOrNull { it.id==page.id } ?: error("页面不存在")
        dao.updatePage(page.copy(revision=current.revision+1,ocrText=null)); dao.touch(page.documentId,System.currentTimeMillis())
    }
    suspend fun reorder(id: String,order: List<PageRow>)=database.withTransaction { order.forEachIndexed { index,p -> dao.updatePage(p.copy(position=index)) }; dao.touch(id,System.currentTimeMillis()) }
    suspend fun deletePage(page: PageRow) { dao.deletePage(page.id); File(page.original).delete(); reorder(page.documentId,dao.pages(page.documentId)) }
    suspend fun deleteDocument(doc: DocumentRow) {
        dao.deleteDocument(doc.id)
        // Only our UUID-named private directory is removed; external documents are never deleted.
        val directory=File(root,doc.id)
        require(directory.canonicalFile.parentFile==root.canonicalFile)
        directory.deleteRecursively()
        doc.pdfPath?.let { path ->
            val file=File(path)
            if(file.canonicalFile.parentFile==exports.canonicalFile && dao.pdfReferences(path)==0) file.delete()
        }
    }
    suspend fun rename(doc: DocumentRow,title: String) { dao.rename(doc.id,safeFileName(title),System.currentTimeMillis()) }
    suspend fun saveOutput(file: File,uri: Uri)=withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri,"wt")?.use { output -> file.inputStream().use { it.copyTo(output) }; output.flush() } ?: error("无法写入所选位置")
    }
    suspend fun registerPdf(file: File,title: String): DocumentRow = database.withTransaction {
        dao.documentByPath(file.path) ?: DocumentRow(UUID.randomUUID().toString(),title,kind="pdf",pdfPath=file.path).also { dao.putDocument(it) }
    }
}

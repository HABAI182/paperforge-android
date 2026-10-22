package cn.paperforge.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.safeFileName
import cn.paperforge.app.ocr.BundledOcrEngine
import cn.paperforge.app.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application): AndroidViewModel(app) {
    val repo=(app as PaperForgeApp).repository
    val documents=repo.documents.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val selected=MutableStateFlow<DocumentRow?>(null)
    val pages=selected.flatMapLatest { it?.let { d -> repo.dao.observePages(d.id) } ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val screen=MutableStateFlow("home")
    val busy=MutableStateFlow(false)
    val progress=MutableStateFlow("")
    val output=MutableStateFlow<File?>(null)
    val ocr=MutableStateFlow<String?>(null)
    val messages=Channel<String>(Channel.BUFFERED)
    private val undo=ArrayDeque<PageRow>(); private val redo=ArrayDeque<PageRow>()
    private var task: Job?=null
    private var exportId: UUID?=null
    init {
        val prefs=app.getSharedPreferences("session",0)
        val pending=prefs.getString("export",null)
        if(pending!=null) runCatching { UUID.fromString(pending) }.onSuccess { watchExport(it) }
    }
    private fun perform(label: String,action: suspend ()->Unit) {
        if(busy.value) return
        task=viewModelScope.launch {
            busy.value=true; progress.value=label
            try { withContext(Dispatchers.IO) { action() } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { messages.send(e.message ?: "操作未完成") }
            finally { busy.value=false; progress.value="" }
        }
    }
    fun newScan(camera: Boolean=true)=perform("正在建立草稿") {
        val doc=repo.newScan(); selected.value=doc; screen.value=if(camera) "capture" else "editor"
    }
    fun open(doc: DocumentRow) { if(busy.value) return; selected.value=doc; screen.value=if(doc.kind=="pdf") "reader" else "editor"; undo.clear(); redo.clear() }
    fun importImages(uris: List<Uri>) { if(uris.isEmpty()) return; perform("正在导入图片") {
        val doc=selected.value?.takeIf { it.kind=="scan" && screen.value in listOf("editor","capture") } ?: repo.newScan()
        selected.value=doc; repo.importImages(doc.id,uris); screen.value="editor"
    } }
    fun importPdf(uri: Uri)=perform("正在导入 PDF") { selected.value=repo.importPdf(uri); screen.value="reader" }
    fun captured(file: File)=perform("正在检测纸张") { repo.addCapture(selected.value!!.id,file) }
    fun save(page: PageRow)=perform("正在更新页面") {
        val previous=repo.dao.pages(page.documentId).first { it.id==page.id }; undo.addLast(previous); redo.clear(); repo.savePage(page)
    }
    fun undo()=perform("撤销") { if(undo.isNotEmpty()) { val old=undo.removeLast(); repo.dao.pages(old.documentId).find { it.id==old.id }?.let { redo.addLast(it); repo.savePage(old) } } }
    fun redo()=perform("重做") { if(redo.isNotEmpty()) { val next=redo.removeLast(); repo.dao.pages(next.documentId).find { it.id==next.id }?.let { undo.addLast(it); repo.savePage(next) } } }
    fun move(page: PageRow,delta: Int)=perform("正在调整顺序") {
        val all=repo.dao.pages(page.documentId).toMutableList(); val from=all.indexOfFirst { it.id==page.id }; val to=(from+delta).coerceIn(0,all.lastIndex)
        all.add(to,all.removeAt(from)); repo.reorder(page.documentId,all)
    }
    fun deletePage(page: PageRow)=perform("正在删除页面") { repo.deletePage(page); undo.clear(); redo.clear() }
    fun delete(doc: DocumentRow)=perform("正在删除本地文档") { repo.deleteDocument(doc); if(selected.value?.id==doc.id) { selected.value=null; screen.value="home" } }
    fun rename(doc: DocumentRow,title: String)=perform("正在重命名") { repo.rename(doc,title); selected.value=doc.copy(title=safeFileName(title)) }
    fun export(paper: String,quality: Int,title: String?=null) {
        val doc=selected.value ?: return
        if(busy.value) return
        val request=OneTimeWorkRequestBuilder<ExportWorker>().setInputData(workDataOf("document" to doc.id,"paper" to paper,"quality" to quality,"title" to (title ?: doc.title))).build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork("export-${doc.id}",ExistingWorkPolicy.KEEP,request)
        getApplication<Application>().getSharedPreferences("session",0).edit().putString("export",request.id.toString()).apply()
        watchExport(request.id)
    }
    private fun watchExport(id: UUID) {
        exportId=id; busy.value=true
        viewModelScope.launch {
            WorkManager.getInstance(getApplication()).getWorkInfoByIdFlow(id).filterNotNull().takeWhile { info ->
                progress.value="导出 PDF · ${info.progress.getInt("progress",0)}%"
                if(info.state.isFinished) {
                    busy.value=false; progress.value=""; exportId=null
                    getApplication<Application>().getSharedPreferences("session",0).edit().remove("export").apply()
                    if(info.state==WorkInfo.State.SUCCEEDED) output.value=File(info.outputData.getString("path")!!)
                    else messages.send(info.outputData.getString("error") ?: "导出已取消")
                }
                !info.state.isFinished
            }.collect()
        }
    }
    fun cancel() { exportId?.let { WorkManager.getInstance(getApplication()).cancelWorkById(it) }; task?.cancel() }
    fun saveOutput(uri: Uri) { val file=output.value ?: return; perform("正在保存文件") { repo.saveOutput(file,uri); messages.send("文件已保存") } }
    fun recognize(page: PageRow,chinese: Boolean)=perform("正在离线识别文字") {
        val result=BundledOcrEngine().recognize(page,chinese)
        repo.dao.updatePage(page.copy(ocrText=result)); ocr.value=result
    }
    fun merge(uris: List<Uri>)=perform("正在合并 PDF") {
        val imported=uris.map { repo.importPdf(it) }
        val file=File(repo.exports,"合并-${System.currentTimeMillis()}.pdf")
        PdfEngine.merge(imported.map { File(it.pdfPath!!) },file)
        selected.value=repo.registerPdf(file,"合并文档"); output.value=file; screen.value="reader"
    }
    fun extract(range: String,rotate: Int)=perform("正在处理页面") {
        val doc=selected.value ?: return@perform; val file=File(repo.exports,"${safeFileName(doc.title)}-${System.currentTimeMillis()}.pdf")
        PdfEngine.extract(File(doc.pdfPath!!),range,file,rotate)
        selected.value=repo.registerPdf(file,doc.title+if(rotate==0) " · 抽页" else " · 旋转"); output.value=file
    }
    fun compress(quality: Int)=perform("正在转换为压缩图像 PDF") {
        val doc=selected.value ?: return@perform; val source=File(doc.pdfPath!!); val file=File(repo.exports,"压缩-${System.currentTimeMillis()}.pdf")
        PdfEngine.compress(source,file,quality) { progress.value="压缩 · $it%" }
        val change=100f*(source.length()-file.length())/source.length().coerceAtLeast(1)
        messages.send("${source.length()/1024} KB → ${file.length()/1024} KB；"+if(change>0) "减少 %.1f%%".format(change) else "本次未减小体积")
        selected.value=repo.registerPdf(file,doc.title+" · 图像压缩"); output.value=file
    }
    fun extractText()=perform("正在提取 PDF 文字") { ocr.value=PdfEngine.text(File(selected.value!!.pdfPath!!)).ifBlank { "这份 PDF 没有可提取文字。扫描图请使用离线 OCR。" } }
    fun previewOutput(file: File)=perform("正在打开 PDF") {
        val doc=repo.registerPdf(file,file.nameWithoutExtension)
        selected.value=doc; output.value=null; screen.value="reader"
    }
}

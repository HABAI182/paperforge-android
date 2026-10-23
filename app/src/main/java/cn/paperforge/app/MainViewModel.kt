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
    private val ocrEngine=(app as PaperForgeApp).ocrEngine
    val query=MutableStateFlow("")
    val documents=query.flatMapLatest { if(it.isBlank()) repo.documents else repo.dao.search(it) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val trash=repo.dao.observeTrash().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val selected=MutableStateFlow<DocumentRow?>(null)
    val pages=selected.flatMapLatest { it?.let { d -> repo.dao.observePages(d.id) } ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val screen=MutableStateFlow("home")
    val busy=MutableStateFlow(false)
    val progress=MutableStateFlow("")
    val output=MutableStateFlow<File?>(null)
    val ocr=MutableStateFlow<String?>(null)
    val ocrReview=MutableStateFlow("")
    val canCorrectOcr=MutableStateFlow(false)
    private var ocrPage: PageRow?=null
    val messages=Channel<String>(Channel.BUFFERED)
    val replacement=MutableStateFlow<String?>(null)
    val mergeSources=MutableStateFlow<List<DocumentRow>>(emptyList())
    val passwordRequest=MutableStateFlow<Uri?>(null)
    val outputLocked=MutableStateFlow(false)
    val formFields=MutableStateFlow<List<Pair<String,String>>?>(null)
    val searchHits=MutableStateFlow<List<cn.paperforge.app.pdf.PdfSearch.Hit>>(emptyList())
    val annotationPage=MutableStateFlow(0)
    val marks=MutableStateFlow<List<PdfMark>>(emptyList())
    private var marksSave: Job?=null
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
    fun open(doc: DocumentRow) { if(busy.value) return; selected.value=doc; searchHits.value=emptyList(); screen.value=if(doc.kind=="pdf") "reader" else "editor" }
    fun importImages(uris: List<Uri>) { if(uris.isEmpty()) return; perform("正在导入图片") {
        val doc=selected.value?.takeIf { it.kind=="scan" && screen.value in listOf("editor","capture") } ?: repo.newScan()
        selected.value=doc; repo.importImages(doc.id,uris); screen.value="editor"
    } }
    fun importPdf(uri: Uri,password: String="")=perform("正在导入 PDF") {
        try { selected.value=repo.importPdf(uri,password); passwordRequest.value=null; screen.value="reader" }
        catch(e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { passwordRequest.value=uri; if(password.isNotEmpty()) messages.send("密码不正确") }
    }
    fun captured(file: File,previewQuad: cn.paperforge.app.model.Quad?=null)=perform("正在检测纸张") {
        val target=replacement.value
        if(target==null) { val page=repo.addCapture(selected.value!!.id,file,previewQuad); if(page.corners==cn.paperforge.app.model.Quad.Full.encode()) messages.send("本页未找到完整纸边，已保留全图；可点击自动裁边重试") } else {
            val page=repo.dao.pages(selected.value!!.id).first { it.id==target }
            val detection=cn.paperforge.app.imaging.ScanProcessor.detectFile(file)
            repo.savePage(page.copy(original=file.path,corners=(detection.quad ?: cn.paperforge.app.model.Quad.Full).encode(),rotation=0,deskewDegrees=0f,detectionConfidence=detection.confidence,detectionMethod=detection.method))
            replacement.value=null; screen.value="editor"
        }
    }
    fun save(page: PageRow)=perform("正在更新页面") {
        repo.savePage(page)
    }
    fun undo()=perform("撤销") { selected.value?.let { repo.undo(it.id) } }
    fun redo()=perform("重做") { selected.value?.let { repo.undo(it.id,true) } }
    fun move(page: PageRow,delta: Int)=perform("正在调整顺序") {
        val all=repo.dao.pages(page.documentId).toMutableList(); val from=all.indexOfFirst { it.id==page.id }; val to=(from+delta).coerceIn(0,all.lastIndex)
        all.add(to,all.removeAt(from)); repo.reorder(page.documentId,all)
    }
    fun deletePage(page: PageRow)=perform("正在删除页面") { repo.deletePage(page) }
    fun delete(doc: DocumentRow)=perform("移入回收站") { repo.trash(doc); if(selected.value?.id==doc.id) { selected.value=null; screen.value="home" } }
    fun purge(doc: DocumentRow)=perform("正在永久删除") { repo.deleteDocument(doc) }
    fun restore(doc: DocumentRow)=perform("正在恢复") { repo.restore(doc) }
    fun categorize(doc: DocumentRow,folder: String,tags: String)=perform("保存分类") { repo.dao.updateDocument(doc.copy(folder=folder,tags=tags)) }
    fun favorite(doc: DocumentRow)=perform("更新收藏") { repo.dao.updateDocument(doc.copy(favorite=!doc.favorite)) }
    fun rename(doc: DocumentRow,title: String)=perform("正在重命名") { repo.rename(doc,title); selected.value=doc.copy(title=safeFileName(title)) }
    fun export(paper: String,quality: Int,title: String?=null,searchable: Boolean=false,resolution: String="STANDARD") {
        val doc=selected.value ?: return
        if(busy.value) return
        val request=OneTimeWorkRequestBuilder<ExportWorker>().setInputData(workDataOf("document" to doc.id,"paper" to paper,"quality" to quality,"title" to (title ?: doc.title),"searchable" to searchable,"resolution" to resolution)).build()
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
                    if(info.state==WorkInfo.State.SUCCEEDED) {
                        outputLocked.value=false; output.value=File(info.outputData.getString("path")!!)
                        if(info.outputData.getString("mode") in listOf("compress","optimize")) messages.send("${info.outputData.getLong("inputBytes",0)/1024} KB → ${info.outputData.getLong("outputBytes",0)/1024} KB")
                    }
                    else messages.send(info.outputData.getString("error") ?: "导出已取消")
                }
                !info.state.isFinished
            }.collect()
        }
    }
    fun cancel() { exportId?.let { WorkManager.getInstance(getApplication()).cancelWorkById(it) }; task?.cancel() }
    fun saveOutput(uri: Uri) { val file=output.value ?: return; perform("正在保存文件") { repo.saveOutput(file,uri); messages.send("文件已保存") } }
    fun recognize(page: PageRow,chinese: Boolean)=perform("正在离线识别文字") {
        val (updated,result)=repo.cacheOcr(page,ocrEngine.layout(page,chinese))
        val uncertain=result.lines.indices.filter { (result.lines[it].confidence ?: 1f)<.65f }.map { it+1 }
        ocrReview.value=if(uncertain.isEmpty()) result.warnings.joinToString("；") else "建议核对第 ${uncertain.joinToString("、")} 行。模型分数仅用于提示。"
        if(result.text.isBlank()) messages.send("没有识别到文字。请将纸面拍清楚、减少反光，或旋转页面后重试。") else { ocrPage=updated; canCorrectOcr.value=true; ocr.value=result.text }
    }
    fun recognizeAll()=perform("正在识别全部页面") {
        val all=repo.dao.pages(selected.value!!.id); val text=mutableListOf<String>()
        all.forEachIndexed { index,page ->
            progress.value="离线 OCR · ${index+1}/${all.size}"
            val (_,result)=repo.cacheOcr(page,ocrEngine.layout(page,true));text.add("第 ${index+1} 页\n${result.text}")
        }; ocrPage=null; canCorrectOcr.value=false; ocr.value=text.joinToString("\n\n")
    }
    fun correctOcr(text: String)=perform("保存文字校正") {
        val old=ocrPage ?: return@perform
        val current=repo.dao.pages(old.documentId).firstOrNull { it.id==old.id } ?: return@perform
        val layout=old.ocrLayout?.let { cn.paperforge.app.model.OcrLayout.decode(it) }
        val lines=text.lines()
        require(current.revision==old.revision) { "页面已修改，请重新识别后再校正" }
        val updated=layout?.takeIf { it.lines.size==lines.size }?.let { l -> l.copy(lines=l.lines.mapIndexed { i,line -> line.copy(text=lines[i],elements=emptyList(),corrected=true) }).encode() }
        repo.dao.updatePage(current.copy(ocrText=text,ocrLayout=updated)); messages.send("已保存校正文字" + if(updated==null) "；行数改变后，PDF 文字层将重新识别" else "")
    }
    fun detectPage(page: PageRow)=perform("正在自动识别纸张边缘") {
        val detection=cn.paperforge.app.imaging.ScanProcessor.detectFile(File(page.original))
        val q=detection.quad
        if(q!=null) { repo.savePage(page.copy(corners=q.encode(),detectionConfidence=detection.confidence,detectionMethod=detection.method)); messages.send("已自动裁切纸张") }
        else messages.send("未找到完整纸边，已保留当前页面。建议纸张四周留出一点背景。")
    }
    fun merge(uris: List<Uri>)=perform("正在合并 PDF") {
        mergeSources.value=uris.map { repo.importPdf(it) }; screen.value="merge"
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
    fun extractText()=perform("正在提取 PDF 文字") { ocrPage=null; canCorrectOcr.value=false; ocr.value=PdfEngine.text(File(selected.value!!.pdfPath!!)).ifBlank { "这份 PDF 没有可提取文字。扫描图请使用离线 OCR。" } }
    fun previewOutput(file: File)=perform("正在打开 PDF") {
        val doc=repo.registerPdf(file,file.nameWithoutExtension)
        selected.value=doc; output.value=null; screen.value="reader"
    }
    fun queueTool(mode: String,options: org.json.JSONObject=org.json.JSONObject()) {
        if(busy.value) return
        val data=workDataOf("mode" to mode,"document" to selected.value?.id,"options" to options.toString(),"sources" to mergeSources.value.mapNotNull { it.pdfPath }.toTypedArray())
        val request=OneTimeWorkRequestBuilder<PdfJobWorker>().setInputData(data).build()
        WorkManager.getInstance(getApplication()).enqueue(request)
        getApplication<Application>().getSharedPreferences("session",0).edit().putString("export",request.id.toString()).apply(); watchExport(request.id)
    }
    fun encrypt(password: String,printing: Boolean,copying: Boolean)=perform("正在加密") {
        val doc=selected.value!!; val file=File(repo.exports,"${safeFileName(doc.title)}-加密-${System.currentTimeMillis()}.pdf")
        PdfTools.encrypt(File(doc.pdfPath!!),file,password,printing,copying); outputLocked.value=true; output.value=file
    }
    fun forms()=perform("读取表单") { formFields.value=PdfTools.forms(File(selected.value!!.pdfPath!!)); if(formFields.value!!.isEmpty()) { formFields.value=null; messages.send("这份 PDF 没有可填写的 AcroForm 字段") } }
    fun fillForms(values: Map<String,String>)=perform("保存表单") {
        val doc=selected.value!!; val file=File(repo.exports,"表单-${System.currentTimeMillis()}.pdf")
        PdfTools.fillForms(File(doc.pdfPath!!),file,values,getApplication()); selected.value=repo.registerPdf(file,doc.title+" · 表单"); outputLocked.value=false; output.value=file; formFields.value=null
    }
    fun searchPdf(text: String)=perform("正在搜索文字") { searchHits.value=PdfSearch.find(File(selected.value!!.pdfPath!!),text) }
    fun reorder(order: List<PageRow>)=perform("保存页序") { selected.value?.let { repo.reorder(it.id,order) } }
    fun duplicate(page: PageRow)=perform("复制页面") { repo.duplicate(page) }
    fun splitBook(page: PageRow)=perform("分割双页") { repo.splitBook(page) }
    fun applyAll(page: PageRow)=perform("应用到所有页") { repo.dao.pages(page.documentId).forEach { repo.savePage(it.copy(filter=page.filter,brightness=page.brightness,contrast=page.contrast)) } }
    fun rotateMany(ids: Set<String>)=perform("批量旋转") { pages.value.filter { it.id in ids }.forEach { repo.savePage(it.copy(rotation=(it.rotation+90)%360)) } }
    fun deleteMany(ids: Set<String>)=perform("批量删除") { pages.value.filter { it.id in ids }.forEach { repo.deletePage(it) } }
    fun images(format: String)=perform("导出扫描图片") {
        val doc=selected.value!!; val all=repo.dao.pages(doc.id)
        val file=File(repo.exports,"${safeFileName(doc.title)}-${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip -> all.forEachIndexed { index,page ->
            currentCoroutineContext().ensureActive(); val bitmap=cn.paperforge.app.imaging.ScanProcessor.render(page,2800)
            try { zip.putNextEntry(java.util.zip.ZipEntry("page-%04d.%s".format(index+1,format))); bitmap.compress(if(format=="png") android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG,94,zip); zip.closeEntry() } finally { bitmap.recycle() }
        } }; outputLocked.value=false; output.value=file
    }
    fun backup()=perform("正在离线备份") { val file=File(repo.exports,"PaperForge-backup-${System.currentTimeMillis()}.zip"); BackupStore.backup(repo,file); outputLocked.value=false; output.value=file }
    fun restoreBackup(uri: Uri)=perform("正在恢复备份") { val count=BackupStore.restore(repo,uri); messages.send("已恢复 $count 份文档") }
    private fun markDraft()=File(repo.folder(selected.value!!.id),"annotations.json")
    fun beginMarks()=perform("读取批注草稿") {
        marks.value=runCatching { PdfMark.decode(org.json.JSONArray(markDraft().readText())) }.getOrDefault(emptyList()); screen.value="annotations"
    }
    fun updateMarks(value: List<PdfMark>) {
        marks.value=value; val file=markDraft()
        marksSave?.cancel(); marksSave=viewModelScope.launch(Dispatchers.IO) { delay(250); file.writeText(org.json.JSONArray(value.map { it.json() }).toString()) }
    }
    fun saveMarks() {
        if(busy.value || marks.value.isEmpty()) return
        val file=File(repo.exports,"marks-job-${UUID.randomUUID()}.json")
        file.writeText(org.json.JSONArray(marks.value.map { it.json() }).toString())
        queueTool("marks",org.json.JSONObject().put("marksFile",file.path))
    }
}

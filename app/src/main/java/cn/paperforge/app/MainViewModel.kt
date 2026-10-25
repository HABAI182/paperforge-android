package cn.paperforge.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.model.safeFileName
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as PaperForgeApp).repository
    private val ocrEngine = (app as PaperForgeApp).ocrEngine
    val query = MutableStateFlow("")
    val homeTab = MutableStateFlow(0)
    val renameSelection = MutableStateFlow<List<String>>(emptyList())
    val canUndoNames = MutableStateFlow(LibraryPreferences(repo.context).renameUndo().isNotEmpty())
    val renameNotice = MutableStateFlow<Pair<String, Int>?>(null)

    fun beginBatchRename(ids: List<String>) {
        renameSelection.value = ids
        screen.value = "rename-batch"
    }

    fun applyBatchNames(rows: List<NameChange>, options: NamingOptions) =
        perform("更新文档名称") {
            val count = repo.renameBatch(rows, options)
            canUndoNames.value = true
            homeTab.value = 1
            query.value = ""
            screen.value = "home"
            renameNotice.value = UUID.randomUUID().toString() to count
        }

    fun undoBatchNames() {
        viewModelScope.launch {
            busy.first { !it }
            perform("撤销批量命名") {
                val result = repo.undoBatchRename()
                canUndoNames.value = false
                messages.send(
                    "已撤销 ${result.restored} 份" +
                        if (result.preserved > 0) "，其余 ${result.preserved} 份保留现状" else ""
                )
            }
        }
    }

    val documents =
        query
            .flatMapLatest {
                if (it.isBlank()) repo.documents
                else repo.dao.search(cn.paperforge.app.model.CjkSearch.normalize(it))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trash =
        repo.dao
            .observeTrash()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selected = MutableStateFlow<DocumentRow?>(null)
    val pages =
        selected
            .flatMapLatest { it?.let { d -> repo.dao.observePages(d.id) } ?: flowOf(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val screen = MutableStateFlow("home")
    val pdfEntry = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val output = MutableStateFlow<File?>(null)
    val ocr = MutableStateFlow<String?>(null)
    val ocrReview = MutableStateFlow("")
    val canCorrectOcr = MutableStateFlow(false)
    private var ocrPage: PageRow? = null
    val ocrVersions = MutableStateFlow<List<OcrTextVersion>>(emptyList())
    val proofPage = MutableStateFlow<PageRow?>(null)
    val proofRegion = MutableStateFlow<OcrRegionResult?>(null)

    fun openProofreading(page: PageRow) {
        proofPage.value = page
        proofRegion.value = null
        screen.value = "ocr-proof"
    }

    fun recognizeRegion(page: PageRow, region: cn.paperforge.app.model.OcrRegion) =
        perform("正在识别选区") {
            proofRegion.value =
                ocrEngine.recognizeRegion(
                    page,
                    region,
                    UserSettings.string("ocrLanguage", "CHINESE") != "LATIN",
                )
        }

    fun applyRegion(page: PageRow) =
        perform("更新选区文字") {
            val run = proofRegion.value ?: return@perform
            proofPage.value = repo.applyOcrRegion(page, run)
            proofRegion.value = null
            messages.send("已更新选区，其余文字保留")
        }

    fun correctProofLine(page: PageRow, index: Int, word: Int?, text: String) =
        perform("保存局部校正") {
            proofPage.value = repo.correctOcrLine(page, index, word, text)
            messages.send("校正已保存，原始记录保留")
        }

    val messages = Channel<String>(Channel.BUFFERED)
    val comparison = LibraryComparisonController(repo, viewModelScope, messages)
    val comparisonReturn = MutableStateFlow(false)

    fun beginComparison(
        ids: List<String>? = null,
        versions: Boolean = false,
        familyId: String? = null,
    ) {
        comparison.configure(ids?.toSet(), versions, familyId)
        screen.value = "comparison"
    }

    fun openComparisonPage(ref: ComparePage) {
        if (busy.value) return
        viewModelScope.launch {
            val doc = repo.dao.document(ref.documentId)?.takeIf { it.trashedAt == null }
            if (doc == null) {
                messages.send("文档已移除，请刷新比较结果")
                return@launch
            }
            val target =
                if (doc.kind == "scan")
                    repo.dao.pages(doc.id).let { pages ->
                        if (ref.pageId == null) pages.firstOrNull()
                        else pages.firstOrNull { it.id == ref.pageId }
                    }
                else null
            if (doc.kind == "scan" && ref.pageId != null && target == null) {
                messages.send("页面已变化，请刷新比较结果")
                return@launch
            }
            open(doc)
            comparisonReturn.value = true
            if (doc.kind == "pdf") readerTarget.value = doc.id to ref.index
            else if (target != null) editorPageTarget.value = doc.id to target.id
        }
    }

    fun closeDocument() {
        screen.value = if (comparisonReturn.value) "comparison" else "home"
        comparisonReturn.value = false
    }

    val replacement = MutableStateFlow<String?>(null)
    val mergeSources = MutableStateFlow<List<DocumentRow>>(emptyList())
    val passwordRequest = MutableStateFlow<Uri?>(null)
    val outputLocked = MutableStateFlow(false)
    val formFields = MutableStateFlow<List<Pair<String, String>>?>(null)
    val searchHits = MutableStateFlow<List<cn.paperforge.app.pdf.PdfSearch.Hit>>(emptyList())
    val annotationPage = MutableStateFlow(0)
    val readerTarget = MutableStateFlow<Pair<String, Int>?>(null)
    val editorTarget = MutableStateFlow<Pair<String, Int>?>(null)
    val editorPageTarget = MutableStateFlow<Pair<String, String>?>(null)
    val libraryFocus = MutableStateFlow<LibrarySearchHit?>(null)
    val outputSource = MutableStateFlow<String?>(null)
    val profileReturn = MutableStateFlow("settings")

    fun openProfiles(origin: String) {
        profileReturn.value = origin
        screen.value = "profiles"
    }

    fun exportProfiles() =
        perform("导出配置档案") {
            val file = File(repo.exports, "PaperForge-profiles-${System.currentTimeMillis()}.json")
            file.writeText(SettingsProfiles(repo.context).export().toString(2), Charsets.UTF_8)
            outputLocked.value = false
            output.value = file
        }

    val libraryFacts =
        repo.dao
            .libraryFacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val librarySearchHits =
        query
            .flatMapLatest { q ->
                if (q.isBlank()) flowOf(emptyList())
                else repo.dao.searchHits(cn.paperforge.app.model.CjkSearch.expression(q))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val marks = MutableStateFlow<List<PdfMark>>(emptyList())
    private var marksSave: Job? = null
    private var task: Job? = null
    private var exportId: UUID? = null
    val localTasks =
        repo.dao
            .observeTasks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val appScope = (app as PaperForgeApp).backgroundScope

    init {
        val prefs = app.getSharedPreferences("session", 0)
        val pending = prefs.getString("export", null)
        if (pending != null) runCatching { UUID.fromString(pending) }.onSuccess { watchExport(it) }
    }

    private suspend fun publishOutput(file: File, source: DocumentRow? = null) {
        withContext(Dispatchers.IO) { repo.recordOutput(file, source) }
        output.value = file
    }

    fun reopenOutput(row: OutputRow) {
        if (File(row.path).exists()) {
            outputLocked.value = false
            output.value = File(row.path)
        } else viewModelScope.launch { messages.send("本地结果已移除，外部保存位置仍在记录中") }
    }

    fun openSearchHit(doc: DocumentRow, hit: LibrarySearchHit, text: String) {
        if (busy.value) return
        open(doc)
        libraryFocus.value = hit
        if (doc.kind == "pdf") {
            readerTarget.value = doc.id to hit.page
            searchPdf(text)
        } else if (hit.pageId != null) editorPageTarget.value = doc.id to hit.pageId
        else editorTarget.value = doc.id to hit.page
    }

    private fun perform(label: String, action: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        progress.value = label
        val launched =
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { action() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    messages.send(e.message ?: "操作未完成")
                } finally {
                    progress.value = ""
                    busy.value = false
                }
            }
        task = launched
    }

    fun newScan(camera: Boolean = true) =
        perform("正在建立草稿") {
            val doc = repo.newScan()
            selected.value = doc
            screen.value = if (camera) "capture" else "editor"
        }

    fun open(doc: DocumentRow) {
        if (busy.value) return
        comparisonReturn.value = false
        libraryFocus.value = null
        editorPageTarget.value = null
        viewModelScope.launch(Dispatchers.IO) {
            repo.dao.opened(doc.id, System.currentTimeMillis())
        }
        selected.value = doc
        searchHits.value = emptyList()
        screen.value = if (doc.kind == "pdf") "reader" else "editor"
    }

    fun importImages(uris: List<Uri>, recognizeFirst: Boolean = false) {
        if (uris.isEmpty()) return
        perform("正在导入图片") {
            val doc =
                selected.value?.takeIf {
                    it.kind == "scan" && screen.value in listOf("editor", "capture")
                } ?: repo.newScan()
            selected.value = doc
            repo.importImages(doc.id, uris)
            screen.value = "editor"
            if (recognizeFirst) {
                progress.value = "正在离线识别文字"
                repo.dao.pages(doc.id).lastOrNull()?.let { showOcr(it, true) }
            }
        }
    }

    fun importPdf(uri: Uri, password: String = "") =
        perform("正在导入 PDF") {
            try {
                selected.value = repo.importPdf(uri, password)
                passwordRequest.value = null
                screen.value = "reader"
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                passwordRequest.value = uri
                if (password.isNotEmpty()) messages.send("密码不正确")
            }
        }

    suspend fun prepareCapture(
        documentId: String,
        reference: cn.paperforge.app.model.CaptureReference?,
        replacementId: String? = null,
        file: File = repo.captureFile(documentId),
    ) =
        cn.paperforge.app.imaging.CaptureTasks.prepare(
            repo,
            documentId,
            reference,
            replacementId,
            file,
        )

    fun captureReady(row: LocalTaskRow) {
        appScope.launch {
            runCatching { cn.paperforge.app.imaging.CaptureTasks.accept(repo, row.id) }
                .onFailure {
                    cn.paperforge.app.imaging.CaptureTasks.fail(
                        repo,
                        row.id,
                        it.message ?: "照片处理失败",
                    )
                }
            if (
                selected.value?.id == row.documentId &&
                    (row.targetPageId != null || UserSettings.bool("reviewAfterCapture", false))
            ) {
                if (replacement.value == row.targetPageId) replacement.value = null
                screen.value = "editor"
            }
        }
    }

    fun captureFailed(row: LocalTaskRow, error: String) {
        appScope.launch { cn.paperforge.app.imaging.CaptureTasks.fail(repo, row.id, error) }
    }

    fun retryTask(row: LocalTaskRow) {
        appScope.launch {
            runCatching {
                    cn.paperforge.app.imaging.CaptureTasks.accept(repo, row.id, retry = true)
                }
                .onFailure { messages.send(it.message ?: "重试未完成") }
        }
    }

    fun cancelLocalTask(row: LocalTaskRow) {
        appScope.launch { cn.paperforge.app.imaging.CaptureTasks.cancel(repo, row) }
    }

    fun openTask(row: LocalTaskRow, retake: Boolean = false) {
        viewModelScope.launch {
            val doc = row.documentId?.let { repo.dao.document(it) } ?: return@launch
            open(doc)
            val pages = repo.dao.pages(doc.id)
            val page =
                pages.firstOrNull { it.id == row.pageId }
                    ?: pages.firstOrNull { it.id == row.targetPageId }
            if (retake) {
                replacement.value = page?.id
                screen.value = "capture"
            } else page?.let { editorPageTarget.value = doc.id to it.id }
        }
    }

    fun save(page: PageRow) = perform("正在更新页面") { repo.savePage(page) }

    fun copyPagesTo(ids: Set<String>, targetId: String?, title: String, move: Boolean = false) {
        val source = selected.value?.id ?: return
        perform(if (move) "正在移动页面与原件" else "正在复制页面与原件") {
            val result = repo.copyPages(source, ids, targetId, title, move)
            selected.value = result.document
            editorPageTarget.value = result.document.id to result.firstPageId
            screen.value = "editor"
            messages.send(
                "已${if(move) "移动" else "复制"} ${result.pages} 页到“${result.document.title}”"
            )
        }
    }

    fun openPageOrigin(origin: PageOrigin) {
        viewModelScope.launch {
            val doc = origin.documentId?.let { repo.dao.document(it) }
            if (doc == null) {
                messages.send("来源文档已不在文档库，当前副本仍可使用")
                return@launch
            }
            val page = repo.dao.pages(doc.id).firstOrNull { it.id == origin.pageId }
            if (page == null) {
                messages.send("来源页已移除，当前副本仍可使用")
                return@launch
            }
            open(doc)
            editorPageTarget.value = doc.id to page.id
        }
    }

    fun dewarpPage(page: PageRow, strength: Float) =
        perform("正在校正曲面") {
            val source =
                cn.paperforge.app.imaging.ScanProcessor.render(
                    page.copy(filter = "ORIGINAL", brightness = 0f, contrast = 1f),
                    2400,
                )
            val file = File(repo.folder(page.documentId), "${UUID.randomUUID()}-dewarp.png")
            val inputFile =
                File(repo.folder(page.documentId), "${UUID.randomUUID()}-dewarp-input.png")
            val gridFile = File(repo.folder(page.documentId), "${UUID.randomUUID()}.pfgrid.gz")
            var saved = false
            try {
                val result = cn.paperforge.app.imaging.PageDewarp.process(source, strength)
                val corrected = result.bitmap
                try {
                    file.outputStream().use {
                        check(
                            corrected.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        )
                    }
                } finally {
                    corrected.recycle()
                }
                inputFile.outputStream().use {
                    check(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                result.grid.write(gridFile)
                repo.savePage(
                    page.copy(
                        original = file.path,
                        corners = cn.paperforge.app.model.Quad.Full.encode(),
                        rotation = 0,
                        deskewDegrees = 0f,
                        detectionConfidence = null,
                        detectionMethod = "dewarp",
                        processingMetadata =
                            cn.paperforge.app.data.PageResources.withDewarp(
                                page,
                                inputFile,
                                gridFile,
                                file,
                                result.grid.strength,
                                cn.paperforge.app.imaging.ScanModel.fingerprint(true),
                            ),
                    )
                )
                saved = true
                UserSettings.set("dewarpStrength", strength)
                messages.send("已应用曲面校正，可撤销")
            } finally {
                source.recycle()
                if (!saved) listOf(file, inputFile, gridFile).forEach { it.delete() }
            }
        }

    fun undo() = perform("撤销") { selected.value?.let { repo.undo(it.id) } }

    fun redo() = perform("重做") { selected.value?.let { repo.undo(it.id, true) } }

    fun move(page: PageRow, delta: Int) =
        perform("正在调整顺序") {
            val all = repo.dao.pages(page.documentId).toMutableList()
            val from = all.indexOfFirst { it.id == page.id }
            val to = (from + delta).coerceIn(0, all.lastIndex)
            all.add(to, all.removeAt(from))
            repo.reorder(page.documentId, all)
        }

    fun deletePage(page: PageRow) = perform("正在删除页面") { repo.deletePage(page) }

    fun delete(doc: DocumentRow) =
        perform("移入回收站") {
            repo.trash(doc)
            if (selected.value?.id == doc.id) {
                selected.value = null
                screen.value = "home"
            }
        }

    fun purge(doc: DocumentRow) =
        perform("正在永久删除") {
            repo.deleteDocument(doc)
            if (selected.value?.id == doc.id) {
                selected.value = null
                screen.value = "home"
            }
        }

    fun restore(doc: DocumentRow) = perform("正在恢复") { repo.restore(doc) }

    fun trashDocuments(ids: Set<String>) =
        perform("批量移入回收站") {
            repo.editDocuments(ids) { it.copy(trashedAt = System.currentTimeMillis()) }
            clearSelectedDocument(ids)
        }

    fun restoreDocuments(ids: Set<String>) =
        perform("批量恢复") {
            repo.editDocuments(ids) {
                it.copy(trashedAt = null, modified = System.currentTimeMillis())
            }
        }

    fun purgeDocuments(ids: Set<String>) =
        perform("批量直接删除") {
            ids.forEachIndexed { index, id ->
                currentCoroutineContext().ensureActive()
                progress.value = "直接删除 ${index+1}/${ids.size}"
                repo.dao.document(id)?.let { repo.deleteDocument(it) }
                clearSelectedDocument(setOf(id))
            }
        }

    private fun clearSelectedDocument(ids: Set<String>) {
        if (selected.value?.id in ids) {
            selected.value = null
            screen.value = "home"
        }
    }

    fun favoriteDocuments(ids: Set<String>, favorite: Boolean) =
        perform("批量收藏") { repo.editDocuments(ids) { it.copy(favorite = favorite) } }

    fun categorizeDocuments(ids: Set<String>, folder: String?, tags: String?) =
        perform("批量分类") {
            repo.editDocuments(ids) {
                it.copy(
                    folder = folder?.let(::libraryFolder) ?: it.folder,
                    tags = tags?.let(::libraryTags) ?: it.tags,
                    modified = System.currentTimeMillis(),
                )
            }
        }

    fun mergeDocuments(ids: List<String>) =
        perform("准备合并") {
            val docs =
                ids.mapNotNull { repo.dao.document(it) }
                    .filter { it.trashedAt == null && it.kind == "pdf" }
            require(docs.size >= 2) { "请选择至少两份 PDF" }
            mergeSources.value = docs
            screen.value = "merge"
        }

    fun shareDocuments(ids: List<String>) =
        perform("打包所选文档") {
            val docs = ids.mapNotNull { repo.dao.document(it) }.filter { it.trashedAt == null }
            require(docs.isNotEmpty()) { "请选择文档" }
            val sharePaper = UserSettings.string("paper", "Auto")
            val shareQuality = UserSettings.number("quality", 90f).toInt()
            val shareSearchable = UserSettings.bool("searchable", true)
            val requestedResolution = UserSettings.string("resolution", "STANDARD")
            val shareResolution =
                if (requestedResolution == "CUSTOM")
                    "DPI:${cn.paperforge.app.imaging.ScanConfig.dpi(requestedResolution)}"
                else requestedResolution
            val file = File(repo.exports, "PaperForge-documents-${System.currentTimeMillis()}.zip")
            val partial = File(file.path + ".part")
            val temporary =
                File(getApplication<Application>().cacheDir, "library-share-${UUID.randomUUID()}")
                    .apply { mkdirs() }
            try {
                java.util.zip.ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                    docs.forEachIndexed { index, doc ->
                        currentCoroutineContext().ensureActive()
                        progress.value = "打包文档 ${index+1}/${docs.size}"
                        val source =
                            if (doc.kind == "pdf") File(requireNotNull(doc.pdfPath))
                            else
                                File(temporary, "${index}.pdf").also {
                                    PdfEngine.scan(
                                        repo.dao.pages(doc.id),
                                        it,
                                        context = getApplication(),
                                        searchable = shareSearchable,
                                        paper = sharePaper,
                                        quality = shareQuality,
                                        resolution = shareResolution,
                                    )
                                }
                        zip.putNextEntry(
                            java.util.zip.ZipEntry(
                                "%03d-%s.pdf".format(index + 1, safeFileName(doc.title))
                            )
                        )
                        source.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                        if (doc.kind != "pdf") source.delete()
                    }
                }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(file)) { "无法完成文档包" }
                outputLocked.value = false
                publishOutput(file)
            } finally {
                partial.delete()
                temporary.deleteRecursively()
            }
        }

    fun categorize(doc: DocumentRow, folder: String, tags: String) =
        perform("保存分类") {
            repo.editDocuments(setOf(doc.id)) {
                it.copy(
                    folder = libraryFolder(folder),
                    tags = libraryTags(tags),
                    modified = System.currentTimeMillis(),
                )
            }
        }

    fun favorite(doc: DocumentRow) =
        perform("更新收藏") { repo.dao.updateDocument(doc.copy(favorite = !doc.favorite)) }

    fun rename(doc: DocumentRow, title: String) =
        perform("正在重命名") {
            repo.rename(doc, title)
            selected.value = doc.copy(title = safeFileName(title))
        }

    fun export(
        paper: String,
        quality: Int,
        title: String? = null,
        searchable: Boolean = false,
        resolution: String = "STANDARD",
    ) {
        val doc = selected.value ?: return
        if (busy.value) return
        val request =
            OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(
                    workDataOf(
                        "document" to doc.id,
                        "paper" to paper,
                        "quality" to quality,
                        "title" to (title ?: doc.title),
                        "searchable" to searchable,
                        "resolution" to
                            if (resolution == "CUSTOM")
                                "DPI:${cn.paperforge.app.imaging.ScanConfig.dpi(resolution)}"
                            else resolution,
                    )
                )
                .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("export-${doc.id}", ExistingWorkPolicy.KEEP, request)
        getApplication<Application>()
            .getSharedPreferences("session", 0)
            .edit()
            .putString("export", request.id.toString())
            .apply()
        watchExport(request.id)
    }

    private fun watchExport(id: UUID) {
        exportId = id
        busy.value = true
        viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfoByIdFlow(id)
                .filterNotNull()
                .takeWhile { info ->
                    progress.value = "导出 PDF · ${info.progress.getInt("progress",0)}%"
                    if (info.state.isFinished) {
                        busy.value = false
                        progress.value = ""
                        exportId = null
                        getApplication<Application>()
                            .getSharedPreferences("session", 0)
                            .edit()
                            .remove("export")
                            .apply()
                        if (info.state == WorkInfo.State.SUCCEEDED) {
                            outputLocked.value = false
                            publishOutput(File(info.outputData.getString("path")!!))
                            if (info.outputData.getString("mode") in listOf("compress", "optimize"))
                                messages.send(
                                    "${info.outputData.getLong("inputBytes",0)/1024} KB → ${info.outputData.getLong("outputBytes",0)/1024} KB"
                                )
                        } else messages.send(info.outputData.getString("error") ?: "导出已取消")
                    }
                    !info.state.isFinished
                }
                .collect()
        }
    }

    fun cancel() {
        exportId?.let { WorkManager.getInstance(getApplication()).cancelWorkById(it) }
        task?.cancel()
    }

    fun saveOutput(uri: Uri) {
        val file = output.value ?: return
        perform("正在保存文件") {
            repo.saveOutput(file, uri)
            messages.send("文件已保存")
        }
    }

    fun recognize(
        page: PageRow,
        chinese: Boolean = UserSettings.string("ocrLanguage", "CHINESE") != "LATIN",
    ) = perform("正在离线识别文字") { showOcr(page, chinese) }

    private suspend fun showOcr(page: PageRow, chinese: Boolean) {
        ocrVersions.value = emptyList()
        canCorrectOcr.value = false
        val (updated, result) =
            repo.cacheOcr(
                page,
                ocrEngine.layout(
                    page,
                    chinese && UserSettings.string("ocrLanguage", "CHINESE") != "LATIN",
                ),
            )
        ocrReview.value =
            "${cn.paperforge.app.ocr.OcrMode.parse(result.recognitionMode).label} · ${result.lines.size} 行 · 可编辑、复制或导出"
        if (result.text.isBlank()) messages.send("没有识别到文字。请将纸面拍清楚、减少反光，或旋转页面后重试。")
        else {
            ocrPage = updated
            ocrVersions.value = OcrRecords.versions(updated)
            canCorrectOcr.value = true
            ocr.value = result.text
        }
    }

    fun recognizeAll() =
        perform("正在识别全部页面") {
            val all = repo.dao.pages(selected.value!!.id)
            val text = mutableListOf<String>()
            all.forEachIndexed { index, page ->
                progress.value = "离线 OCR · ${index+1}/${all.size}"
                val (_, result) =
                    repo.cacheOcr(
                        page,
                        ocrEngine.layout(
                            page,
                            UserSettings.string("ocrLanguage", "CHINESE") != "LATIN",
                        ),
                    )
                text.add("第 ${index+1} 页\n${result.text}")
            }
            ocrPage = null
            ocrVersions.value = emptyList()
            canCorrectOcr.value = false
            ocr.value = text.joinToString("\n\n")
        }

    fun openOcr(page: PageRow) =
        perform("打开识别结果") {
            val current =
                repo.dao.pages(page.documentId).firstOrNull { it.id == page.id } ?: return@perform
            if (current.ocrText == null)
                showOcr(current, UserSettings.string("ocrLanguage", "CHINESE") != "LATIN")
            else {
                ocrPage = current
                ocrVersions.value = OcrRecords.versions(current)
                canCorrectOcr.value = true
                ocrReview.value = "已保存文字 · 可查看原始识别与历次校正"
                ocr.value = current.ocrText
            }
        }

    fun correctOcr(text: String) =
        perform("保存文字校正") {
            val page = ocrPage ?: return@perform
            val updated = repo.correctOcr(page, text)
            ocrPage = updated
            ocrVersions.value = OcrRecords.versions(updated)
            ocr.value = text
            messages.send(
                "校正已保存，原始识别与历史版本保留" + if (updated.ocrLayout == null) "；行数改变后，PDF文字层会重新识别" else ""
            )
        }

    fun detectPage(page: PageRow) =
        perform("正在自动识别纸张边缘") {
            val detection = cn.paperforge.app.imaging.ScanProcessor.detectFile(File(page.original))
            val q = detection.quad
            if (q != null) {
                repo.savePage(
                    page.copy(
                        corners = q.encode(),
                        detectionConfidence = detection.confidence,
                        detectionMethod = detection.method,
                    )
                )
                messages.send("已自动裁切纸张")
            } else messages.send("未找到完整纸边，已保留当前页面。建议纸张四周留出一点背景。")
        }

    fun merge(uris: List<Uri>) =
        perform("正在合并 PDF") {
            mergeSources.value = uris.map { repo.importPdf(it) }
            screen.value = "merge"
        }

    fun extract(range: String, rotate: Int) =
        perform("正在处理页面") {
            val doc = selected.value ?: return@perform
            val file =
                File(repo.exports, "${safeFileName(doc.title)}-${System.currentTimeMillis()}.pdf")
            PdfEngine.extract(File(doc.pdfPath!!), range, file, rotate)
            selected.value =
                repo.registerPdf(
                    file,
                    doc.title + if (rotate == 0) " · 抽页" else " · 旋转",
                    listed = false,
                )
            publishOutput(file, doc)
        }

    fun compress(quality: Int) =
        perform("正在压缩嵌入图片") {
            val doc = selected.value ?: return@perform
            val source = File(doc.pdfPath!!)
            val file = File(repo.exports, "压缩-${System.currentTimeMillis()}.pdf")
            PdfEngine.compress(source, file, quality) { progress.value = "压缩 · $it%" }
            val change = 100f * (source.length() - file.length()) / source.length().coerceAtLeast(1)
            messages.send(
                "${source.length()/1024} KB → ${file.length()/1024} KB；" +
                    if (change > 0) "减少 %.1f%%".format(change) else "本次未减小体积"
            )
            selected.value = repo.registerPdf(file, doc.title + " · 图片压缩", listed = false)
            publishOutput(file, doc)
        }

    fun extractText() =
        perform("正在提取 PDF 文字") {
            ocrPage = null
            ocrVersions.value = emptyList()
            canCorrectOcr.value = false
            ocr.value =
                PdfEngine.text(File(selected.value!!.pdfPath!!)).ifBlank {
                    "这份 PDF 没有可提取文字。扫描图请使用离线 OCR。"
                }
        }

    val tableReview = MutableStateFlow<List<PhoneTables.Table>>(emptyList())
    private var tableOutputCsv = false

    fun phoneTables(uri: Uri, csv: Boolean, options: org.json.JSONObject) =
        perform("正在手机上识别表格") {
            val name = repo.displayName(uri)
            val input =
                File(
                    getApplication<Application>().cacheDir,
                    "table-${UUID.randomUUID()}.${name.substringAfterLast('.', "pdf")}",
                )
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { source ->
                    input.outputStream().use { source.copyTo(it) }
                }
                tableReview.value =
                    PhoneTables.extract(input, ocrEngine, options) { progress.value = it }
                tableOutputCsv = csv
                screen.value = "tables"
            } finally {
                input.delete()
            }
        }

    fun editTableCell(index: Int, row: Int, column: Int, text: String) {
        tableReview.value =
            tableReview.value.mapIndexed { i, table ->
                if (i != index) table
                else
                    table.copy(
                        rows =
                            table.rows.mapIndexed { r, values ->
                                values.mapIndexed { c, value ->
                                    if (r == row && c == column) text else value
                                }
                            },
                        cells =
                            table.cells.map {
                                if (it.row == row && it.column == column) it.copy(text = text)
                                else it
                            },
                    )
            }
    }

    fun exportPhoneTables(csv: Boolean = tableOutputCsv) =
        perform("正在导出可编辑表格") {
            val file =
                File(
                    repo.exports,
                    "PaperForge-tables-${System.currentTimeMillis()}.${if (csv) "zip" else "xlsx"}",
                )
            PhoneTables.write(tableReview.value, file, csv)
            outputLocked.value = false
            publishOutput(file)
        }

    fun officeResult(uri: Uri) =
        perform("正在接收手机 Office 结果") {
            val name = repo.displayName(uri)
            val file = File(repo.exports, "${System.currentTimeMillis()}-${safeFileName(name)}")
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { source ->
                    file.outputStream().use { source.copyTo(it) }
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
            if (file.extension.equals("pdf", true))
                repo.registerPdf(file, name.substringBeforeLast('.'))
            outputLocked.value = false
            publishOutput(file)
            runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
        }

    fun desktopTask(uri: Uri, mode: String, options: org.json.JSONObject = org.json.JSONObject()) =
        perform("正在打包电脑转换任务") {
            val file = File(repo.exports, "PaperForge-task-${System.currentTimeMillis()}.zip")
            DesktopTasks.create(getApplication(), uri, repo.displayName(uri), mode, file, options)
            outputLocked.value = false
            publishOutput(file)
            messages.send("将任务包交给电脑助手处理，再从转换中心导入结果包")
        }

    fun importDesktopResult(uri: Uri) =
        perform("正在导入电脑转换结果") {
            val files = DesktopTasks.import(getApplication(), uri, repo.exports)
            files
                .filter { it.extension.equals("pdf", true) }
                .forEach { repo.registerPdf(it, it.nameWithoutExtension) }
            outputLocked.value = false
            if (files.size == 1) publishOutput(files.first())
            else {
                val zip = File(repo.exports, "PaperForge-results-${System.currentTimeMillis()}.zip")
                java.util.zip.ZipOutputStream(zip.outputStream()).use { stream ->
                    files.forEach { file ->
                        stream.putNextEntry(java.util.zip.ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(stream) }
                        stream.closeEntry()
                    }
                }
                publishOutput(zip)
            }
            messages.send("已导入 ${files.size} 个结果；PDF 已加入文档库")
        }

    fun convertText(uri: Uri, size: Float) =
        perform("正在生成文字 PDF") {
            val name = repo.displayName(uri)
            val file =
                File(
                    repo.exports,
                    "${safeFileName(name.substringBeforeLast('.'))}-${System.currentTimeMillis()}.pdf",
                )
            cn.paperforge.app.pdf.LocalConversions.textToPdf(
                getApplication(),
                uri,
                name,
                file,
                size,
            )
            repo.registerPdf(file, name.substringBeforeLast('.') + " · 文字 PDF")
            outputLocked.value = false
            publishOutput(file)
        }

    fun convertImages(uris: List<Uri>, format: String) =
        perform("正在转换图片") {
            require(uris.isNotEmpty()) { "请先选择图片" }
            val extension = if (uris.size > 1) "zip" else format
            val file =
                File(repo.exports, "PaperForge-images-${System.currentTimeMillis()}.$extension")
            cn.paperforge.app.pdf.LocalConversions.images(getApplication(), uris, format, file) {
                progress.value = it
            }
            outputLocked.value = false
            publishOutput(file)
        }

    fun previewOutput(file: File) =
        perform("正在打开 PDF") {
            val doc = repo.registerPdf(file, file.nameWithoutExtension)
            selected.value = doc
            output.value = null
            screen.value = "reader"
        }

    fun queueTool(mode: String, options: org.json.JSONObject = org.json.JSONObject()) {
        if (busy.value) return
        val data =
            workDataOf(
                "mode" to mode,
                "document" to selected.value?.id,
                "options" to options.toString(),
                "sources" to mergeSources.value.mapNotNull { it.pdfPath }.toTypedArray(),
            )
        val request = OneTimeWorkRequestBuilder<PdfJobWorker>().setInputData(data).build()
        WorkManager.getInstance(getApplication()).enqueue(request)
        getApplication<Application>()
            .getSharedPreferences("session", 0)
            .edit()
            .putString("export", request.id.toString())
            .apply()
        watchExport(request.id)
    }

    fun encrypt(password: String, printing: Boolean, copying: Boolean) =
        perform("正在加密") {
            val doc = selected.value!!
            val file =
                File(
                    repo.exports,
                    "${safeFileName(doc.title)}-加密-${System.currentTimeMillis()}.pdf",
                )
            PdfTools.encrypt(File(doc.pdfPath!!), file, password, printing, copying)
            outputLocked.value = true
            publishOutput(file, doc)
        }

    fun forms() =
        perform("读取表单") {
            formFields.value = PdfTools.forms(File(selected.value!!.pdfPath!!))
            if (formFields.value!!.isEmpty()) {
                formFields.value = null
                messages.send("这份 PDF 没有可填写的 AcroForm 字段")
            }
        }

    fun fillForms(values: Map<String, String>) =
        perform("保存表单") {
            val doc = selected.value!!
            val file = File(repo.exports, "表单-${System.currentTimeMillis()}.pdf")
            PdfTools.fillForms(File(doc.pdfPath!!), file, values, getApplication())
            selected.value = repo.registerPdf(file, doc.title + " · 表单", listed = false)
            outputLocked.value = false
            publishOutput(file, doc)
            formFields.value = null
        }

    fun searchPdf(text: String) =
        perform("正在搜索文字") {
            searchHits.value = PdfSearch.find(File(selected.value!!.pdfPath!!), text)
        }

    fun reorder(order: List<PageRow>) =
        perform("保存页序") { selected.value?.let { repo.reorder(it.id, order) } }

    fun duplicate(page: PageRow) = perform("复制页面") { repo.duplicate(page) }

    fun splitBook(page: PageRow, top: Float = .5f, bottom: Float = .5f, keep: String = "both") =
        perform("分割双页") { repo.splitBook(page, top, bottom, keep) }

    fun applyAll(page: PageRow) =
        perform("应用到所有页") {
            repo.dao.pages(page.documentId).forEach {
                repo.savePage(
                    it.copy(
                        filter = page.filter,
                        brightness = page.brightness,
                        contrast = page.contrast,
                        enhanceStrength = page.enhanceStrength,
                    )
                )
            }
        }

    fun rotateMany(ids: Set<String>) =
        perform("批量旋转") {
            pages.value
                .filter { it.id in ids }
                .forEach { repo.savePage(it.copy(rotation = (it.rotation + 90) % 360)) }
        }

    fun deleteMany(ids: Set<String>) =
        perform("批量删除") { pages.value.filter { it.id in ids }.forEach { repo.deletePage(it) } }

    fun images(format: String) =
        perform("导出扫描图片") {
            val doc = selected.value!!
            val all = repo.dao.pages(doc.id)
            val file =
                File(repo.exports, "${safeFileName(doc.title)}-${System.currentTimeMillis()}.zip")
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                all.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = cn.paperforge.app.imaging.ScanProcessor.render(page, 2800)
                    try {
                        zip.putNextEntry(
                            java.util.zip.ZipEntry("page-%04d.%s".format(index + 1, format))
                        )
                        bitmap.compress(
                            if (format == "png") android.graphics.Bitmap.CompressFormat.PNG
                            else android.graphics.Bitmap.CompressFormat.JPEG,
                            94,
                            zip,
                        )
                        zip.closeEntry()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            outputLocked.value = false
            publishOutput(file, doc)
        }

    fun exportScanCase(page: PageRow) =
        perform("正在保存扫描诊断") {
            publishOutput(
                cn.paperforge.app.imaging.ScanDiagnostics.export(
                    getApplication(),
                    page,
                    repo.exports,
                ),
                selected.value,
            )
            outputLocked.value = false
        }

    fun exportStudyNotes(format: String) =
        perform("正在导出摘录") {
            publishOutput(
                cn.paperforge.app.reader.StudyNotes.export(getApplication(), repo.exports, format)
            )
            outputLocked.value = false
        }

    fun openDocumentLink(uri: Uri) {
        viewModelScope.launch {
            val doc =
                withContext(Dispatchers.IO) { uri.lastPathSegment?.let { repo.dao.document(it) } }
            if (doc == null) messages.send("来源文档已移除，摘录文字仍然保留")
            else {
                val reader = ReaderPreferences(getApplication())
                val page =
                    ((uri.getQueryParameter("page")?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
                reader.save(doc.id, reader.load(doc.id).copy(page = page))
                open(doc)
                readerTarget.value = doc.id to page
            }
        }
    }

    fun backup() =
        perform("正在离线备份") {
            val file = File(repo.exports, "PaperForge-backup-${System.currentTimeMillis()}.zip")
            BackupStore.backup(repo, file)
            outputLocked.value = false
            output.value = file
        }

    fun exportPreferences() =
        perform("导出个性化设置") {
            val file = File(repo.exports, "PaperForge-settings-${System.currentTimeMillis()}.json")
            file.writeText(UserSettings.exportJson(), Charsets.UTF_8)
            outputLocked.value = false
            output.value = file
        }

    fun importPreferences(uri: Uri) =
        perform("导入个性化设置") {
            val text =
                getApplication<Application>()
                    .contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() } ?: error("无法读取设置文件")
            val count = UserSettings.importJson(text)
            messages.send("已应用 $count 项个性化设置")
        }

    fun restoreBackup(uri: Uri) =
        perform("正在恢复备份") {
            val count = BackupStore.restore(repo, uri)
            canUndoNames.value = LibraryPreferences(repo.context).renameUndo().isNotEmpty()
            comparison.refreshFamilies()
            messages.send("已恢复 $count 份文档")
        }

    private fun markDraft() = File(repo.folder(selected.value!!.id), "annotations.json")

    fun beginMarks() =
        perform("读取批注草稿") {
            marks.value =
                runCatching { PdfMark.decode(org.json.JSONArray(markDraft().readText())) }
                    .getOrDefault(emptyList())
            screen.value = "annotations"
        }

    fun updateMarks(value: List<PdfMark>) {
        marks.value = value
        val file = markDraft()
        marksSave?.cancel()
        marksSave =
            viewModelScope.launch(Dispatchers.IO) {
                delay(250)
                file.writeText(org.json.JSONArray(value.map { it.json() }).toString())
            }
    }

    fun saveMarks() {
        if (busy.value || marks.value.isEmpty()) return
        val file = File(repo.exports, "marks-job-${UUID.randomUUID()}.json")
        file.writeText(org.json.JSONArray(marks.value.map { it.json() }).toString())
        queueTool("marks", org.json.JSONObject().put("marksFile", file.path))
    }
}

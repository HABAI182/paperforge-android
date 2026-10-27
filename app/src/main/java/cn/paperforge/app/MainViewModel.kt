package cn.paperforge.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
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
    val conversionEntry = MutableStateFlow("")
    val conversionReturn = MutableStateFlow("convert")

    fun openConversion(mode: String) {
        conversionEntry.value = mode
        screen.value = "convert"
    }

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
    val outputNotice = MutableStateFlow<Pair<String, String>?>(null)
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
    val assembly = PdfAssemblyController(repo, viewModelScope, messages)
    val assemblyReturn = MutableStateFlow("home")

    fun beginAssembly(ids: List<String> = emptyList(), fresh: Boolean = false) {
        outputReturn.value = null
        assemblyReturn.value =
            if (screen.value in listOf("reader", "pdf-pages")) "reader" else "home"
        if (
            fresh || ids.isNotEmpty() && assembly.plan.value.sources.none { it.documentId in ids }
        ) {
            assembly.cancelLoading()
            assembly.change(
                AssemblyPlan(title = selected.value?.takeIf { it.id in ids }?.title ?: "组合文档")
            )
        }
        screen.value = "assembly"
        if (ids.isNotEmpty()) assembly.addSources(ids)
    }

    fun importAssemblySources(uris: List<Uri>) =
        perform("正在导入组合来源") {
            val ids = mutableListOf<String>()
            val failures = mutableListOf<String>()
            uris.forEach { uri ->
                try {
                    ids += repo.importPdf(uri).id
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failures += error.message ?: "读取失败"
                }
            }
            if (ids.isNotEmpty()) assembly.addSources(ids)
            if (failures.isNotEmpty())
                messages.send("已读取 ${ids.size} 份，${failures.size} 份未能读取：${failures.first()}")
        }

    fun closeAssembly() {
        assembly.cancelLoading()
        viewModelScope.launch {
            runCatching { assembly.flush() }.onFailure { messages.send(it.message ?: "草稿未保存") }
        }
        screen.value = assemblyReturn.value
    }

    fun exportAssembly() {
        if (!busy.compareAndSet(false, true)) return
        progress.value = "正在准备页面组合"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                var manifest: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        assembly.flush()
                        val plan = assembly.plan.value
                        require(plan.pages.isNotEmpty() || plan.cover) { "请先加入页面" }
                        val directory =
                            File(repo.context.filesDir, "assembly-jobs").apply { mkdirs() }
                        val saved =
                            File(directory, "${UUID.randomUUID()}.json").also { manifest = it }
                        java.io.FileOutputStream(saved).use { stream ->
                            stream.write(plan.json().toString().toByteArray(Charsets.UTF_8))
                            stream.fd.sync()
                        }
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            val work =
                                DocumentJobs.enqueue(
                                    repo,
                                    "PDF",
                                    workDataOf(
                                        "mode" to "assemble",
                                        "document" to plan.chapters.firstOrNull()?.documentId,
                                        "options" to
                                            org.json
                                                .JSONObject()
                                                .put("manifest", saved.path)
                                                .put("title", plan.title)
                                                .toString(),
                                    ),
                                )
                            queued = work
                            getApplication<Application>()
                                .getSharedPreferences("session", 0)
                                .edit()
                                .putString("export", work.toString())
                                .apply()
                        }
                    }
                } catch (cancel: CancellationException) {
                    queued?.let { WorkManager.getInstance(getApplication()).cancelWorkById(it) }
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "组合任务未能创建")
                } finally {
                    if (queued == null) manifest?.delete()
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
    }

    val comparisonReturn = MutableStateFlow(false)
    val outputReturn = MutableStateFlow<String?>(null)

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
        val target = outputReturn.value
        screen.value = target ?: if (comparisonReturn.value) "comparison" else "home"
        outputReturn.value = null
        if (target == null) comparisonReturn.value = false
    }

    val replacement = MutableStateFlow<String?>(null)
    val mergeSources = MutableStateFlow<List<DocumentRow>>(emptyList())
    val passwordRequest = MutableStateFlow<Uri?>(null)
    val outputLocked = MutableStateFlow(false)
    val formFields = MutableStateFlow<List<Pair<String, String>>?>(null)
    val searchHits = MutableStateFlow<List<cn.paperforge.app.pdf.PdfSearch.Hit>>(emptyList())
    val annotationPage = MutableStateFlow(0)
    val readerTarget = MutableStateFlow<Pair<String, Int>?>(null)
    val readerLocation = MutableStateFlow<Pair<String, ReaderDestination>?>(null)
    val editorTarget = MutableStateFlow<Pair<String, Int>?>(null)
    val editorPageTarget = MutableStateFlow<Pair<String, String>?>(null)
    val libraryFocus = MutableStateFlow<LibrarySearchHit?>(null)
    val outputSource = MutableStateFlow<String?>(null)
    val profileReturn = MutableStateFlow("settings")
    val ocrModelsReturn = MutableStateFlow("settings")

    fun openOcrModels() {
        ocrModelsReturn.value = screen.value
        screen.value = "ocr-models"
    }

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
    private var exportWatch: Job? = null
    val canBackgroundTask = MutableStateFlow(false)
    val taskFilter = MutableStateFlow("ACTIVE")
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

    private suspend fun publishOutput(
        file: File,
        source: DocumentRow? = null,
        notice: String = "",
    ) {
        withContext(Dispatchers.IO) { repo.recordOutput(file, source) }
        outputNotice.value = file.path to notice
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

    fun continueReading() =
        viewModelScope.launch {
            val recent =
                withContext(Dispatchers.IO) {
                    cn.paperforge.app.system.SystemEntrypoints.latestReadable(repo)
                }
            if (recent != null) open(recent)
            else {
                screen.value = "home"
                messages.send("还没有可继续阅读的 PDF，可先扫描或打开文件")
            }
        }

    fun open(doc: DocumentRow) {
        if (busy.value) return
        comparisonReturn.value = false
        outputReturn.value = null
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
        if (
            BatchConversions.batchId(row).isNotBlank() &&
                !BatchConversions.input(row).optBoolean("draft")
        ) {
            batches.retry(row.id)
            return
        }
        if (
            row.type == "CONVERT" &&
                org.json.JSONObject(row.payload).optJSONObject("input")?.optBoolean("draft") == true
        ) {
            loadConversionDraft(row)
            return
        }
        appScope.launch {
            runCatching {
                    if (row.type in DocumentJobs.types) {
                        val latest = repo.dao.task(row.id) ?: error("任务已移除")
                        val payload = org.json.JSONObject(latest.payload)
                        val input = payload.optJSONObject("input")
                        if (row.type == "AUTO_EXPORT") {
                            input?.put("manual", true)?.put("delaySeconds", 0)
                            repo.dao.putTask(latest.copy(payload = payload.toString()))
                        }
                        if (input?.has("ocrRequest") == true) {
                            input.put(
                                "ocrRequest",
                                cn.paperforge.app.ocr.OcrRequest.current(
                                        (repo.context.applicationContext as PaperForgeApp)
                                            .ocrEngine
                                            .id
                                    )
                                    .json(),
                            )
                            repo.dao.putTask(latest.copy(payload = payload.toString()))
                        }
                        DocumentJobs.retry(repo, latest)
                    } else cn.paperforge.app.imaging.CaptureTasks.accept(repo, row.id, retry = true)
                }
                .onFailure { messages.send(it.message ?: "重试未完成") }
        }
    }

    fun cancelLocalTask(row: LocalTaskRow) {
        appScope.launch {
            if (row.type in DocumentJobs.types) DocumentJobs.cancel(repo, row)
            else cn.paperforge.app.imaging.CaptureTasks.cancel(repo, row)
        }
    }

    fun forgetTask(row: LocalTaskRow) {
        appScope.launch {
            runCatching { DocumentJobs.forget(repo, row.id) }
                .onFailure { messages.send(it.message ?: "记录未能移除") }
        }
    }

    private fun loadConversionDraft(row: LocalTaskRow) {
        when (org.json.JSONObject(row.payload).getJSONObject("input").optString("kind")) {
            "BATCH_ITEM" -> {
                batches.selected.value = BatchConversions.batchId(row)
                screen.value = "conversion-batch"
            }
            "LONG_IMAGES" -> longImages.open(row)
            "TABLE_REVIEW" -> perform("打开表格草稿") { tableWorkspace.open(row) }
            else -> loadTextConversion(row)
        }
    }

    fun openTask(row: LocalTaskRow, retake: Boolean = false) {
        if (
            row.type == "CONVERT" &&
                org.json.JSONObject(row.payload).getJSONObject("input").optString("kind") ==
                    "CONVERSION_PREVIEW"
        ) {
            showConversionPreview(row)
            return
        }
        conversionReturn.value =
            if (BatchConversions.batchId(row).isNotBlank()) "conversion-batch" else "convert"
        if (BatchConversions.batchId(row).isNotBlank())
            batches.selected.value = BatchConversions.batchId(row)
        if (BatchConversions.member(row) && row.resourceEntries().none { it.id == "result" }) {
            batches.selected.value = BatchConversions.batchId(row)
            screen.value = "conversion-batch"
            return
        }
        if (
            row.type == "CONVERT" &&
                org.json.JSONObject(row.payload).optJSONObject("input")?.optBoolean("draft") == true
        ) {
            loadConversionDraft(row)
            return
        }
        viewModelScope.launch {
            if (row.type in DocumentJobs.types) {
                val file =
                    row.resourceEntries()
                        .firstOrNull { it.id == "result" || it.id == "save-source" }
                        ?.path
                        ?.let(::File)
                        ?.takeIf { it.isFile }
                if (file != null) {
                    if (
                        row.type == "CONVERT" &&
                            org.json
                                .JSONObject(row.payload)
                                .getJSONObject("input")
                                .optString("kind") == "MARKUP"
                    ) {
                        openMarkupResult(row)
                    } else if (
                        row.type == "CONVERT" &&
                            org.json
                                .JSONObject(row.payload)
                                .getJSONObject("input")
                                .optString("kind") == "TABLE_ANALYZE"
                    ) {
                        tableWorkspace.result(row)
                    } else if (
                        row.type == "CONVERT" &&
                            org.json
                                .JSONObject(row.payload)
                                .getJSONObject("input")
                                .optString("kind") == "PDF_DIFF"
                    ) {
                        openPdfDiffResult(row)
                    } else if (row.type == "OCR") {
                        ocrPage = null
                        canCorrectOcr.value = false
                        ocrVersions.value = emptyList()
                        ocr.value = withContext(Dispatchers.IO) { file.readText() }
                    } else if (file.extension == "pdf" && row.type != "SAVE") previewOutput(file)
                    else output.value = file
                    return@launch
                }
            }
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

    fun exportWithOptions(
        title: String,
        pages: Int,
        options: cn.paperforge.app.model.ScanExportOptions,
    ) {
        val doc = selected.value ?: return
        enqueueJob(
            "SCAN_EXPORT",
            workDataOf(
                "document" to doc.id,
                "paper" to options.paper,
                "quality" to options.quality,
                "title" to title,
                "fileName" to options.fileName(title, pages),
                "searchable" to (options.searchable && options.format == "PDF"),
                "resolution" to options.effectiveResolution,
                "format" to options.format,
            ),
        )
    }

    fun rememberOutputDirectory(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                    withContext(Dispatchers.IO) { ExportDestinations(repo.context).remember(uri) }
                }
                .onSuccess { destination ->
                    UserSettings.set("exportDestination", destination.id)
                    saveOutputToDirectory(destination.id)
                }
                .onFailure { messages.send(it.message ?: "无法授权保存目录，请重新选择") }
        }
    }

    fun saveOutputToDirectory(
        destinationId: String = UserSettings.string("exportDestination", "")
    ) {
        val file = output.value ?: return
        val store = ExportDestinations(repo.context)
        val destination = store.get(destinationId)
        if (destination == null || !store.available(destination)) {
            viewModelScope.launch { messages.send("目录授权已失效，请重新选择；本地成品保留") }
            return
        }
        enqueueJob("SAVE", workDataOf("path" to file.path, "treeUri" to destination.uri.toString()))
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
        enqueueJob(
            "SCAN_EXPORT",
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
            ),
        )
    }

    private fun enqueueJob(type: String, data: Data) = enqueueJob(type, JobInput.from(data))

    private fun enqueueJob(type: String, data: JobInput) {
        if (busy.value) return
        busy.value = true
        progress.value = "正在保存任务"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    queued = DocumentJobs.enqueue(repo, type, data)
                    getApplication<Application>()
                        .getSharedPreferences("session", 0)
                        .edit()
                        .putString("export", queued.toString())
                        .apply()
                } catch (failure: Exception) {
                    messages.send(failure.message ?: "任务未能创建")
                } finally {
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
    }

    private fun watchExport(id: UUID) {
        getApplication<Application>()
            .getSharedPreferences("session", 0)
            .edit()
            .putString("export", id.toString())
            .apply()
        exportId = id
        busy.value = true
        exportWatch?.cancel()
        canBackgroundTask.value = true
        exportWatch =
            viewModelScope.launch {
                WorkManager.getInstance(getApplication())
                    .getWorkInfoByIdFlow(id)
                    .filterNotNull()
                    .takeWhile { info ->
                        val detail =
                            localTasks.value.firstOrNull { it.workId == id.toString() }?.detail
                                ?: "处理文件"
                        progress.value = "$detail · ${info.progress.getInt("progress",0)}%"
                        if (info.state.isFinished) {
                            busy.value = false
                            progress.value = ""
                            exportId = null
                            canBackgroundTask.value = false
                            getApplication<Application>()
                                .getSharedPreferences("session", 0)
                                .edit()
                                .remove("export")
                                .apply()
                            if (info.state == WorkInfo.State.SUCCEEDED) {
                                if (info.outputData.getBoolean("skipped", false)) {
                                    messages.send(
                                        info.outputData.getString("detail") ?: "已由较新的任务接续"
                                    )
                                    return@takeWhile false
                                }
                                outputLocked.value = false
                                val file =
                                    File(info.outputData.getString("path") ?: error("任务没有本地结果"))
                                when (info.outputData.getString("mode")) {
                                    "OCR" -> {
                                        ocrPage = null
                                        ocrVersions.value = emptyList()
                                        canCorrectOcr.value = false
                                        ocr.value = withContext(Dispatchers.IO) { file.readText() }
                                    }
                                    "TABLE_REVIEW" -> {
                                        val row =
                                            repo.dao.task(info.outputData.getString("taskId") ?: "")
                                                ?: error("表格记录已移除")
                                        withContext(Dispatchers.IO) { tableWorkspace.result(row) }
                                    }
                                    "MARKUP" -> {
                                        val row =
                                            repo.dao.task(info.outputData.getString("taskId") ?: "")
                                                ?: error("转换记录已移除")
                                        openMarkupResult(row)
                                    }
                                    "PDF_DIFF" -> {
                                        val row =
                                            checkNotNull(
                                                repo.dao.task(
                                                    info.outputData.getString("taskId") ?: ""
                                                )
                                            )
                                        openPdfDiffResult(row)
                                    }
                                    "SAVE" -> messages.send("文件已保存并核对")
                                    else -> {
                                        val target = info.outputData.getLong("targetBytes", 0)
                                        val notice =
                                            if (target > 0)
                                                "目标 $target 字节 · 实际 ${file.length()} 字节 · ${if(info.outputData.getBoolean("targetAchieved",false)) "已达到目标" else "未达到目标，保留可用结果"}"
                                            else ""
                                        publishOutput(file, notice = notice)
                                        if (info.outputData.getBoolean("partial", false))
                                            messages.send(
                                                "${info.outputData.getInt("completed",0)} 项完成，${info.outputData.getInt("failed",0)} 项失败；可在任务中心只重试失败项"
                                            )
                                    }
                                }
                                if (
                                    info.outputData.getString("mode") in
                                        listOf("compress", "optimize")
                                )
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

    fun backgroundJob() {
        if (!canBackgroundTask.value) return
        exportWatch?.cancel()
        exportWatch = null
        exportId = null
        canBackgroundTask.value = false
        busy.value = false
        progress.value = ""
        getApplication<Application>()
            .getSharedPreferences("session", 0)
            .edit()
            .remove("export")
            .apply()
        screen.value = "tasks"
    }

    fun cancel() {
        exportId?.let { id ->
            WorkManager.getInstance(getApplication()).cancelWorkById(id)
            appScope.launch {
                repo.dao
                    .allTasks()
                    .firstOrNull { it.workId == id.toString() }
                    ?.let { DocumentJobs.cancel(repo, it) }
            }
        }
        task?.cancel()
    }

    fun saveOutput(uri: Uri) {
        val file = output.value ?: return
        runCatching {
            repo.context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        enqueueJob("SAVE", workDataOf("path" to file.path, "uri" to uri.toString()))
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

    fun recognizeAll() {
        val doc = selected.value ?: return
        enqueueJob(
            "OCR",
            workDataOf(
                "document" to doc.id,
                "chinese" to (UserSettings.string("ocrLanguage", "CHINESE") != "LATIN"),
            ),
        )
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
        queueTool("extract", org.json.JSONObject().put("range", range).put("rotation", rotate))

    fun compress(quality: Int) =
        queueTool("compress", org.json.JSONObject().put("quality", quality))

    fun compressTarget(bytes: Long) =
        queueTool("compress", org.json.JSONObject().put("targetBytes", bytes))

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

    fun exportOcrStructure(page: PageRow) =
        perform("正在导出结构数据") {
            val current =
                repo.dao.pages(page.documentId).firstOrNull { it.id == page.id } ?: error("页面已移除")
            val layout =
                current.ocrLayout?.let(cn.paperforge.app.model.OcrLayout::decode) ?: error("请先识别本页")
            require(layout.matches(current)) { "页面已修改，请重新识别" }
            val file =
                File(
                    repo.exports,
                    "PaperForge-page-${current.position + 1}-${System.currentTimeMillis()}.ocr.json",
                )
            file.writeText(cn.paperforge.app.model.OcrStructure.json(layout).toString(2))
            repo.recordOutput(file, repo.dao.document(current.documentId))
            outputLocked.value = false
            publishOutput(file)
        }

    val batches = ConversionBatches(repo, viewModelScope) { messages.send(it) }

    val conversionPreviewTask = MutableStateFlow("")
    val conversionPreviewReturn = MutableStateFlow("convert")

    private fun showConversionPreview(row: LocalTaskRow) {
        if (screen.value != "conversion-preview") conversionPreviewReturn.value = screen.value
        conversionPreviewTask.value = row.id
        output.value = null
        screen.value = "conversion-preview"
    }

    fun compareConversion(row: LocalTaskRow) =
        perform("准备原件与实际结果对照") { showConversionPreview(ConversionPreviews.prepare(repo, row)) }

    val tableWorkspace =
        TableWorkspace(
            repo,
            viewModelScope,
            { screen.value = it },
            ::watchExport,
            { messages.send(it) },
        )

    fun phoneTables(uri: Uri, csv: Boolean, options: org.json.JSONObject) {
        if (!busy.compareAndSet(false, true)) return
        progress.value = "保留表格原件"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    queued =
                        withContext(Dispatchers.IO) {
                            val retained = ConversionInputs.retain(repo, listOf(uri)).single()
                            tableWorkspace.analyze(
                                retained.first,
                                retained.second,
                                org.json.JSONObject(options.toString()).put("outputCsv", csv),
                                notify = false,
                            )
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "表格未能开始识别")
                } finally {
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
    }

    fun phoneOffice(uri: Uri, format: String) {
        if (!busy.compareAndSet(false, true)) return
        progress.value = "保留 Office 原件"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    queued =
                        withContext(Dispatchers.IO) {
                            val retained = ConversionInputs.retain(repo, listOf(uri)).single()
                            DocumentJobs.enqueue(
                                repo,
                                "CONVERT",
                                JobInput(
                                    org.json
                                        .JSONObject()
                                        .put("kind", "OFFICE")
                                        .put("name", retained.second)
                                        .put(
                                            "inputs",
                                            org.json.JSONArray(listOf(retained.first.path)),
                                        )
                                        .put("names", org.json.JSONArray(listOf(retained.second)))
                                        .put(
                                            "options",
                                            org.json.JSONObject().put("format", format).toString(),
                                        )
                                ),
                            )
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "Office 转换未能开始")
                } finally {
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
    }

    val formatTarget = MutableStateFlow("PDF")
    val formatDpi = MutableStateFlow(200)

    fun convertLayout(uri: Uri, options: org.json.JSONObject) {
        if (!busy.compareAndSet(false, true)) return
        progress.value = "保留版式原件"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    queued =
                        withContext(Dispatchers.IO) {
                            val retained = ConversionInputs.retain(repo, listOf(uri)).single()
                            DocumentJobs.enqueue(
                                repo,
                                "CONVERT",
                                JobInput(
                                    org.json
                                        .JSONObject()
                                        .put("kind", "LAYOUT")
                                        .put("name", retained.second)
                                        .put(
                                            "inputs",
                                            org.json.JSONArray(listOf(retained.first.path)),
                                        )
                                        .put("names", org.json.JSONArray(listOf(retained.second)))
                                        .put("options", options.toString())
                                ),
                            )
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "版式转换未能开始")
                } finally {
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
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

    val textConversion = MutableStateFlow<TextConversionDraft?>(null)
    val workflowDraft = MutableStateFlow(cn.paperforge.app.model.WorkflowRecipe())
    val autoExportDraft = MutableStateFlow<cn.paperforge.app.model.AutoExportRule?>(null)

    fun saveAutoExportRule(rule: cn.paperforge.app.model.AutoExportRule) =
        perform("保存自动导出规则") {
            if (rule.enabled) rule.issue()?.let { error(it) }
            cn.paperforge.app.model.AutoExportRules.save(rule)
            if (!rule.enabled)
                repo.dao
                    .allTasks()
                    .filter {
                        it.type == "AUTO_EXPORT" &&
                            it.state in LocalTaskRow.active &&
                            org.json
                                .JSONObject(it.payload)
                                .getJSONObject("input")
                                .optString("automationRule") == rule.id
                    }
                    .forEach { DocumentJobs.cancel(repo, it) }
            autoExportDraft.value = null
        }

    fun removeAutoExportRule(rule: cn.paperforge.app.model.AutoExportRule) =
        perform("删除自动导出规则") {
            cn.paperforge.app.model.AutoExportRules.remove(rule.id)
            repo.dao
                .allTasks()
                .filter {
                    it.type == "AUTO_EXPORT" &&
                        it.state in LocalTaskRow.active &&
                        org.json
                            .JSONObject(it.payload)
                            .getJSONObject("input")
                            .optString("automationRule") == rule.id
                }
                .forEach { DocumentJobs.cancel(repo, it) }
            if (autoExportDraft.value?.id == rule.id) autoExportDraft.value = null
        }

    fun rememberAutoExportDirectory(uri: Uri) =
        perform("记住规则保存目录") {
            val destination = ExportDestinations(repo.context).remember(uri)
            val rule = autoExportDraft.value ?: return@perform
            val last = rule.recipe.steps.indexOfLast { it.operation == "save" }
            val steps = rule.recipe.steps.toMutableList()
            if (last < 0)
                steps +=
                    cn.paperforge.app.model.RecipeStep(
                        operation = "save",
                        options = org.json.JSONObject().put("destination", destination.id),
                    )
            else
                steps[last] =
                    steps[last].copy(
                        options =
                            org.json
                                .JSONObject(steps[last].options.toString())
                                .put("destination", destination.id)
                    )
            autoExportDraft.value = rule.copy(recipe = rule.recipe.copy(steps = steps))
        }

    fun runAutoExportRule(rule: cn.paperforge.app.model.AutoExportRule) =
        perform("提交匹配文档") {
            var count = 0
            for (signal in
                AutoExportCoordinator.signals(repo).first().filter {
                    it.ready && rule.matches(it.document)
                }) if (AutoExportCoordinator.enqueue(repo, rule, signal, force = true) != null)
                count++
            messages.send("已提交 $count 份文档，结果见自动导出记录；尚在处理的文档请完成后再运行")
        }

    fun retryAutoExport(row: LocalTaskRow) {
        appScope.launch {
            runCatching {
                    val updated =
                        repo.database.withTransaction {
                            val latest = checkNotNull(repo.dao.task(row.id))
                            require(latest.state !in LocalTaskRow.active) { "任务正在运行" }
                            val payload = org.json.JSONObject(latest.payload)
                            payload
                                .getJSONObject("input")
                                .put("manual", true)
                                .put("delaySeconds", 0)
                            latest.copy(payload = payload.toString()).also { repo.dao.putTask(it) }
                        }
                    DocumentJobs.retry(repo, updated)
                }
                .onFailure { messages.send(it.message ?: "重试未能开始") }
        }
    }

    val workflowRun = MutableStateFlow<String?>(null)
    val workflowDirectoryStep = MutableStateFlow<String?>(null)

    fun showWorkflowStage(file: File) = perform("打开步骤结果") { publishOutput(file) }

    fun editWorkflowRun(row: LocalTaskRow) =
        perform("载入配方与已完成步骤") {
            workflowDraft.value =
                cn.paperforge.app.model.WorkflowRecipe.decode(
                    org.json
                        .JSONObject(
                            org.json
                                .JSONObject(row.payload)
                                .getJSONObject("input")
                                .getString("options")
                        )
                        .getJSONObject("recipe")
                )
            workflowRun.value = row.id
            screen.value = "workflows"
        }

    fun rememberWorkflowDirectory(uri: Uri) =
        perform("记住工作流保存目录") {
            val destination = ExportDestinations(repo.context).remember(uri)
            val id = workflowDirectoryStep.value
            val current = workflowDraft.value
            workflowDraft.value =
                current.copy(
                    steps =
                        current.steps.map {
                            if (it.id != id) it
                            else
                                it.copy(
                                    options =
                                        org.json
                                            .JSONObject(it.options.toString())
                                            .put("destination", destination.id)
                                )
                        }
                )
            workflowDirectoryStep.value = null
        }

    fun runWorkflow(uri: Uri? = null) {
        val recipe = cn.paperforge.app.model.WorkflowRecipe.decode(workflowDraft.value.json())
        recipe.issue()?.let { issue ->
            messages.trySend(issue)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        progress.value = "准备工作流"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    queued =
                        withContext(Dispatchers.IO) {
                            val previous = workflowRun.value?.let { repo.dao.task(it) }
                            if (previous == null) {
                                val retained =
                                    ConversionInputs.retain(
                                            repo,
                                            listOf(requireNotNull(uri) { "请选择输入文件" }),
                                        )
                                        .single()
                                val name =
                                    uri?.path?.let { repo.dao.documentByPath(it)?.title }
                                        ?: retained.second
                                DocumentJobs.enqueue(
                                        repo,
                                        "CONVERT",
                                        JobInput(
                                            org.json
                                                .JSONObject()
                                                .put("kind", "RECIPE")
                                                .put("name", name)
                                                .put(
                                                    "inputs",
                                                    org.json.JSONArray(listOf(retained.first.path)),
                                                )
                                                .put("names", org.json.JSONArray(listOf(name)))
                                                .put(
                                                    "options",
                                                    org.json
                                                        .JSONObject()
                                                        .put("recipe", recipe.json())
                                                        .toString(),
                                                )
                                        ),
                                    )
                                    .also { work ->
                                        workflowRun.value =
                                            repo.dao
                                                .allTasks()
                                                .first { it.workId == work.toString() }
                                                .id
                                    }
                            } else {
                                val updated =
                                    repo.database.withTransaction {
                                        val latest = checkNotNull(repo.dao.task(previous.id))
                                        require(latest.state !in LocalTaskRow.active) { "此配方正在运行" }
                                        val payload = org.json.JSONObject(latest.payload)
                                        if (latest.type == "AUTO_EXPORT") {
                                            payload
                                                .getJSONObject("input")
                                                .put("manual", true)
                                                .put("delaySeconds", 0)
                                            payload
                                                .getJSONObject("input")
                                                .optJSONObject("autoRule")
                                                ?.put("recipe", recipe.json())
                                        }
                                        payload
                                            .getJSONObject("input")
                                            .put(
                                                "options",
                                                org.json
                                                    .JSONObject()
                                                    .put("recipe", recipe.json())
                                                    .toString(),
                                            )
                                        latest.copy(payload = payload.toString()).also {
                                            repo.dao.putTask(it)
                                        }
                                    }
                                DocumentJobs.retry(repo, updated)
                            }
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "工作流未能开始")
                } finally {
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
    }

    val pdfDiffReview = MutableStateFlow<PdfDiffReview?>(null)
    val pdfDiffPosition = MutableStateFlow<Pair<Int, String?>>(0 to null)
    val pdfDiffOptions = MutableStateFlow(PdfDiffOptions())
    val pdfDiffInputs = MutableStateFlow<List<Uri>>(emptyList())

    fun startPdfComparison(uris: List<Uri>, options: PdfDiffOptions) {
        if (uris.size != 2 || !busy.compareAndSet(false, true)) return
        progress.value = "保留两份 PDF 原件"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    queued =
                        withContext(Dispatchers.IO) {
                            val files = ConversionInputs.retain(repo, uris)
                            val library = repo.documents.first().associateBy { it.pdfPath }
                            val names =
                                files.mapIndexed { index, source ->
                                    library[uris[index].path]?.title ?: source.second
                                }
                            DocumentJobs.enqueue(
                                repo,
                                "CONVERT",
                                JobInput(
                                    org.json
                                        .JSONObject()
                                        .put("kind", "PDF_DIFF")
                                        .put("name", "文档比较")
                                        .put(
                                            "inputs",
                                            org.json.JSONArray(files.map { it.first.path }),
                                        )
                                        .put("names", org.json.JSONArray(names))
                                        .put("options", options.json().toString())
                                ),
                            )
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "未能开始比较")
                } finally {
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
    }

    private suspend fun openPdfDiffResult(row: LocalTaskRow) {
        if (pdfDiffReview.value?.taskId != row.id) pdfDiffPosition.value = 0 to null
        pdfDiffReview.value = withContext(Dispatchers.IO) { PdfDiffReview.open(repo, row) }
        screen.value = "pdf-diff"
    }

    fun ignorePdfDifference(page: PdfDiffPage, finding: PdfDifference) =
        perform("保存核对结果") { pdfDiffReview.value = pdfDiffReview.value?.toggle(repo, page, finding) }

    fun exportPdfComparison() =
        perform("生成比较报告") {
            val review = pdfDiffReview.value ?: return@perform
            val file = File(repo.exports, "PaperForge-comparison-${System.currentTimeMillis()}.zip")
            PdfComparison.writeArchive(
                file,
                review.directory,
                review.report,
                listOf("left.pdf", "right.pdf").map { File(review.directory, it) },
            )
            publishOutput(file)
        }

    fun openPdfDifferenceSource(left: Boolean, page: PdfDiffPage, finding: PdfDifference?) =
        perform("定位原 PDF") {
            val review = pdfDiffReview.value ?: return@perform
            val index = (if (left) page.leftPage else page.rightPage) ?: return@perform
            val file = File(review.directory, if (left) "left.pdf" else "right.pdf")
            val document =
                markupPdfDocuments[file.path]
                    ?.let { repo.dao.document(it) }
                    ?.takeIf { it.trashedAt == null }
                    ?: repo.importPdf(Uri.fromFile(file)).let {
                        markupPdfDocuments[file.path] = it.id
                        val name = review.report.getJSONArray("names").getString(if (left) 0 else 1)
                        repo.rename(it, name)
                        it.copy(title = name)
                    }
            repo.dao.opened(document.id, System.currentTimeMillis())
            selected.value = document
            searchHits.value = emptyList()
            comparisonReturn.value = false
            outputReturn.value = "pdf-diff"
            readerTarget.value = null
            val rect =
                (if (left) finding?.left else finding?.right)?.let {
                    val margin = maxOf(.025f, (it.right - it.left) * .15f)
                    DiffRect(
                        (it.left - margin).coerceAtLeast(0f),
                        (it.top - .025f).coerceAtLeast(0f),
                        (it.right + margin).coerceAtMost(1f),
                        (it.bottom + .025f).coerceAtMost(1f),
                    )
                }
            readerLocation.value =
                document.id to
                    if (rect == null) ReaderDestination(index, "Fit")
                    else
                        ReaderDestination(
                            index,
                            "FitR",
                            rect.left,
                            rect.top,
                            rect.right,
                            rect.bottom,
                        )
            screen.value = "reader"
        }

    val markupReview = MutableStateFlow<MarkupReview?>(null)
    private val markupPdfDocuments = mutableMapOf<String, String>()

    fun openMarkupPdf(file: File, page: Int) =
        perform("打开原 PDF") {
            val previous =
                markupPdfDocuments[file.path]
                    ?.let { repo.dao.document(it) }
                    ?.takeIf { it.trashedAt == null }
            val document =
                previous
                    ?: repo.importPdf(Uri.fromFile(file)).also {
                        markupPdfDocuments[file.path] = it.id
                    }
            repo.dao.opened(document.id, System.currentTimeMillis())
            selected.value = document
            searchHits.value = emptyList()
            comparisonReturn.value = false
            outputReturn.value = "markup-review"
            readerTarget.value = document.id to page.coerceAtLeast(0)
            screen.value = "reader"
        }

    private fun openMarkupResult(row: LocalTaskRow) {
        conversionReturn.value =
            if (BatchConversions.batchId(row).isNotBlank()) "conversion-batch" else "convert"
        val input = org.json.JSONObject(row.payload).getJSONObject("input")
        markupReview.value =
            MarkupReview(
                input.getJSONArray("inputs").getString(0),
                row.resourceEntries().first { it.id == "result" }.path,
                input.optString("name", "文档"),
            )
        screen.value = "markup-review"
    }

    fun showMarkupOutput() {
        markupReview.value?.let {
            outputNotice.value = null
            outputLocked.value = false
            outputSource.value = null
            output.value = File(it.result)
        }
    }

    fun convertMarkup(
        uri: Uri,
        target: String,
        settings: org.json.JSONObject = org.json.JSONObject(),
    ) {
        if (!busy.compareAndSet(false, true)) return
        progress.value = "保留文档原件"
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    queued =
                        withContext(Dispatchers.IO) {
                            val retained = ConversionInputs.retain(repo, listOf(uri)).single()
                            DocumentJobs.enqueue(
                                repo,
                                "CONVERT",
                                JobInput(
                                    org.json
                                        .JSONObject()
                                        .put("kind", "MARKUP")
                                        .put("name", retained.second)
                                        .put(
                                            "inputs",
                                            org.json.JSONArray(listOf(retained.first.path)),
                                        )
                                        .put("names", org.json.JSONArray(listOf(retained.second)))
                                        .put(
                                            "options",
                                            org.json
                                                .JSONObject(settings.toString())
                                                .put("target", target)
                                                .put(
                                                    "print",
                                                    TextPdfOptions(
                                                            paper =
                                                                UserSettings.string("paper", "A4")
                                                        )
                                                        .json(),
                                                )
                                                .toString(),
                                        )
                                ),
                            )
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "文档未能开始转换")
                } finally {
                    busy.value = false
                    progress.value = ""
                }
                queued?.let(::watchExport)
            }
    }

    private var textConversionSave: Job? = null
    private val textConversionLock = kotlinx.coroutines.sync.Mutex()

    fun convertText(uri: Uri, size: Float) = convertText(uri, TextPdfOptions(fontSize = size))

    fun convertText(uri: Uri, options: TextPdfOptions) =
        perform("读取正文与保留原件") {
            conversionReturn.value = "convert"
            val retained = ConversionInputs.retain(repo, listOf(uri)).single()
            val text =
                LocalConversions.readText(
                    repo.context,
                    Uri.fromFile(retained.first),
                    retained.second,
                )
            val edited = File(retained.first.parentFile, "${UUID.randomUUID()}.txt")
            edited.writeText(text, Charsets.UTF_8)
            val input =
                org.json
                    .JSONObject()
                    .put("kind", "TEXT_PDF")
                    .put("name", retained.second)
                    .put("draft", true)
                    .put("inputs", org.json.JSONArray(listOf(retained.first.path, edited.path)))
                    .put("names", org.json.JSONArray(listOf(retained.second, "edited.txt")))
                    .put("edited", edited.path)
                    .put("options", options.json().toString())
            val row = DocumentJobs.prepare(repo, "CONVERT", JobInput(input))
            repo.dao.putTask(row.copy(state = LocalTaskState.PARTIAL, detail = "正文待编辑"))
            textConversion.value =
                TextConversionDraft(
                    row.id,
                    retained.second,
                    edited.path,
                    TextConversionDraft.chunks(text),
                    options,
                )
            screen.value = "text-compose"
        }

    fun loadTextConversion(row: LocalTaskRow) =
        perform("打开正文草稿") {
            textConversionSave?.join()
            val input = org.json.JSONObject(row.payload).getJSONObject("input")
            val file = File(input.getString("edited"))
            textConversion.value =
                TextConversionDraft(
                    row.id,
                    input.optString("name", "正文"),
                    file.path,
                    TextConversionDraft.chunks(file.readText()),
                    TextPdfOptions.decode(org.json.JSONObject(input.optString("options", "{}"))),
                )
            screen.value = "text-compose"
        }

    fun updateTextConversion(
        index: Int? = null,
        text: String? = null,
        options: TextPdfOptions? = null,
    ) {
        val old = textConversion.value ?: return
        val next =
            old.copy(
                chunks =
                    if (index != null && text != null)
                        old.chunks.mapIndexed { i, value -> if (i == index) text else value }
                    else old.chunks,
                options = options ?: old.options,
                revision = old.revision + 1,
            )
        textConversion.value = next
        textConversionSave?.cancel()
        textConversionSave =
            viewModelScope.launch(Dispatchers.IO) {
                delay(250)
                textConversionLock.lock()
                try {
                    if (
                        textConversion.value?.revision != next.revision ||
                            textConversion.value?.taskId != next.taskId
                    )
                        return@launch
                    val atomic = android.util.AtomicFile(File(next.editedPath))
                    val stream = atomic.startWrite()
                    try {
                        next.chunks.forEach {
                            ensureActive()
                            stream.write(it.toByteArray(Charsets.UTF_8))
                        }
                        atomic.finishWrite(stream)
                    } catch (failure: Throwable) {
                        atomic.failWrite(stream)
                        throw failure
                    }
                    repo.database.withTransaction {
                        val row = repo.dao.task(next.taskId) ?: return@withTransaction
                        val payload = org.json.JSONObject(row.payload)
                        payload
                            .getJSONObject("input")
                            .put("options", next.options.json().toString())
                        repo.dao.putTask(
                            row.copy(
                                payload = payload.toString(),
                                updated = System.currentTimeMillis(),
                            )
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    messages.send("正文尚未写入草稿：${failure.message}")
                } finally {
                    textConversionLock.unlock()
                }
            }
    }

    fun generateTextConversion() {
        if (busy.value) return
        val draft = textConversion.value ?: return
        busy.value = true
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    textConversionSave?.join()
                    withContext(Dispatchers.IO) {
                        val row = repo.dao.task(draft.taskId) ?: error("正文草稿已移除")
                        val frozen =
                            File(File(draft.editedPath).parentFile, "${UUID.randomUUID()}.txt")
                        java.io.FileOutputStream(frozen).use { stream ->
                            draft.chunks.forEach { stream.write(it.toByteArray(Charsets.UTF_8)) }
                            stream.fd.sync()
                        }
                        val payload = org.json.JSONObject(row.payload)
                        val input = payload.getJSONObject("input")
                        input
                            .put("draft", false)
                            .put("edited", frozen.path)
                            .put("options", draft.options.json().toString())
                        val originals = input.getJSONArray("inputs")
                        originals.put(frozen.path)
                        repo.dao.putTask(
                            row.copy(
                                payload = payload.toString(),
                                resources =
                                    DocumentResources.encode(
                                            row.resourceEntries() +
                                                PageResource(
                                                    "frozen",
                                                    "conversion-source",
                                                    frozen.path,
                                                )
                                        )
                                        .toString(),
                            )
                        )
                        queued = DocumentJobs.retry(repo, row)
                    }
                    textConversion.value = null
                } catch (failure: Exception) {
                    messages.send(failure.message ?: "转换未能开始")
                } finally {
                    busy.value = false
                }
                queued?.let(::watchExport)
            }
    }

    fun exportEditedText() =
        perform("保存正文") {
            textConversionSave?.join()
            val draft = textConversion.value ?: return@perform
            val output =
                File(
                    repo.exports,
                    "${safeFileName(draft.name.substringBeforeLast('.'))}-edited-${System.currentTimeMillis()}.txt",
                )
            java.io.FileOutputStream(output).use { stream ->
                draft.chunks.forEach { stream.write(it.toByteArray(Charsets.UTF_8)) }
                stream.fd.sync()
            }
            outputLocked.value = false
            publishOutput(output)
        }

    fun convertImages(uris: List<Uri>, format: String) =
        convertImages(uris, ImageConversionOptions(format = format))

    fun convertImages(uris: List<Uri>, options: ImageConversionOptions) =
        queueImageInputs(uris, "IMAGES", options.json())

    val longImages =
        LongImageWorkspace(
            repo,
            viewModelScope,
            ::navigateLongImages,
            ::watchExport,
            { messages.send(it) },
        )

    private fun navigateLongImages(target: String) {
        if (!target.startsWith("document:")) {
            screen.value = target
            return
        }
        viewModelScope.launch {
            val doc =
                withContext(Dispatchers.IO) { repo.dao.document(target.removePrefix("document:")) }
            if (doc != null && doc.trashedAt == null) {
                selected.value = doc
                screen.value = "editor"
            } else screen.value = "convert"
        }
    }

    fun editLongImageTask(row: LocalTaskRow) = perform("打开拼接设置副本") { longImages.copy(row) }

    fun convertLongImages(uris: List<Uri>, options: LongImageOptions) =
        perform("保留拼接原图") {
            if (uris.isNotEmpty()) longImages.create(ConversionInputs.retain(repo, uris), options)
        }

    fun stitchScanPages() =
        perform("准备票据页面") {
            val source = selected.value ?: return@perform
            val pages = repo.dao.pages(source.id)
            if (pages.isEmpty()) return@perform
            val folder =
                File(repo.context.filesDir, "conversion-inputs/stitch-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val inputs =
                pages.mapIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    progress.value = "准备第 ${index+1} / ${pages.size} 页"
                    cn.paperforge.app.data.RuntimeBudget.background.acquire()
                    try {
                        val bitmap = cn.paperforge.app.imaging.ScanProcessor.render(page, 2400)
                        try {
                            val file = File(folder, "page-${index+1}.png")
                            file.outputStream().use {
                                check(
                                    bitmap.compress(
                                        android.graphics.Bitmap.CompressFormat.PNG,
                                        100,
                                        it,
                                    )
                                )
                            }
                            file to "${source.title} · 第 ${index+1} 页"
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        cn.paperforge.app.data.RuntimeBudget.background.release()
                    }
                }
            longImages.create(inputs, LongImageOptions(gap = 0), "editor", source.id)
        }

    private fun queueImageInputs(uris: List<Uri>, kind: String, options: org.json.JSONObject) {
        if (busy.value || uris.isEmpty()) return
        busy.value = true
        task =
            viewModelScope.launch {
                var queued: UUID? = null
                try {
                    withContext(Dispatchers.IO) {
                        val inputs = ConversionInputs.retain(repo, uris)
                        queued =
                            DocumentJobs.enqueue(
                                repo,
                                "CONVERT",
                                JobInput(
                                    org.json
                                        .JSONObject()
                                        .put("kind", kind)
                                        .put("name", "${inputs.size} 张图片")
                                        .put(
                                            "inputs",
                                            org.json.JSONArray(inputs.map { it.first.path }),
                                        )
                                        .put("names", org.json.JSONArray(inputs.map { it.second }))
                                        .put("options", options.toString())
                                ),
                            )
                    }
                } catch (failure: Exception) {
                    messages.send(failure.message ?: "图片未能导入")
                } finally {
                    busy.value = false
                }
                queued?.let(::watchExport)
            }
    }

    fun previewOutput(file: File) =
        perform("正在打开 PDF") {
            outputReturn.value =
                screen.value.takeIf {
                    it in
                        setOf(
                            "assembly",
                            "workflows",
                            "format-conversion",
                            "convert",
                            "jobs",
                            "auto-export",
                            "pdf-redaction",
                            "conversion-batch",
                        )
                }
            val doc = repo.registerPdf(file, file.nameWithoutExtension)
            selected.value = doc
            output.value = null
            screen.value = "reader"
        }

    val redactionSource = MutableStateFlow<DocumentRow?>(null)

    fun queueRedaction(mode: String, options: org.json.JSONObject) {
        val source = redactionSource.value ?: return
        selected.value = source
        queueTool(mode, options)
    }

    fun queueTool(mode: String, options: org.json.JSONObject = org.json.JSONObject()) {
        if (busy.value) return
        if (mode == "redact") {
            redactionSource.value = selected.value
            screen.value = "pdf-redaction"
            return
        }
        enqueueJob(
            "PDF",
            JobInput(
                org.json
                    .JSONObject()
                    .put("mode", mode)
                    .put("document", selected.value?.id)
                    .put("options", options.toString())
                    .put(
                        "sources",
                        org.json.JSONArray(mergeSources.value.mapNotNull { it.pdfPath }),
                    )
            ),
        )
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

    fun exportPdfDiagnostics(report: PdfDiagnosticsReport) =
        perform("保存诊断报告") {
            val file =
                File(
                    repo.exports,
                    "${safeFileName(report.fileName.substringBeforeLast('.'))}-diagnostics-${System.currentTimeMillis()}.json",
                )
            file.writeText(report.json().toString(2), Charsets.UTF_8)
            outputLocked.value = false
            publishOutput(file, selected.value)
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
            marksSave?.join()
            textConversionSave?.join()
            assembly.flush()
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

    val backupPreview = MutableStateFlow<Pair<Uri, BackupStore.Preview>?>(null)

    fun restoreBackup(uri: Uri) =
        perform("正在检查备份") { backupPreview.value = uri to BackupStore.preview(repo, uri) }

    fun cancelBackupRestore() {
        backupPreview.value = null
    }

    fun confirmBackupRestore(policy: BackupStore.DuplicatePolicy, applyWorkspace: Boolean) {
        val pending = backupPreview.value ?: return
        backupPreview.value = null
        restoreBackupNow(pending.first, policy, applyWorkspace)
    }

    private fun restoreBackupNow(
        uri: Uri,
        policy: BackupStore.DuplicatePolicy,
        applyWorkspace: Boolean,
    ) =
        perform("正在恢复备份") {
            assembly.cancelLoading()
            assembly.flush()
            val count = BackupStore.restore(repo, uri, policy, applyWorkspace)
            canUndoNames.value = LibraryPreferences(repo.context).renameUndo().isNotEmpty()
            comparison.refreshFamilies()
            assembly.reload()
            val workspace = if (applyWorkspace) "已应用设置和工作区" else "已保留本机设置和工作区"
            messages.send("已追加 $count 份文档；$workspace；配方和规则已合并，新规则已停用")
        }

    private fun markDraft() = File(repo.folder(selected.value!!.id), "annotations.json")

    val marksPreview = MutableStateFlow<String?>(null)
    val managedMarksCount = MutableStateFlow(0)
    val canUndoMarks = MutableStateFlow(false)
    val canRedoMarks = MutableStateFlow(false)
    private val markUndo = ArrayDeque<List<PdfMark>>()
    private val markRedo = ArrayDeque<List<PdfMark>>()

    fun beginMarks() =
        perform("读取批注草稿") {
            marksSave?.join()
            val source = File(requireNotNull(selected.value?.pdfPath))
            val imported = PdfNativeMarks.read(source)
            managedMarksCount.value = imported.size
            marks.value =
                runCatching { PdfMark.decode(org.json.JSONArray(markDraft().readText())) }
                    .getOrDefault(imported)
            markUndo.clear()
            markRedo.clear()
            canUndoMarks.value = false
            canRedoMarks.value = false
            val previous = marksPreview.value
            marksPreview.value =
                if (imported.isEmpty()) null
                else
                    PdfNativeMarks.preview(
                            source,
                            File(repo.context.cacheDir, "marks-preview-${UUID.randomUUID()}.pdf"),
                        )
                        .path
            previous?.let { File(it).delete() }
            screen.value = "annotations"
        }

    fun updateMarks(value: List<PdfMark>) {
        if (value == marks.value) return
        markUndo.addLast(marks.value)
        if (markUndo.size > 30) markUndo.removeFirst()
        markRedo.clear()
        persistMarks(value)
    }

    fun undoMarks() {
        if (markUndo.isEmpty()) return
        markRedo.addLast(marks.value)
        persistMarks(markUndo.removeLast())
    }

    fun redoMarks() {
        if (markRedo.isEmpty()) return
        markUndo.addLast(marks.value)
        persistMarks(markRedo.removeLast())
    }

    private fun persistMarks(value: List<PdfMark>) {
        marks.value = value
        canUndoMarks.value = markUndo.isNotEmpty()
        canRedoMarks.value = markRedo.isNotEmpty()
        val file = markDraft()
        marksSave?.cancel()
        marksSave =
            viewModelScope.launch(Dispatchers.IO) {
                delay(250)
                val atomic = android.util.AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(
                        org.json
                            .JSONArray(value.map { it.json() })
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomic.failWrite(stream)
                    throw failure
                }
            }
    }

    fun saveMarks(editable: Boolean = true) {
        if (busy.value || (marks.value.isEmpty() && managedMarksCount.value == 0)) return
        val file = File(repo.exports, "marks-job-${UUID.randomUUID()}.json")
        file.writeText(org.json.JSONArray(marks.value.map { it.json() }).toString())
        queueTool(
            "marks",
            org.json.JSONObject().put("marksFile", file.path).put("editable", editable),
        )
    }

    fun addReaderMark(page: Int, block: ReaderTextBlock, kind: String) =
        addReaderMarks(page, listOf(block), kind)

    fun addReaderMarks(page: Int, blocks: List<ReaderTextBlock>, kind: String) =
        perform("添加文字标记") {
            marksSave?.join()
            val source = File(requireNotNull(selected.value?.pdfPath))
            val previous =
                runCatching { PdfMark.decode(org.json.JSONArray(markDraft().readText())) }
                    .getOrElse { PdfNativeMarks.read(source) }
            val additions =
                blocks.map { block ->
                    PdfMark(
                        page,
                        kind,
                        listOf(
                            cn.paperforge.app.model.PointN(block.left, block.top),
                            cn.paperforge.app.model.PointN(block.right, block.bottom),
                        ),
                        android.graphics.Color.YELLOW,
                        text = block.text,
                    )
                }
            android.util.AtomicFile(markDraft()).let { atomic ->
                val stream = atomic.startWrite()
                try {
                    stream.write(
                        org.json
                            .JSONArray((previous + additions).map { it.json() })
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomic.failWrite(stream)
                    throw failure
                }
            }
            messages.send("已加入批注草稿，可在“批注”中查看和导出")
        }
}

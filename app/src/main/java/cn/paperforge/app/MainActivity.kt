package cn.paperforge.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.paperforge.app.data.OutputNames
import cn.paperforge.app.ui.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    private var appLocked by mutableStateOf(false)
    private var authenticating = false
    private val securityPreferences by lazy { getSharedPreferences("security", 0) }
    private val authentication =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            authenticating = false
            if (result.resultCode == RESULT_OK) {
                LocalAppLock.authorized = true
                LocalAppLock.backgroundAt = 0L
                appLocked = false
            }
        }

    fun securitySettingsChanged(forceLock: Boolean = false) {
        if (securityPreferences.getBoolean("secureScreen", false))
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val available = getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure
        if (!available) securityPreferences.edit().putBoolean("appLock", false).apply()
        val enabled = available && securityPreferences.getBoolean("appLock", false)
        if (!enabled) appLocked = false
        else if (forceLock || !LocalAppLock.authorized) {
            LocalAppLock.authorized = false
            appLocked = true
        }
    }

    override fun onStart() {
        super.onStart()
        securitySettingsChanged()
        if (
            !authenticating &&
                securityPreferences.getBoolean("appLock", false) &&
                LocalAppLock.backgroundAt > 0 &&
                android.os.SystemClock.elapsedRealtime() - LocalAppLock.backgroundAt > 30_000
        ) {
            LocalAppLock.authorized = false
            appLocked = true
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && !authenticating)
            LocalAppLock.backgroundAt = android.os.SystemClock.elapsedRealtime()
        super.onStop()
        if (!isChangingConfigurations)
            (application as PaperForgeApp).backgroundScope.launch {
                runCatching {
                    cn.paperforge.app.system.SystemEntrypoints.refreshWidgets(this@MainActivity)
                }
            }
    }

    private var incoming by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming = if (savedInstanceState == null) intent else null
        securitySettingsChanged()
        setContent {
            val vm: MainViewModel = viewModel()
            val screen by vm.screen.collectAsState()
            PaperForgeTheme(forceDarkSystemBars = screen == "capture" && !appLocked) {
                if (appLocked) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().systemBarsPadding(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("PaperForge已锁定", style = MaterialTheme.typography.headlineSmall)
                            Button(
                                onClick = {
                                    @Suppress("DEPRECATION")
                                    val intent =
                                        getSystemService(android.app.KeyguardManager::class.java)
                                            .createConfirmDeviceCredentialIntent(
                                                "解锁PaperForge",
                                                "请使用系统设备凭据验证",
                                            )
                                    if (intent != null) {
                                        authenticating = true
                                        authentication.launch(intent)
                                    } else securitySettingsChanged()
                                }
                            ) {
                                Text("使用设备凭据解锁")
                            }
                        }
                    }
                    return@PaperForgeTheme
                }
                val busy by vm.busy.collectAsState()
                val backgroundable by vm.canBackgroundTask.collectAsState()
                val progress by vm.progress.collectAsState()
                val output by vm.output.collectAsState()
                val outputNotice by vm.outputNotice.collectAsState()
                val ocr by vm.ocr.collectAsState()
                val canCorrectOcr by vm.canCorrectOcr.collectAsState()
                val locked by vm.outputLocked.collectAsState()
                val passwordRequest by vm.passwordRequest.collectAsState()
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val navigationExpansion = remember { NavigationExpansion(scope) }
                var about by remember { mutableStateOf(false) }
                var pendingText by remember { mutableStateOf("") }
                val clipboard = LocalClipboardManager.current
                var recognizeImport by remember { mutableStateOf(false) }
                val images =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.PickMultipleVisualMedia()
                    ) {
                        vm.importImages(it, recognizeImport)
                        recognizeImport = false
                    }
                val conversionImages = rememberPaperDocumentsPicker { vm.importImages(it) }
                val pdf = rememberPaperDocumentPicker {
                    if (it != null) vm.importPdf(it) else vm.pdfEntry.value = null
                }
                val merge = rememberPaperDocumentsPicker {
                    if (it.size >= 2) vm.merge(it)
                    else if (it.isNotEmpty()) scope.launch { snackbar.showSnackbar("请选择至少两份 PDF") }
                }
                val assemblyImport = rememberPaperDocumentsPicker { uris ->
                    if (uris.isNotEmpty()) vm.importAssemblySources(uris)
                }
                val diffImport = rememberPaperDocumentsPicker { uris ->
                    if (uris.size == 2) vm.pdfDiffInputs.value = uris
                    else if (uris.isNotEmpty())
                        android.widget.Toast.makeText(
                                this,
                                "请选择两份 PDF",
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            .show()
                }
                val save =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == RESULT_OK) result.data?.data?.let(vm::saveOutput)
                    }
                val exportDirectory =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                        uri ->
                        uri?.let(vm::rememberOutputDirectory)
                    }
                val restore =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                        it?.let(vm::restoreBackup)
                    }
                val saveText =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("text/plain")
                    ) { uri ->
                        uri?.let {
                            scope.launch {
                                runCatching {
                                        withContext(Dispatchers.IO) {
                                            contentResolver.openOutputStream(it, "wt")?.use { stream
                                                ->
                                                stream.write(
                                                    pendingText.toByteArray(Charsets.UTF_8)
                                                )
                                            } ?: error("无法写入文本")
                                        }
                                    }
                                    .onSuccess { snackbar.showSnackbar("文本已保存") }
                                    .onFailure { snackbar.showSnackbar(it.message ?: "保存失败") }
                            }
                        }
                    }
                var conversionFont by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(12f) }
                var markupTarget by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("HTML") }
                var markupOptions by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("{}") }
                val markupImport = rememberPaperDocumentPicker { uri ->
                    uri?.let {
                        vm.convertMarkup(it, markupTarget, org.json.JSONObject(markupOptions))
                    }
                }
                var conversionImageOptions by
                    androidx.compose.runtime.saveable.rememberSaveable {
                        mutableStateOf(
                            cn.paperforge.app.pdf.ImageConversionOptions().json().toString()
                        )
                    }
                val textImport = rememberPaperDocumentPicker {
                    it?.let { uri -> vm.convertText(uri, conversionFont) }
                }
                val formatImport = rememberPaperDocumentsPicker {
                    if (it.isNotEmpty())
                        vm.convertImages(
                            it,
                            cn.paperforge.app.pdf.ImageConversionOptions.decode(
                                org.json.JSONObject(conversionImageOptions)
                            ),
                        )
                }
                var longOptionsJson by
                    androidx.compose.runtime.saveable.rememberSaveable {
                        mutableStateOf(cn.paperforge.app.pdf.LongImageOptions().json().toString())
                    }
                val longImport = rememberPaperDocumentsPicker { uris ->
                    if (uris.isNotEmpty())
                        vm.convertLongImages(
                            uris,
                            cn.paperforge.app.pdf.LongImageOptions.decode(
                                org.json.JSONObject(longOptionsJson)
                            ),
                        )
                }
                var mobileTarget by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("pdf") }
                var mobileOfficeOptions by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("{}") }
                val mobileOfficeInput = rememberPaperDocumentPicker { uri ->
                    uri?.let {
                        vm.phoneOffice(it, mobileTarget, org.json.JSONObject(mobileOfficeOptions))
                    }
                }
                var mobileTableCsv by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                var mobileTableOptions by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("{}") }
                val mobileTableInput = rememberPaperDocumentPicker { uri ->
                    uri?.let {
                        vm.phoneTables(it, mobileTableCsv, org.json.JSONObject(mobileTableOptions))
                    }
                }
                var desktopMode by
                    androidx.compose.runtime.saveable.rememberSaveable {
                        mutableStateOf("office-pdf")
                    }
                var desktopOptions by
                    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("{}") }
                val desktopImport = rememberPaperDocumentPicker {
                    it?.let { uri ->
                        vm.desktopTask(uri, desktopMode, org.json.JSONObject(desktopOptions))
                    }
                }
                val desktopResult = rememberPaperDocumentPicker { it?.let(vm::importDesktopResult) }
                val share: (File, Boolean) -> Unit = { file, open ->
                    scope.launch {
                        runCatching {
                                val copy =
                                    withContext(Dispatchers.IO) {
                                        File(cacheDir, "share/${java.util.UUID.randomUUID()}")
                                            .apply { mkdirs() }
                                            .let { File(it, OutputNames.display(vm.repo, file)) }
                                            .also { file.copyTo(it, overwrite = true) }
                                    }
                                val uri =
                                    FileProvider.getUriForFile(
                                        this@MainActivity,
                                        "$packageName.files",
                                        copy,
                                    )
                                startActivity(
                                    Intent.createChooser(
                                        Intent(if (open) Intent.ACTION_VIEW else Intent.ACTION_SEND)
                                            .apply {
                                                if (open) setDataAndType(uri, mime(file))
                                                else {
                                                    type = mime(file)
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                }
                                                putExtra(Intent.EXTRA_TITLE, copy.name)
                                                clipData =
                                                    android.content.ClipData.newRawUri(
                                                        copy.name,
                                                        uri,
                                                    )
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                        if (open) "用其他应用打开" else "分享文件",
                                    )
                                )
                            }
                            .onFailure { snackbar.showSnackbar(it.message ?: "无法启动分享") }
                    }
                }
                LaunchedEffect(Unit) { for (message in vm.messages) snackbar.showSnackbar(message) }
                val renameNotice by vm.renameNotice.collectAsState()
                LaunchedEffect(renameNotice) {
                    val notice = renameNotice ?: return@LaunchedEffect
                    val result =
                        snackbar.showSnackbar(
                            "已命名 ${notice.second} 份文档",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Long,
                        )
                    if (vm.renameNotice.value == notice) vm.renameNotice.value = null
                    if (result == SnackbarResult.ActionPerformed) vm.undoBatchNames()
                }
                val folderNotice by vm.folderNotice.collectAsState()
                LaunchedEffect(folderNotice, screen) {
                    val notice = folderNotice ?: return@LaunchedEffect
                    if (screen != "home") {
                        vm.folderNotice.value = null
                        return@LaunchedEffect
                    }
                    val result =
                        snackbar.showSnackbar(notice, "撤销", duration = SnackbarDuration.Long)
                    if (vm.folderNotice.value == notice) {
                        if (result == SnackbarResult.ActionPerformed) vm.undoLibraryFolder()
                        else vm.folderNotice.value = null
                    }
                }
                LaunchedEffect(incoming, busy) {
                    if (busy) return@LaunchedEffect
                    incoming?.let { source ->
                        @Suppress("DEPRECATION")
                        when (source.action) {
                            cn.paperforge.app.system.SystemEntrypoints.SCAN -> vm.newScan()
                            cn.paperforge.app.system.SystemEntrypoints.CONTINUE ->
                                vm.continueReading()
                            cn.paperforge.app.system.SystemEntrypoints.OCR -> {
                                vm.screen.value = "home"
                                vm.selected.value = null
                                recognizeImport = true
                                images.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                            Intent.ACTION_SEND ->
                                source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                                    if (
                                        source.type == "application/pdf" ||
                                            contentResolver.getType(it) == "application/pdf" ||
                                            vm.repo
                                                .displayName(it)
                                                .endsWith(".pdf", ignoreCase = true)
                                    )
                                        vm.importSharedPdf(it)
                                    else vm.importImages(listOf(it))
                                }
                            Intent.ACTION_SEND_MULTIPLE ->
                                source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                                    vm.importImages(it)
                                }
                            Intent.ACTION_VIEW ->
                                source.data?.let {
                                    if (it.scheme == "paperforge") vm.openDocumentLink(it)
                                    else vm.importPdf(it)
                                }
                        }
                        incoming = null
                    }
                }
                val shareProcessing by vm.shareProcessing.collectAsState()
                val shareStatus by vm.shareStatus.collectAsState()
                val shareReady by vm.shareReady.collectAsState()
                shareReady?.let { file ->
                    AlertDialog(
                        onDismissRequest = {
                            vm.shareReady.value = null
                            if (intent.action == Intent.ACTION_SEND) finish()
                        },
                        title = { Text("PDF 已处理") },
                        text = { Text("${file.name}\n可保存到手机文件夹，或继续分享到其他应用。") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    vm.output.value = file
                                    vm.shareReady.value = null
                                    save.launch(
                                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                                            .addCategory(Intent.CATEGORY_OPENABLE)
                                            .setType("application/pdf")
                                            .putExtra(Intent.EXTRA_TITLE, file.name)
                                    )
                                }
                            ) {
                                Text("保存到本地 PDF")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    runCatching {
                                            val uri =
                                                FileProvider.getUriForFile(
                                                    this@MainActivity,
                                                    "$packageName.files",
                                                    file,
                                                )
                                            val send =
                                                Intent(Intent.ACTION_SEND)
                                                    .setType("application/pdf")
                                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                                    .putExtra(Intent.EXTRA_TITLE, file.name)
                                                    .apply {
                                                        clipData =
                                                            android.content.ClipData.newRawUri(
                                                                file.name,
                                                                uri,
                                                            )
                                                    }
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            val forwarded =
                                                UserSettings[SettingKeys.ShareForward] ==
                                                    "wechat" &&
                                                    runCatching {
                                                            startActivity(
                                                                Intent(send)
                                                                    .setPackage("com.tencent.mm")
                                                            )
                                                            true
                                                        }
                                                        .getOrDefault(false)
                                            if (!forwarded)
                                                startActivity(
                                                    Intent.createChooser(send, "分享处理后的 PDF")
                                                        .putExtra(
                                                            Intent.EXTRA_EXCLUDE_COMPONENTS,
                                                            arrayOf(
                                                                android.content.ComponentName(
                                                                    this@MainActivity,
                                                                    MainActivity::class.java,
                                                                )
                                                            ),
                                                        )
                                                )
                                            vm.shareReady.value = null
                                            // Remove this temporary share entry from the caller's
                                            // back stack.
                                            // NEW_TASK previously brought the scanner back through
                                            // PaperForge.
                                            if (intent.action == Intent.ACTION_SEND) finish()
                                        }
                                        .onFailure {
                                            vm.messages.trySend(it.message ?: "无法分享处理后的 PDF")
                                        }
                                }
                            ) {
                                Text("继续分享")
                            }
                        },
                    )
                }
                if (shareProcessing) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                            tonalElevation = 6.dp,
                        ) {
                            Column(
                                Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(14.dp))
                                Text(shareStatus.ifBlank { "正在处理…" })
                            }
                        }
                    }
                }
                val navigateBack: () -> Unit = back@{
                    if (screen == "tables") {
                        vm.tableWorkspace.close()
                        return@back
                    }
                    if (screen == "assembly") {
                        vm.closeAssembly()
                        return@back
                    }
                    if (screen != "profiles") vm.replacement.value = null
                    if (screen in listOf("reader", "editor")) {
                        vm.closeDocument()
                        return@back
                    }
                    vm.screen.value =
                        when {
                            screen == "pdf-archive" ->
                                if (vm.archiveSource.value?.pdfPath == null)
                                    vm.archiveReportReturn.value
                                else "reader".also { vm.selected.value = vm.archiveSource.value }
                            screen == "pdf-certificates" ->
                                "reader".also { vm.selected.value = vm.certificateSource.value }
                            screen == "pdf-forms" ->
                                "reader".also { vm.selected.value = vm.formSource.value }
                            screen == "pdf-native-edit" ->
                                if (vm.nativeEditSource.value?.pdfPath == null) "home"
                                else "reader".also { vm.selected.value = vm.nativeEditSource.value }
                            screen == "pdf-redaction" ->
                                if (vm.redactionSource.value?.pdfPath == null) "home"
                                else "reader".also { vm.selected.value = vm.redactionSource.value }
                            screen in listOf("annotations", "pdf-pages", "pdf-diagnostics") ->
                                "reader"
                            screen in listOf("conversion-batch", "workflows", "auto-export") ->
                                vm.toolHubReturn.value
                            screen == "conversion-preview" -> vm.conversionPreviewReturn.value
                            screen == "text-compose" || screen == "markup-review" ->
                                vm.conversionReturn.value
                            screen == "ocr-models" -> vm.ocrModelsReturn.value
                            screen == "storage" -> vm.storageReturn.value
                            screen == "profiles" -> vm.profileReturn.value
                            screen == "ocr-proof" -> "editor"
                            screen == "comparison" -> "home".also { vm.comparison.cancel() }
                            screen == "pages" ||
                                screen == "capture" && vm.pages.value.isNotEmpty() -> "editor"
                            else -> "home"
                        }
                }
                BackHandler(screen != "home" && !busy) { navigationExpansion.close(navigateBack) }

                Box(
                    Modifier.fillMaxSize().semantics {
                        testTagsAsResourceId = true
                        isTraversalGroup = true
                    }
                ) {
                    DocumentWorkspace(vm, screen, navigationExpansion) {
                        when (LocalPaperDestination.current?.screen ?: screen) {
                            "capture" ->
                                CameraScreen(vm) {
                                    images.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            "document-watermarks" -> DocumentWatermarkScreen(vm)
                            "editor" ->
                                EditorScreen(vm) {
                                    images.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            "reader" -> ReaderScreen(vm) { share(it, false) }
                            "help" -> UsageGuideScreen(vm)
                            "pdf-diagnostics" -> PdfDiagnosticsScreen(vm)
                            "pdf-native-edit" -> NativePdfEditorScreen(vm)
                            "pdf-certificates" -> PdfCertificatesScreen(vm)
                            "pdf-forms" -> PdfFormsScreen(vm)
                            "pdf-archive" -> PdfArchiveScreen(vm)
                            "pdf-redaction" -> {
                                val source by vm.redactionSource.collectAsState()
                                val path = source?.pdfPath
                                if (path != null)
                                    PdfRedactionScreen(
                                        input = File(path),
                                        onBack = {
                                            vm.selected.value = source
                                            vm.screen.value = "reader"
                                        },
                                        onSubmit = vm::queueRedaction,
                                    )
                                else
                                    Column(Modifier.padding(24.dp)) {
                                        Text("请先打开要处理的 PDF")
                                        TextButton(onClick = { vm.screen.value = "home" }) {
                                            Text("返回首页")
                                        }
                                    }
                            }
                            "conversion-batch" -> ConversionBatchScreen(vm)
                            "conversion-preview" -> ConversionPreviewScreen(vm)
                            "text-compose" -> TextConversionScreen(vm)
                            "long-image-compose" -> LongImageComposeScreen(vm.longImages)
                            "tables" -> TableReviewScreen(vm)
                            "notes" -> StudyNotesScreen(vm)
                            "comparison" -> LibraryComparisonScreen(vm)
                            "workflows" -> WorkflowScreen(vm)
                            "auto-export" -> AutoExportScreen(vm)
                            "format-conversion" -> FormatConversionScreen(vm)
                            "pdf-diff" ->
                                PdfDiffScreen(vm) { diffImport.launch(arrayOf("application/pdf")) }
                            "assembly" ->
                                PdfAssemblyScreen(vm) {
                                    assemblyImport.launch(arrayOf("application/pdf"))
                                }
                            "convert" ->
                                ConversionScreen(
                                    vm,
                                    onPdf = { mode ->
                                        vm.pdfEntry.value = mode
                                        pdf.launch(arrayOf("application/pdf"))
                                    },
                                    onImages = { conversionImages.launch(arrayOf("image/*")) },
                                    onMerge = { merge.launch(arrayOf("application/pdf")) },
                                    onText = { size ->
                                        conversionFont = size
                                        textImport.launch(
                                            arrayOf(
                                                "text/*",
                                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                "application/octet-stream",
                                            )
                                        )
                                    },
                                    onMarkup = { target, options ->
                                        markupTarget = target
                                        markupOptions = options.toString()
                                        markupImport.launch(
                                            arrayOf(
                                                "text/*",
                                                "application/zip",
                                                "application/xhtml+xml",
                                                "application/epub+zip",
                                                "application/pdf",
                                                "application/octet-stream",
                                            )
                                        )
                                    },
                                    onLongImages = { options ->
                                        longOptionsJson = options.json().toString()
                                        longImport.launch(arrayOf("image/*"))
                                    },
                                    onImageFormat = { settings ->
                                        conversionImageOptions = settings.json().toString()
                                        formatImport.launch(arrayOf("image/*"))
                                    },
                                    onDesktop = { mode, options ->
                                        when {
                                            mode.startsWith("office-") -> {
                                                mobileTarget = mode.removePrefix("office-")
                                                mobileOfficeOptions = options.toString()
                                                mobileOfficeInput.launch(arrayOf("*/*"))
                                            }
                                            mode.startsWith("tables-") -> {
                                                mobileTableCsv = mode.endsWith("csv")
                                                mobileTableOptions = options.toString()
                                                mobileTableInput.launch(
                                                    arrayOf("application/pdf", "image/*")
                                                )
                                            }
                                            else -> {
                                                desktopMode = mode
                                                desktopOptions = options.toString()
                                                desktopImport.launch(arrayOf("*/*"))
                                            }
                                        }
                                    },
                                    onResult = {
                                        desktopResult.launch(
                                            arrayOf("application/zip", "application/octet-stream")
                                        )
                                    },
                                )
                            "settings" -> SettingsScreen(vm)
                            "markup-review" -> MarkupReviewScreen(vm)
                            "ocr-models" -> OcrModelsScreen(vm)
                            "storage" -> StorageScreen(vm)
                            "tasks" -> TaskCenterScreen(vm)
                            "profiles" -> SettingsProfilesScreen(vm)
                            "ocr-proof" -> OcrProofreadScreen(vm)
                            "rename-batch" -> BatchRenameScreen(vm)
                            "outputs" -> OutputHistoryScreen(vm)
                            "pages" -> PageGridScreen(vm)
                            "pdf-pages" -> PdfPageGridScreen(vm)
                            "merge" -> MergeScreen(vm)
                            "annotations" -> AnnotationScreen(vm)
                            else ->
                                HomeScreen(
                                    vm,
                                    {
                                        images.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    { pdf.launch(arrayOf("application/pdf")) },
                                    { merge.launch(arrayOf("application/pdf")) },
                                    { about = true },
                                    {
                                        restore.launch(
                                            arrayOf("application/zip", "application/octet-stream")
                                        )
                                    },
                                    onPdfTool = { mode ->
                                        vm.pdfEntry.value = mode
                                        pdf.launch(arrayOf("application/pdf"))
                                    },
                                    onOcrImages = {
                                        recognizeImport = true
                                        images.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                )
                        }
                    }
                    SnackbarHost(
                        snackbar,
                        Modifier.align(
                                if (screen in setOf("reader", "editor")) Alignment.TopCenter
                                else Alignment.BottomCenter
                            )
                            .then(
                                if (screen in setOf("reader", "editor"))
                                    Modifier.statusBarsPadding().padding(top = 128.dp)
                                else Modifier.navigationBarsPadding().padding(bottom = 88.dp)
                            ),
                    )
                    if (busy)
                        Surface(
                            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
                            shape = MaterialTheme.shapes.large,
                            shadowElevation = 4.dp,
                        ) {
                            Column(
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        progress,
                                        Modifier.weight(1f).semantics {
                                            liveRegion = LiveRegionMode.Polite
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    if (backgroundable)
                                        TextButton(onClick = vm::backgroundJob) { Text("后台") }
                                    TextButton(onClick = vm::cancel) { Text("取消") }
                                }
                            }
                        }
                }
                output?.let { file ->
                    val displayName by
                        produceState(file.name, file) {
                            value =
                                withContext(Dispatchers.IO) { OutputNames.display(vm.repo, file) }
                        }
                    // v200 feedback 1: a finished PDF is a library document; the card links to it.
                    val libraryDocument by
                        produceState<cn.paperforge.app.data.DocumentRow?>(null, file) {
                            value =
                                withContext(Dispatchers.IO) {
                                    runCatching { vm.repo.dao.documentByPath(file.path) }
                                        .getOrNull()
                                }
                        }
                    val tasks by vm.localTasks.collectAsState()
                    val compareTask =
                        tasks.firstOrNull { row ->
                            row.resourceEntries().any {
                                it.id == "result" && it.path == file.path
                            } && cn.paperforge.app.pdf.ConversionPreviews.files(row) != null
                        }
                    val fileNotice =
                        outputNotice?.takeIf { it.first == file.path }?.second.orEmpty()
                    val exportRevision by UserSettings.revision.collectAsState()
                    val destination =
                        remember(exportRevision) {
                            cn.paperforge.app.data
                                .ExportDestinations(this@MainActivity)
                                .get(UserSettings[SettingKeys.ExportDestination])
                        }
                    val saveElsewhere: () -> Unit = {
                        save.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType(mime(file))
                                .putExtra(Intent.EXTRA_TITLE, displayName)
                                .apply {
                                    cn.paperforge.app.data
                                        .ExportDestinations(this@MainActivity)
                                        .get(UserSettings[SettingKeys.ExportDestination])
                                        ?.let {
                                            putExtra(
                                                android.provider.DocumentsContract
                                                    .EXTRA_INITIAL_URI,
                                                it.uri,
                                            )
                                        }
                                }
                        )
                    }
                    OutputResultDialog(
                        title = if (file.extension == "pdf") "PDF 已生成" else "文件已生成",
                        name = displayName,
                        size =
                            android.text.format.Formatter.formatShortFileSize(
                                this@MainActivity,
                                file.length(),
                            ),
                        notice = fileNotice,
                        destination = destination?.name,
                        onDismiss = { vm.output.value = null },
                        onShare = { share(file, false) },
                        onSave = {
                            if (destination != null) vm.saveOutputToDirectory(destination.id)
                            else saveElsewhere()
                        },
                        onSaveAs = saveElsewhere,
                        onOpen = { share(file, true) },
                        onPickDirectory = { exportDirectory.launch(destination?.uri) },
                        onPreview =
                            if (file.extension == "pdf" && !locked) ({ vm.previewOutput(file) })
                            else null,
                        onCompare =
                            if (!locked && compareTask != null)
                                ({ vm.compareConversion(compareTask) })
                            else null,
                        onLibrary =
                            libraryDocument?.let { doc -> { vm.open(doc) } },
                    )
                }

                ocr?.let { result ->
                    var text by rememberSaveable(result) { mutableStateOf(result) }
                    val review by vm.ocrReview.collectAsState()
                    val versions by vm.ocrVersions.collectAsState()
                    var audit by remember(result) { mutableStateOf(false) }
                    SpaciousToolDialog(
                        onDismissRequest = { vm.ocr.value = null },
                        title = { Text("识别结果") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (canCorrectOcr && review.isNotBlank())
                                    Text(review, style = MaterialTheme.typography.bodySmall)
                                if (canCorrectOcr && versions.isNotEmpty())
                                    TextButton(onClick = { audit = true }) { Text("原始识别与校正记录") }
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 160.dp, max = 420.dp),
                                    label = { Text("文字内容") },
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingText = text
                                    saveText.launch("PaperForge文字.txt")
                                }
                            ) {
                                Text("导出 TXT")
                            }
                        },
                        dismissButton = {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(text))
                                        scope.launch { snackbar.showSnackbar("已复制文字") }
                                    }
                                ) {
                                    Text("复制")
                                }
                                if (canCorrectOcr)
                                    TextButton(onClick = { vm.correctOcr(text) }) { Text("保存校正") }
                                TextButton(onClick = { vm.ocr.value = null }) { Text("关闭") }
                            }
                        },
                    )
                    if (audit)
                        OcrAuditDialog(
                            versions,
                            onDismiss = { audit = false },
                            onUse = {
                                text = it
                                audit = false
                            },
                        )
                }
                passwordRequest?.let { uri ->
                    var password by remember(uri) { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { vm.passwordRequest.value = null },
                        title = { Text("输入 PDF 打开密码") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    password,
                                    { password = it },
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text("密码") },
                                )
                                Text(
                                    "解锁后在应用中保存可编辑副本，密码不保存。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = !busy, onClick = { vm.importPdf(uri, password) }) {
                                Text("打开")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { vm.passwordRequest.value = null }) { Text("取消") }
                        },
                    )
                }
                BackupRestoreDialog(vm)
                if (about)
                    AlertDialog(
                        onDismissRequest = { about = false },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandLogo(Modifier.size(52.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "PaperForge",
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                    Text(
                                        packageManager
                                            .getPackageInfo(packageName, 0)
                                            .versionName
                                            .orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                var showNotices by remember { mutableStateOf(false) }
                                Text(
                                    if (
                                        cn.paperforge.app.system.PhoneEngines.integrated(
                                            this@MainActivity
                                        )
                                    )
                                        "完整功能版 · 扫描、阅读与文件转换\n\nOffice、特殊格式和联网工具均已内置。文档、原图和编辑历史可完整备份。"
                                    else
                                        "个人项目 · 扫描、阅读与文件转换\n\n扫描与 OCR 在手机运行。Office 与特殊格式使用可选本机引擎，文档、原图和编辑历史可完整备份。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(
                                                        "https://github.com/HABAI182/paperforge-android"
                                                    ),
                                                )
                                            )
                                        }
                                    ) {
                                        Text("项目主页")
                                    }
                                    TextButton(
                                        onClick = {
                                            cn.paperforge.app.system.NetworkTools.updates(
                                                this@MainActivity,
                                                checkOnOpen = true,
                                            )
                                        }
                                    ) {
                                        Text("检查更新")
                                    }
                                }
                                Text(
                                    if (
                                        cn.paperforge.app.system.PhoneEngines.integrated(
                                            this@MainActivity
                                        )
                                    )
                                        "完整功能版：Office、特殊格式和联网工具已内置。扫描与文档处理在手机运行，联网功能按需开启。"
                                    else "安装可选联网工具后，可在手机内检查和下载更新。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                                TextButton(onClick = { showNotices = !showNotices }) {
                                    Text(if (showNotices) "收起开源与模型声明" else "开源与模型声明")
                                }
                                if (showNotices)
                                    Text(
                                        remember {
                                            runCatching {
                                                    assets
                                                        .open("LICENSE_NOTICES.txt")
                                                        .bufferedReader()
                                                        .use { it.readText() }
                                                }
                                                .getOrDefault("见源码 LICENSE_NOTICES.md")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                            }
                        },
                        confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } },
                    )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = intent
    }
}

private object LocalAppLock {
    var authorized = false
    var backgroundAt = 0L
}

private fun mime(file: File) =
    when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "epub" -> "application/epub+zip"
        "jpg",
        "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "csv" -> "text/csv"
        "rtf" -> "application/rtf"
        "html" -> "text/html"
        "md" -> "text/markdown"
        else -> "text/plain"
    }

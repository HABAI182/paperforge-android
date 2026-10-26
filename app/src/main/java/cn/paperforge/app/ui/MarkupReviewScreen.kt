package cn.paperforge.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*

private data class MarkupPage(
    val html: String,
    val raw: String,
    val source: MarkupSource,
    val print: TextPdfOptions,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MarkupReviewScreen(vm: MainViewModel) {
    val review by vm.markupReview.collectAsState()
    val model = review ?: return
    val context = LocalContext.current
    var tab by remember(model.result) { mutableIntStateOf(1) }
    var code by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var printView by remember { mutableStateOf<WebView?>(null) }
    var notice by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val page by
        produceState<Result<MarkupPage>?>(null, model.result, tab, retry) {
            value = null
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val outcome = MarkupDocuments.open(context, File(model.result))
                        val options =
                            TextPdfOptions.decode(
                                outcome.report?.optJSONObject("print") ?: org.json.JSONObject()
                            )
                        if (tab == 0 && File(model.source).extension.equals("pdf", true)) {
                            val original = File(model.source)
                            val pdfPages =
                                outcome.report
                                    ?.optJSONObject("sourceDetails")
                                    ?.optJSONArray("pages")
                            val html = buildString {
                                append("<h1>PDF 原件逐页对照</h1>")
                                if (pdfPages != null)
                                    for (i in 0 until pdfPages.length()) {
                                        val image =
                                            File(
                                                outcome.root,
                                                "EPUB/resources/${pdfPages.getJSONObject(i).getString("reference")}",
                                            )
                                        append(
                                            "<h2>第 ${i + 1} 页</h2><img src=\"${Uri.fromFile(image)}\" alt=\"原页\">"
                                        )
                                    }
                            }
                            return@runCatching MarkupPage(
                                MarkupDocuments.styledHtml(html, false, model.name, options),
                                "原件：${model.name}\n${original.length()} 字节\nSHA256：${PdfAssembly.sha256(original)}\n原 PDF 和每页对照图随 EPUB 保留。",
                                outcome,
                                options,
                            )
                        }
                        val source =
                            if (tab == 1) outcome
                            else
                                MarkupDocuments.open(
                                    context,
                                    File(model.source),
                                    mainPath = outcome.report?.optString("sourceMain"),
                                )
                        val body =
                            LocalConversions.readText(
                                context,
                                Uri.fromFile(source.main),
                                source.main.name,
                                preserveMarkup = true,
                            )
                        MarkupPage(
                            if (MarkupDocuments.isHtml(source.main.name)) body
                            else if (source.main.extension.equals("txt", true))
                                MarkupDocuments.styledHtml(
                                    "<pre>${org.jsoup.nodes.Entities.escape(body)}</pre>",
                                    false,
                                    model.name,
                                    options,
                                )
                            else MarkupDocuments.styledHtml(body, true, model.name, options),
                            body,
                            source,
                            options,
                        )
                    }
                }
        }
    val active = page?.getOrNull()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文档转换结果", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "convert" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.showMarkupOutput() }) { Text("导出包") }
                    TextButton(
                        enabled = active != null && printView == null,
                        onClick = {
                            val content = active ?: return@TextButton
                            notice = "正在准备系统打印"
                            val view = createMarkupWebView(context, content.source)
                            printView = view
                            var started = false
                            view.webViewClient =
                                object : DocumentWebClient(context, content.source) {
                                    override fun onPageFinished(web: WebView, url: String) {
                                        if (started) return
                                        started = true
                                        try {
                                            val documentName =
                                                model.name.substringBeforeLast('.').ifBlank {
                                                    "PaperForge"
                                                }
                                            val pdfName =
                                                if (documentName.endsWith(".pdf", true))
                                                    documentName
                                                else "$documentName.pdf"
                                            val delegate = web.createPrintDocumentAdapter(pdfName)
                                            val wrapped =
                                                object : PrintDocumentAdapter() {
                                                    override fun onStart() {
                                                        delegate.onStart()
                                                    }

                                                    override fun onLayout(
                                                        old: PrintAttributes?,
                                                        next: PrintAttributes,
                                                        signal: CancellationSignal,
                                                        callback: LayoutResultCallback,
                                                        extras: Bundle?,
                                                    ) {
                                                        delegate.onLayout(
                                                            old,
                                                            next,
                                                            signal,
                                                            callback,
                                                            extras,
                                                        )
                                                    }

                                                    override fun onWrite(
                                                        pages: Array<out PageRange>,
                                                        destination: ParcelFileDescriptor,
                                                        signal: CancellationSignal,
                                                        callback: WriteResultCallback,
                                                    ) {
                                                        delegate.onWrite(
                                                            pages,
                                                            destination,
                                                            signal,
                                                            callback,
                                                        )
                                                    }

                                                    override fun onFinish() {
                                                        try {
                                                            delegate.onFinish()
                                                        } finally {
                                                            web.destroy()
                                                            printView = null
                                                            notice = ""
                                                        }
                                                    }
                                                }
                                            val paper =
                                                when (content.print.paper) {
                                                    "A5" -> PrintAttributes.MediaSize.ISO_A5
                                                    "Letter" -> PrintAttributes.MediaSize.NA_LETTER
                                                    else -> PrintAttributes.MediaSize.ISO_A4
                                                }
                                            context
                                                .getSystemService(PrintManager::class.java)
                                                .print(
                                                    documentName,
                                                    wrapped,
                                                    PrintAttributes.Builder()
                                                        .setMediaSize(
                                                            if (content.print.landscape)
                                                                paper.asLandscape()
                                                            else paper.asPortrait()
                                                        )
                                                        .setMinMargins(
                                                            PrintAttributes.Margins.NO_MARGINS
                                                        )
                                                        .setResolution(
                                                            PrintAttributes.Resolution(
                                                                "paperforge",
                                                                "PaperForge",
                                                                300,
                                                                300,
                                                            )
                                                        )
                                                        .build(),
                                                )
                                            notice = "系统打印中可选择“另存为 PDF”。"
                                        } catch (error: Exception) {
                                            web.destroy()
                                            printView = null
                                            notice = error.message ?: "系统打印未能打开"
                                        }
                                    }
                                }
                            view.loadDataWithBaseURL(
                                Uri.fromFile(content.source.main).toString(),
                                content.html,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        },
                    ) {
                        Text("打印 / PDF")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("原件", "转换结果").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            FlowRow(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = !code, onClick = { code = false }, label = { Text("排版预览") })
                FilterChip(selected = code, onClick = { code = true }, label = { Text("源代码") })
                TextButton(onClick = { details = !details }) { Text("转换说明") }
            }
            if (notice.isNotBlank())
                Text(
                    notice,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            if (details && active != null)
                Column(
                    Modifier.heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        "主文档：${active.source.relative}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val warnings = active.source.report?.optJSONArray("warnings")
                    if (warnings == null || warnings.length() == 0)
                        Text(
                            "静态标题、列表、链接、图片与表格；资源包保留相对路径。打印和 PDF 使用手机系统打印界面。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    else
                        for (i in 0 until warnings.length()) Text(
                            "• ${warnings.getString(i)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            if (active != null) {
                if (code)
                    SelectionContainer(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            active.raw.take(200000) +
                                if (active.raw.length > 200000) "\n…界面仅展示前 20 万字符，完整内容在导出包。"
                                else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                else
                    key(active.source.main.path, tab) {
                        val view = remember {
                            createMarkupWebView(context, active.source) { file, page ->
                                    vm.openMarkupPdf(file, page)
                                }
                                .apply {
                                    loadDataWithBaseURL(
                                        Uri.fromFile(active.source.main).toString(),
                                        active.html,
                                        "text/html",
                                        "UTF-8",
                                        null,
                                    )
                                }
                        }
                        DisposableEffect(view) {
                            onDispose {
                                view.stopLoading()
                                view.destroy()
                            }
                        }
                        AndroidView(
                            factory = { view },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
            } else if (page == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else
                Column(Modifier.padding(24.dp)) {
                    Text(page?.exceptionOrNull()?.message ?: "暂时无法读取结果")
                    TextButton(onClick = { retry++ }) { Text("重新读取") }
                }
        }
    }
}

private fun createMarkupWebView(
    context: Context,
    source: MarkupSource,
    onPdf: ((File, Int) -> Unit)? = null,
) =
    WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = DocumentWebClient(context, source, onPdf)
    }

private open class DocumentWebClient(
    private val context: Context,
    private val source: MarkupSource,
    private val onPdf: ((File, Int) -> Unit)? = null,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme == "data" || uri.scheme == "about") return null
        if (uri.scheme == "file") {
            val file = runCatching { File(uri.path ?: "").canonicalFile }.getOrNull()
            val allowed =
                file != null &&
                    (file == source.main.canonicalFile ||
                        source.root?.let {
                            file.path.startsWith(it.canonicalPath + File.separator)
                        } == true)
            if (allowed) return null
        }
        return WebResourceResponse("text/plain", "UTF-8", byteArrayOf().inputStream())
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (
            request.url.scheme == "file" &&
                request.url.path?.endsWith(".pdf", true) == true &&
                onPdf != null
        ) {
            val file = File(request.url.path!!).canonicalFile
            if (
                source.root?.let { file.path.startsWith(it.canonicalPath + File.separator) } ==
                    true && file.isFile
            ) {
                val page =
                    Uri.decode(request.url.encodedFragment ?: "")
                        .substringAfter("page=", "1")
                        .substringBefore('&')
                        .toIntOrNull() ?: 1
                onPdf.invoke(file, page - 1)
            }
            return true
        }
        if (request.url.scheme in setOf("https", "http", "mailto")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
        }
        return false
    }
}

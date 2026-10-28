package cn.paperforge.formats

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.*
import android.view.View
import android.webkit.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Independent optional renderer. Input/output are granted descriptors, never external paths. */
class FormatService : Service() {
    companion object {
        const val ADAPTER = "ofd026-djvu054-embedded-fonts-v3"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Job? = null
    private var activeId = ""
    private val endpoint by lazy {
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val data = message.data
                val id = data.getString("id").orEmpty()
                val reply = message.replyTo
                if (message.what == 5 && id == activeId) {
                    active?.cancel()
                    Process.killProcess(Process.myPid())
                } else if (message.what == 1 && reply != null) {
                    if (active?.isActive == true) send(reply, id, 4, "格式引擎正在处理另一份文档")
                    else {
                        activeId = id
                        active =
                            scope.launch {
                                var input: ParcelFileDescriptor? = null
                                var output: ParcelFileDescriptor? = null
                                try {
                                    require(data.getInt("protocol") == 1) { "请更新手机格式引擎" }
                                    @Suppress("DEPRECATION")
                                    input =
                                        requireNotNull(
                                            data.getParcelable<ParcelFileDescriptor>("input")
                                        )
                                    @Suppress("DEPRECATION")
                                    output =
                                        requireNotNull(
                                            data.getParcelable<ParcelFileDescriptor>("output")
                                        )
                                    val options = JSONObject(data.getString("format") ?: "{}")
                                    convert(
                                        requireNotNull(input),
                                        requireNotNull(output),
                                        options,
                                    ) { percent, text ->
                                        send(reply, id, 2, text, percent)
                                    }
                                    send(
                                        reply,
                                        id,
                                        3,
                                        if (options.optString("operation") == "validate-pdfa")
                                            "PDF/A 校验完成"
                                        else "版面转换完成",
                                        100,
                                    )
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    send(reply, id, 4, error.message ?: "版面转换未完成")
                                } catch (error: LinkageError) {
                                    android.util.Log.e(
                                        "FormatService",
                                        "Optional engine dependency failure",
                                        error,
                                    )
                                    send(reply, id, 4, "格式引擎遇到兼容问题，请更新完整格式引擎；原件保留")
                                } finally {
                                    runCatching { input?.close() }
                                    runCatching { output?.close() }
                                }
                            }
                    }
                }
                true
            }
        )
    }

    private fun send(reply: Messenger, id: String, what: Int, detail: String, progress: Int = 0) {
        runCatching {
            reply.send(
                Message.obtain(null, what).apply {
                    data =
                        Bundle().apply {
                            putString("id", id)
                            putInt("protocol", 1)
                            putString("detail", detail)
                            putInt("progress", progress)
                        }
                }
            )
        }
    }

    override fun onBind(intent: Intent) = endpoint.binder

    override fun onUnbind(intent: Intent): Boolean {
        scope.launch {
            delay(1500)
            if (active?.isActive != true) Process.killProcess(Process.myPid())
        }
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun convert(
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
        options: JSONObject,
        progress: (Int, String) -> Unit,
    ) {
        if (options.optString("operation") == "validate-pdfa") {
            val temporary =
                File(cacheDir, "archive-${activeId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.pdf")
            try {
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(input).use { from ->
                        temporary.outputStream().use { to -> from.copyTo(to) }
                    }
                    val report =
                        cn.paperforge.archive.ArchiveValidator.validate(
                            temporary,
                            options.optString("target", "2b"),
                        )
                    ParcelFileDescriptor.AutoCloseOutputStream(output).use {
                        it.write(report.toString(2).toByteArray(Charsets.UTF_8))
                    }
                }
                progress(100, "PDF/A 校验完成")
            } finally {
                temporary.delete()
            }
            return
        }
        val type = options.optString("type").lowercase()
        require(type in setOf("ofd", "djvu", "djv") + NativeFormats.types) { "请选择支持的版式文档或电子书" }
        val dpi = options.optInt("dpi", 200).coerceIn(72, 600)
        val copy = File(cacheDir, "input-${activeId.replace(Regex("[^A-Za-z0-9_-]"),"_")}.$type")
        val hash =
            withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                ParcelFileDescriptor.AutoCloseInputStream(input).use { from ->
                    copy.outputStream().use { to ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            ensureActive()
                            val n = from.read(bytes)
                            if (n < 0) break
                            digest.update(bytes, 0, n)
                            to.write(bytes, 0, n)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        val native = type in NativeFormats.types
        val style =
            listOf(
                    options.optDouble("widthPt", 595.0),
                    options.optDouble("heightPt", 842.0),
                    options.optDouble("fontSize", 12.0),
                    options.optBoolean("includeHeaders", true),
                    options.optBoolean("mathSource", false),
                )
                .joinToString("|")
        val styleHash =
            MessageDigest.getInstance("SHA-256")
                .digest(style.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
                .take(24)
        val folder =
            File(
                    cacheDir,
                    if (native) "pages/${NativeFormats.VERSION}-$type-$hash-$dpi-$styleHash"
                    else "pages/$ADAPTER-$hash-$dpi",
                )
                .apply { mkdirs() }
        val manifest = File(folder, "document.json")
        var document =
            if (native)
                withContext(Dispatchers.IO) {
                    NativeFormats.render(copy, folder, hash, type, dpi, options, progress)
                }
            else if (manifest.exists()) JSONObject(manifest.readText()) else null
        if (
            document?.getJSONArray("pages")?.let { pages ->
                (0 until pages.length()).all {
                    File(folder, pages.getJSONObject(it).getString("image")).exists()
                }
            } != true
        ) {
            var pending: CompletableDeferred<JSONObject>? = CompletableDeferred()
            val web = WebView(this)
            try {
                web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                web.setBackgroundColor(Color.WHITE)
                web.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    textZoom = 100
                    minimumFontSize = 1
                    useWideViewPort = true
                }
                web.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun response(value: String) {
                            Handler(Looper.getMainLooper()).post {
                                runCatching { JSONObject(value) }
                                    .onSuccess { pending?.complete(it) }
                                    .onFailure { pending?.completeExceptionally(it) }
                            }
                        }
                    },
                    "NativeLayout",
                )
                web.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            val path = request.url.path.orEmpty()
                            return when {
                                request.url.host != "paperforge.local" ->
                                    WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        404,
                                        "Not found",
                                        emptyMap(),
                                        "".byteInputStream(),
                                    )
                                path == "/source" ->
                                    WebResourceResponse(
                                        "application/octet-stream",
                                        null,
                                        copy.inputStream(),
                                    )
                                path == "/index.html" || path == "/adapters.js" ->
                                    WebResourceResponse(
                                        if (path.endsWith(".js")) "application/javascript"
                                        else "text/html",
                                        "UTF-8",
                                        assets.open("layout$path"),
                                    )
                                else ->
                                    WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            pending?.completeExceptionally(
                                IllegalStateException("格式渲染进程已停止，可从已完成页面继续")
                            )
                            return true
                        }
                    }
                suspend fun result(): JSONObject {
                    val value = withTimeout(120000) { requireNotNull(pending).await() }
                    check(value.optString("kind") != "error") {
                        value.optString("detail", "文档解析未完成")
                    }
                    return value
                }
                suspend fun command(code: String): JSONObject {
                    pending = CompletableDeferred()
                    web.evaluateJavascript(code, null)
                    return result()
                }
                web.loadUrl("https://paperforge.local/index.html")
                result()
                progress(5, "正在解析原始版式")
                val count = command("PFLayout.open(${JSONObject.quote(type)})").getInt("pages")
                require(count > 0) { "文档没有可转换页面" }
                val pages = JSONArray()
                for (index in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    progress(8 + 78 * index / count, "转换版面 · 第 ${index+1} / $count 页")
                    val image = File(folder, "page-${index+1}.png")
                    val metadata = File(folder, "page-${index+1}.json")
                    val info =
                        if (image.exists() && metadata.exists()) JSONObject(metadata.readText())
                        else {
                            val info = command("PFLayout.page($index,$dpi)")
                            val width = info.getInt("width")
                            val height = info.getInt("height")
                            require(width > 0 && height > 0) { "页面尺寸不可用" }
                            val density = resources.displayMetrics.density
                            val nativeWidth = (width * density).toInt()
                            val nativeHeight = (height * density).toInt()
                            web.measure(
                                View.MeasureSpec.makeMeasureSpec(
                                    nativeWidth,
                                    View.MeasureSpec.EXACTLY,
                                ),
                                View.MeasureSpec.makeMeasureSpec(
                                    nativeHeight,
                                    View.MeasureSpec.EXACTLY,
                                ),
                            )
                            web.layout(0, 0, nativeWidth, nativeHeight)
                            web.invalidate()
                            delay(75)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                val canvas = Canvas(bitmap)
                                canvas.scale(1 / density, 1 / density)
                                web.draw(canvas)
                                withContext(Dispatchers.IO) {
                                    val temp = File(image.path + ".part")
                                    temp.outputStream().use {
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                    check(temp.renameTo(image)) { "无法保存页面结果" }
                                }
                            } finally {
                                bitmap.recycle()
                            }
                            info.put("image", image.name)
                            atomic(metadata, info)
                            info
                        }
                    pages.put(info)
                }
                document =
                    JSONObject()
                        .put("adapter", ADAPTER)
                        .put("sourceSha256", hash)
                        .put("type", type)
                        .put("dpi", dpi)
                        .put("pages", pages)
                atomic(manifest, requireNotNull(document))
            } finally {
                pending = null
                web.stopLoading()
                web.destroy()
            }
        }
        progress(90, "正在打包版面结果")
        withContext(Dispatchers.IO) {
            ZipOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry("document.json"))
                zip.write(requireNotNull(document).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                requireNotNull(document)
                    .optString("nativePdf")
                    .takeIf { it.isNotBlank() }
                    ?.let { name ->
                        zip.putNextEntry(ZipEntry(name))
                        File(folder, name).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                val pages = requireNotNull(document).getJSONArray("pages")
                for (index in 0 until pages.length()) {
                    ensureActive()
                    val name = pages.getJSONObject(index).getString("image")
                    zip.putNextEntry(ZipEntry(name))
                    File(folder, name).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            copy.delete()
        }
    }

    private suspend fun atomic(file: File, value: JSONObject) =
        withContext(Dispatchers.IO) {
            val atomic = android.util.AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(value.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
}

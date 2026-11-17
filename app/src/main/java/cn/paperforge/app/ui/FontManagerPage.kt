package cn.paperforge.app.ui

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.paperforge.app.pdf.FontStore
import cn.paperforge.app.pdf.NativeFontLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE = "永和九年 The quick brown fox 0123"

/**
 * Fonts in one place: what is installed (built in, imported, downloaded) with a preview, importing a .ttf, and
 * downloading open fonts from the catalog. Opened from Settings and from the PDF editor; with [pdfFont] (a font the
 * PDF uses that PaperForge cannot ship, such as Arial or SimSun) its open alternatives come first.
 */
@Composable
fun FontManagerPage(onDismiss: () -> Unit, pdfFont: String? = null, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Family names and coverage follow the page's own (Chinese) text, so one page never mixes two languages; the
    // catalog keeps English names for when the page itself is translated.
    val english = false
    var tab by remember { mutableIntStateOf(if (pdfFont != null) 1 else 0) }
    var query by remember { mutableStateOf("") }
    // Download tab filter: a style, or "CJK" for anything with Chinese, Japanese or Korean.
    var kind by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val installed by produceState(emptyList<FontStore.Installed>(), refresh) { value = withContext(Dispatchers.IO) { FontStore.installed(context) } }
    val installedFamilies by produceState(emptySet<String>(), refresh) { value = withContext(Dispatchers.IO) { FontStore.installedFamilies(context) } }
    val catalog = remember { runCatching { FontStore.catalog(context) }.getOrNull() }
    val suggested = remember(pdfFont) { pdfFont?.let { FontStore.alternatives(context, it) }.orEmpty() }
    val builtIn = remember { NativeFontLibrary.available(context) }
    val progress = remember { mutableStateMapOf<String, Pair<Float, String>>() }
    val jobs = remember { mutableStateMapOf<String, Job>() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    message =
                        runCatching { withContext(Dispatchers.IO) { FontStore.import(context, uri) } }
                            .fold({ "已导入：${it.name}" }, { it.message ?: "无法导入这个字体" })
                    refresh++
                    onChanged()
                }
        }
    fun matches(vararg texts: String) = query.isBlank() || texts.any { it.contains(query.trim(), ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("font-manager"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    Text("字体管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { picker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream")) },
                        modifier = Modifier.testTag("font-import"),
                    ) {
                        Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入 .ttf")
                    }
                }
                TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("已安装") }, modifier = Modifier.testTag("font-tab-installed"))
                    Tab(tab == 1, { tab = 1 }, text = { Text("下载字体") }, modifier = Modifier.testTag("font-tab-download"))
                }
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("font-manager-search"),
                    placeholder = { Text("搜索字体、语言或要替代的字体（如 Arial、宋体）") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                )
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp).testTag("font-manager-message"))
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 0) {
                        val added = installed.filter { matches(it.name, it.family.orEmpty()) }
                        item { SectionTitle("导入与下载的字体（${added.size}）") }
                        if (added.isEmpty())
                            item { Text("还没有。可导入手机里的 .ttf，或在“下载字体”中安装开源字体。", style = MaterialTheme.typography.bodySmall) }
                        items(added, key = { it.sha256 }) { font ->
                            FontCard(
                                font.name,
                                listOfNotNull(if (font.origin == "download") "已下载" else "已导入", font.license, "%.1f MB".format(font.file.length() / 1048576.0)).joinToString(" · "),
                                remember(font.file) { runCatching { FontFamily(Typeface.createFromFile(font.file)) }.getOrNull() },
                            ) {
                                IconButton(onClick = {
                                    FontStore.delete(context, font.sha256)
                                    message = "已删除：${font.name}（已编辑并保存的 PDF 不受影响）"
                                    refresh++
                                    onChanged()
                                }) { Icon(Icons.Default.DeleteOutline, "删除 ${font.name}") }
                            }
                        }
                        val inside = builtIn.filter { matches(it.nameZh, it.nameEn, it.coverage) }
                        item { SectionTitle("内置字体（${inside.size}）") }
                        items(inside, key = { "builtin/" + it.id }) { font ->
                            FontCard(
                                if (english) font.nameEn else font.nameZh,
                                "内置 · " + font.coverage,
                                remember(font.asset) { runCatching { FontFamily(Typeface.createFromAsset(context.assets, font.asset)) }.getOrNull() },
                            ) {}
                        }
                    } else {
                        if (!FontStore.canDownload(context))
                            item { Notice("这个版本不含联网功能，无法在应用内下载。可在电脑或浏览器下载 .ttf 后用右上角“导入”。") }
                        if (catalog == null) item { Notice("字体目录不可用。") }
                        if (suggested.isNotEmpty())
                            item {
                                Notice("PDF 使用的“${pdfFont!!.substringAfter('+')}”是商业字体，不能随 PaperForge 分发。下面的开源字体与它字形相近或等宽，可下载后用于编辑。")
                            }
                        item {
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val kinds = listOf(null to "全部", "CJK" to "中文", "SANS" to "无衬线", "SERIF" to "衬线", "HAND" to "手写", "SCRIPT" to "楷书/书法", "MONO" to "等宽", "DISPLAY" to "标题")
                                items(kinds.size) { i ->
                                    val (key, label) = kinds[i]
                                    FilterChip(kind == key, { kind = key }, { Text(label) }, modifier = Modifier.testTag("font-kind-${key ?: "all"}"))
                                }
                            }
                        }
                        fun cjk(f: FontStore.Family) = listOf("中文", "繁", "简", "日文", "韩文").any { it in f.coverageZh }
                        val families = (suggested + catalog?.families.orEmpty().filter { it !in suggested })
                            .filter { matches(it.nameZh, it.nameEn, it.coverageZh, it.coverageEn, *it.replaces.toTypedArray()) }
                            .filter { f -> when (kind) { null -> true; "CJK" -> cjk(f); else -> f.category == kind } }
                        items(families, key = { "family/" + it.id }) { family ->
                            val have = family.id in installedFamilies
                            val running = progress[family.id]
                            OutlinedCard(Modifier.fillMaxWidth().testTag("font-family-${family.id}"), shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(if (english) family.nameEn else family.nameZh, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                (if (english) family.coverageEn else family.coverageZh) + " · " + family.license + " · " + "%.1f MB".format(family.bytes / 1048576.0),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        when {
                                            have -> AssistChip({}, { Text("已安装") }, leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) })
                                            running != null ->
                                                TextButton(onClick = { jobs[family.id]?.cancel() }) { Text("取消") }
                                            else ->
                                                FilledTonalButton(
                                                    enabled = FontStore.canDownload(context),
                                                    onClick = {
                                                        progress[family.id] = 0f to ""
                                                        jobs[family.id] =
                                                            scope.launch {
                                                                message =
                                                                    runCatching {
                                                                            withContext(Dispatchers.IO) {
                                                                                FontStore.download(context, family) { done, total, host ->
                                                                                    progress[family.id] = (done.toFloat() / total.coerceAtLeast(1)) to host
                                                                                }
                                                                            }
                                                                        }
                                                                        .fold(
                                                                            { "已安装：${if (english) family.nameEn else family.nameZh}，可在 PDF 编辑的“字体”中选用" },
                                                                            { if (it is kotlinx.coroutines.CancellationException) "已取消下载" else it.message ?: "下载失败" },
                                                                        )
                                                                progress.remove(family.id)
                                                                jobs.remove(family.id)
                                                                refresh++
                                                                onChanged()
                                                            }
                                                    },
                                                    modifier = Modifier.testTag("font-download-${family.id}"),
                                                ) { Text("下载") }
                                        }
                                    }
                                    if (running != null) {
                                        LinearProgressIndicator({ running.first }, Modifier.fillMaxWidth().padding(top = 8.dp))
                                        if (running.second.isNotBlank())
                                            Text("来源：${running.second} · 下载后校验完整性", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                "字体来自 Google Fonts 开源字体库（固定版本），由多个镜像提供；下载后逐一核对文件大小与校验值，不符即换下一个来源。只列出 OFL / Apache 许可、允许嵌入 PDF 的字体。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Notice(text: String) =
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }

@Composable
private fun FontCard(title: String, detail: String, family: FontFamily?, actions: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (family != null)
                    Text(SAMPLE, fontFamily = family, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            actions()
        }
    }
}

package cn.paperforge.app.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.*
import cn.paperforge.app.reader.OfflineSpeech
import cn.paperforge.app.reader.StudyNotes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderNavigationSheet(
    file: File,
    info: ReaderInfo,
    bookmarks: Set<Int>,
    page: Int,
    onGoTo: (ReaderDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val links by
        produceState(emptyList<ReaderLink>(), file, page) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { ReaderDocument.links(file, page) }.getOrDefault(emptyList())
                }
        }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(.8f).padding(horizontal = 16.dp)) {
            Text("目录与导航", style = MaterialTheme.typography.titleLarge)
            TabRow(tab) {
                listOf("目录", "书签", "本页链接").forEachIndexed { index, title ->
                    Tab(tab == index, { tab = index }, text = { Text(title) })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                when (tab) {
                    0 -> {
                        if (info.outline.isEmpty())
                            item { Text("这份 PDF 没有内置目录。可添加书签，或使用页面缩略图。", Modifier.padding(16.dp)) }
                        itemsIndexed(info.outline) { _, entry ->
                            TextButton(
                                onClick = {
                                    onGoTo(entry.destination)
                                    onDismiss()
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(start = (entry.depth.coerceAtMost(5) * 14).dp),
                            ) {
                                Text(entry.title, Modifier.weight(1f))
                                Text(info.labels.getOrNull(entry.page) ?: "${entry.page + 1}")
                            }
                        }
                    }
                    1 -> {
                        if (bookmarks.isEmpty())
                            item { Text("还没有书签，可在阅读设置中收藏当前页。", Modifier.padding(16.dp)) }
                        itemsIndexed(bookmarks.sorted()) { _, index ->
                            TextButton(
                                onClick = {
                                    onGoTo(ReaderDestination(index))
                                    onDismiss()
                                }
                            ) {
                                Text(
                                    "${info.labels.getOrNull(index)?.plus(" · ") ?: ""}第 ${index + 1} 页"
                                )
                            }
                        }
                    }
                    else -> {
                        if (links.isEmpty())
                            item { Text("本页没有可用的 PDF 链接。", Modifier.padding(16.dp)) }
                        itemsIndexed(links) { _, link ->
                            TextButton(
                                onClick = {
                                    if (link.page != null) {
                                        onGoTo(link.destination ?: ReaderDestination(link.page))
                                        onDismiss()
                                    } else
                                        link.uri?.let { uri ->
                                            runCatching { uriHandler.openUri(uri) }
                                                .onFailure { error = "系统无法打开此链接：${it.message}" }
                                        }
                                }
                            ) {
                                Text(link.uri ?: "跳到第 ${(link.page ?: 0) + 1} 页")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTextSheet(
    file: File,
    page: Int,
    documentId: String,
    title: String,
    point: PointN?,
    onFocus: (ReaderTextBlock) -> Unit,
    onLocate: () -> Unit,
    onDismiss: () -> Unit,
    onMark: (ReaderTextBlock, String) -> Unit,
    initialBlocks: List<ReaderTextBlock>? = null,
    onRegion: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperForgeApp
    var recognize by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    var excerpt by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var speed by remember { mutableFloatStateOf(1f) }
    val blocks by
        produceState(emptyList<ReaderTextBlock>(), file, page, recognize, initialBlocks) {
            loading = true
            error = null
            runCatching {
                    if (recognize == 0 && initialBlocks != null) initialBlocks
                    else PageTextSource.load(context, file, page, app.ocrEngine, recognize > 0)
                }
                .onSuccess { value = it }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    error = it.message ?: "文字读取未完成"
                }
            loading = false
        }
    val ordered = remember(blocks, order) { ReaderDocument.order(blocks, order) }
    val text = remember(ordered) { ordered.joinToString("\n\n") { it.text } }
    val starts =
        remember(ordered) { ordered.runningFold(0) { sum, block -> sum + block.text.length + 2 } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val speech = remember { OfflineSpeech(context) }
    val speechState by speech.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, speech) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) speech.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            speech.close()
        }
    }
    LaunchedEffect(ordered) {
        selected =
            point?.let { p ->
                ordered
                    .indexOfFirst { p.x in it.left..it.right && p.y in it.top..it.bottom }
                    .takeIf { it >= 0 }
            } ?: 0
        ordered.getOrNull(selected)?.let(onFocus)
        if (selected > 0) list.scrollToItem(selected)
    }
    ModalBottomSheet(
        onDismissRequest = {
            speech.pause()
            onDismiss()
        }
    ) {
        Column(
            Modifier.fillMaxHeight(.9f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("文字与朗读 · 第 ${page + 1} 页", style = MaterialTheme.typography.titleLarge)
            Text("长按文字选择复制；原生文字优先。扫描页或缺字区域可补识别。", style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(onClick = { recognize++ }, enabled = !loading) { Text("补识别此页") }
                if (onRegion != null)
                    TextButton(onClick = onRegion, enabled = !loading) { Text("区域识字") }
                TextButton(
                    onClick = {
                        onLocate()
                        onDismiss()
                    }
                ) {
                    Text("回到原页")
                }
                TextButton(onClick = { excerpt = true }, enabled = ordered.isNotEmpty()) {
                    Text("保存摘录")
                }
                listOf("高亮" to "highlight", "下划线" to "underline", "删除线" to "strikeout").forEach {
                    (label, kind) ->
                    TextButton(
                        onClick = { ordered.getOrNull(selected)?.let { onMark(it, kind) } },
                        enabled = ordered.isNotEmpty(),
                    ) {
                        Text(label)
                    }
                }
                listOf("按行" to 0, "先左后右" to 1, "先右后左" to 2).forEach { (label, value) ->
                    FilterChip(order == value, { order = value }, label = { Text(label) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(speechState.message, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(
                            onClick = {
                                if (speechState.playing) speech.pause()
                                else
                                    speech.play(
                                        text,
                                        speechState.offset.takeIf { it < text.length } ?: 0,
                                    )
                            },
                            enabled = speechState.ready && text.isNotBlank(),
                        ) {
                            Text(if (speechState.playing) "暂停" else "朗读 / 继续")
                        }
                        TextButton(
                            onClick = { speech.play(text, starts.getOrElse(selected) { 0 }) },
                            enabled = speechState.ready && text.isNotBlank(),
                        ) {
                            Text("从选段开始")
                        }
                        TextButton(
                            onClick = {
                                runCatching {
                                        context.startActivity(
                                            Intent("com.android.settings.TTS_SETTINGS")
                                        )
                                    }
                                    .onFailure { error = "系统没有提供朗读设置入口" }
                            }
                        ) {
                            Text("系统声音")
                        }
                    }
                    Row {
                        Text("语速 %.1f×".format(speed), style = MaterialTheme.typography.labelMedium)
                        Slider(
                            speed,
                            {
                                speed = it
                                speech.speed(it)
                            },
                            valueRange = 0.5f..2f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                if (ordered.isEmpty() && !loading)
                    item { Text("本页没有可读取的文字，点击“补识别此页”使用手机离线 OCR。", Modifier.padding(24.dp)) }
                itemsIndexed(ordered) { index, block ->
                    val highlight = MaterialTheme.colorScheme.tertiaryContainer
                    val value = buildAnnotatedString {
                        append(block.text)
                        val a = (speechState.offset - starts[index]).coerceIn(0, length)
                        val b = (speechState.end - starts[index]).coerceIn(0, length)
                        if (speechState.playing && b > a)
                            addStyle(SpanStyle(background = highlight), a, b)
                    }
                    Surface(
                        color =
                            if (selected == index) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        onClick = {
                            selected = index
                            onFocus(block)
                        },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (block.source == "native") "原生文字" else "离线 OCR",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(value, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
    if (excerpt) {
        var quote by remember { mutableStateOf(ordered.getOrNull(selected)?.text ?: "") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { excerpt = false },
            title = { Text("保存摘录 · 第 ${page + 1} 页") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(quote, { quote = it }, label = { Text("摘录文字") })
                    OutlinedTextField(note, { note = it }, label = { Text("笔记") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                    withContext(Dispatchers.IO) {
                                        StudyNotes.add(
                                            context,
                                            documentId,
                                            title,
                                            page,
                                            quote,
                                            note,
                                        )
                                    }
                                }
                                .onSuccess {
                                    excerpt = false
                                    notice = "摘录已保存，可从工具页查看和导出"
                                }
                                .onFailure { error = it.message }
                        }
                    },
                    enabled = quote.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { excerpt = false }) { Text("取消") } },
        )
    }
}

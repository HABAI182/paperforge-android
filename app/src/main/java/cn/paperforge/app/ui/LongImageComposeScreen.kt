package cn.paperforge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.pdf.*
import java.io.File
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongImageComposeScreen(workspace: LongImageWorkspace) {
    val draft by workspace.draft.collectAsState()
    val working by workspace.working.collectAsState()
    val model = draft ?: return
    var pair by remember(model.taskId) { mutableIntStateOf(1) }
    pair = pair.coerceIn(1, model.sources.lastIndex.coerceAtLeast(1))
    var outputSettings by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }
    var valid by remember { mutableStateOf(true) }
    val active = model.sources.size > 1
    val seam = if (active) model.seam(pair) else StitchSeam()
    val preview by
        produceState<Result<android.graphics.Bitmap>?>(
            null,
            model.taskId,
            pair,
            model.sources,
            seam,
            overlay,
        ) {
            if (active) {
                delay(80)
                value =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            StitchPreview.render(
                                File(model.sources[pair - 1].path),
                                File(model.sources[pair].path),
                                seam,
                                overlay,
                            )
                        }
                    }
            }
        }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, workspace) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP)
                (context.applicationContext as PaperForgeApp).backgroundScope.launch {
                    runCatching { workspace.persist() }
                }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler { workspace.close() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("长图与票据拼接") },
                navigationIcon = {
                    IconButton(onClick = workspace::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = workspace::generate,
                        enabled = working.isBlank() && valid,
                    ) {
                        Text("生成长图")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { order = true }, enabled = working.isBlank()) {
                    Text("${model.sources.size} 张 · 调整顺序")
                }
                OutlinedButton(
                    onClick = workspace::analyze,
                    enabled = working.isBlank() && active,
                ) {
                    Text("自动识别接缝")
                }
            }
            if (working.isNotBlank()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(working)
            }
            if (active) {
                ScrollableTabRow(selectedTabIndex = pair - 1) {
                    (1 until model.sources.size).forEach { index ->
                        Tab(
                            selected = pair == index,
                            onClick = { pair = index },
                            text = { Text("${index} → ${index+1}") },
                        )
                    }
                }
                preview?.fold(
                    onSuccess = {
                        Image(
                            it.asImageBitmap(),
                            "拼接接缝预览",
                            Modifier.fillMaxWidth()
                                .height(260.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        )
                    },
                    onFailure = {
                        Text(it.message ?: "预览未能打开", color = MaterialTheme.colorScheme.error)
                    },
                ) ?: Box(Modifier.fillMaxWidth().height(120.dp)) { CircularProgressIndicator() }
                Text(
                    if (overlay) "上：半透明叠加对照；下：下一张保留部分" else "上：上一张底部；下：下一张保留部分",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        seam.overlap == 0f,
                        { workspace.seam(pair, StitchSeam()) },
                        label = { Text("顺序拼接") },
                        enabled = working.isBlank(),
                    )
                    FilterChip(
                        seam.overlap > 0f,
                        {
                            workspace.seam(
                                pair,
                                seam.copy(
                                    overlap = seam.overlap.takeIf { it > 0 } ?: .2f,
                                    mode = "MANUAL",
                                ),
                            )
                        },
                        label = { Text("重叠拼接") },
                        enabled = working.isBlank(),
                    )
                    FilterChip(overlay, { overlay = !overlay }, label = { Text("叠加对照") })
                }
                if (seam.mode == "UNRESOLVED")
                    Text("未找到明确重叠，已保留顺序，可手动调整。", style = MaterialTheme.typography.bodySmall)
                if (seam.mode == "AUTO")
                    Text(
                        "重叠建议 · 匹配 ${(seam.confidence*100).toInt()}% · 请对照接缝",
                        style = MaterialTheme.typography.bodySmall,
                    )
                Text("去除下一张顶部重复部分 · ${"%.1f".format(seam.overlap*100)}%")
                Slider(
                    seam.overlap,
                    { workspace.seam(pair, seam.copy(overlap = it, mode = "MANUAL")) },
                    valueRange = 0f..0.95f,
                    enabled = working.isBlank(),
                )
                Text("水平对齐 · ${"%+.1f".format(seam.shift*100)}%")
                Slider(
                    seam.shift,
                    { workspace.seam(pair, seam.copy(shift = it, mode = "MANUAL")) },
                    valueRange = -0.2f..0.2f,
                    enabled = working.isBlank(),
                )
                Text("水平偏移会补足留白，保留两侧内容。", style = MaterialTheme.typography.bodySmall)
            } else Text("单张图片按原比例分段输出。")
            OutlinedButton(onClick = { outputSettings = !outputSettings }) {
                Text(
                    if (outputSettings) "收起输出设置"
                    else "输出设置 · ${model.options.width}px · ${model.options.format.uppercase()}"
                )
            }
            if (outputSettings) LongImageControls(model.options, workspace::options, { valid = it })
            Text("草稿自动保存；原图与顺序、接缝一并保留。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (order)
        ModalBottomSheet(onDismissRequest = { order = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("图片顺序", style = MaterialTheme.typography.titleLarge)
                model.sources.forEachIndexed { index, source ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "${index+1}. ${source.name}",
                            Modifier.weight(1f).padding(vertical = 12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { workspace.move(index, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, "上移第 ${index+1} 张")
                        }
                        IconButton(
                            onClick = { workspace.move(index, 1) },
                            enabled = index < model.sources.lastIndex,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, "下移第 ${index+1} 张")
                        }
                        IconButton(
                            onClick = { workspace.remove(index) },
                            enabled = model.sources.size > 1,
                        ) {
                            Icon(Icons.Default.RemoveCircleOutline, "排除第 ${index+1} 张")
                        }
                    }
                }
            }
        }
}

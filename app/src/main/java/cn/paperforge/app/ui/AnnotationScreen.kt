package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.PdfMark
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.JSONArray

private fun markLabel(kind: String) =
    mapOf(
        "pen" to "画笔",
        "signature" to "签名笔迹",
        "highlight" to "高亮",
        "underline" to "下划线",
        "strikeout" to "删除线",
        "rect" to "矩形",
        "text" to "文字",
        "note" to "便签",
        "label" to "图章",
        "redact" to "脱敏区域",
    )[kind] ?: "批注"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationScreen(vm: MainViewModel) {
    val doc by vm.selected.collectAsState()
    val marks by vm.marks.collectAsState()
    val busy by vm.busy.collectAsState()
    val preview by vm.marksPreview.collectAsState()
    val originalCount by vm.managedMarksCount.collectAsState()
    val undo by vm.canUndoMarks.collectAsState()
    val redo by vm.canRedoMarks.collectAsState()
    val file = (preview ?: doc?.pdfPath)?.let { File(it) }
    val session by
        produceState<PdfSession?>(null, file) {
            value = withContext(Dispatchers.IO) { file?.let { PdfSession(it) } }
        }
    val active = session
    DisposableEffect(active) { onDispose { active?.close() } }
    var page by remember { mutableIntStateOf(vm.annotationPage.value) }
    val bitmap by
        produceState<Bitmap?>(null, active, page) {
            value =
                withContext(Dispatchers.IO) {
                    active?.let { it.render(page.coerceIn(0, it.count - 1)) }
                }
        }
    var tool by remember { mutableStateOf("pen") }
    var color by remember { mutableIntStateOf(android.graphics.Color.RED) }
    var width by remember { mutableFloatStateOf(.004f) }
    var current by remember { mutableStateOf<PdfMark?>(null) }
    var textAt by remember { mutableStateOf<PointN?>(null) }
    var text by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var editable by remember { mutableStateOf(true) }
    var showList by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PdfMark?>(null) }
    val prefs = LocalContext.current.getSharedPreferences("signature", 0)
    var signature by remember {
        mutableStateOf(
            runCatching { PdfMark.decode(JSONArray(prefs.getString("strokes", "[]"))) }
                .getOrDefault(emptyList())
        )
    }
    val currentMarks by rememberUpdatedState(marks)
    fun add(mark: PdfMark) {
        vm.updateMarks(currentMarks + mark)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批注 · 第 ${page+1} 页") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "reader" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(enabled = undo && !busy, onClick = { vm.undoMarks() }) { Text("撤销") }
                    TextButton(enabled = redo && !busy, onClick = { vm.redoMarks() }) { Text("重做") }
                    TextButton(
                        enabled = (marks.isNotEmpty() || originalCount > 0) && !busy,
                        onClick = { confirm = true },
                    ) {
                        Text("保存副本")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                        "pen" to "画笔",
                        "highlight" to "高亮框",
                        "underline" to "下划线",
                        "strikeout" to "删除线",
                        "rect" to "矩形",
                        "text" to "文字",
                        "note" to "便签",
                        "label" to "文字图章",
                        "signature" to "手写签名",
                        "stamp" to "复用签名",
                        "redact" to "安全脱敏",
                    )
                    .forEach { (id, label) ->
                        FilterChip(
                            tool == id,
                            { tool = id },
                            label = { Text(label) },
                            enabled = id != "stamp" || signature.isNotEmpty(),
                        )
                    }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                        android.graphics.Color.RED,
                        android.graphics.Color.BLUE,
                        android.graphics.Color.BLACK,
                        android.graphics.Color.YELLOW,
                    )
                    .forEach { c ->
                        Box(
                            Modifier.padding(5.dp)
                                .size(24.dp)
                                .background(Color(c), androidx.compose.foundation.shape.CircleShape)
                                .border(
                                    if (c == color) 3.dp else 1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                                .clickable { color = c }
                        )
                    }
                Slider(
                    width,
                    { width = it },
                    valueRange = 0.002f..0.014f,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                if (tool == "redact") "拖框选择遮盖区域；导出将所有页重绘为图片并移除原文字与附件。"
                else if (tool == "stamp") "拖框放置已保存的签名。" else "批注草稿自动保存；支持可编辑批注或固定导出。",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val b = bitmap
                if (b == null) CircularProgressIndicator()
                else {
                    val ratio = b.width.toFloat() / b.height
                    val fit =
                        if (maxWidth / maxHeight > ratio)
                            Modifier.fillMaxHeight().aspectRatio(ratio)
                        else Modifier.fillMaxWidth().aspectRatio(ratio)
                    Box(fit) {
                        Image(b.asImageBitmap(), "批注页面 ${page+1}", Modifier.fillMaxSize())
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(
                                tool,
                                page,
                                color,
                                width,
                                signature,
                            ) {
                                fun point(o: Offset) =
                                    PointN(
                                        (o.x / size.width).coerceIn(0f, 1f),
                                        (o.y / size.height).coerceIn(0f, 1f),
                                    )
                                if (tool in setOf("text", "note", "label"))
                                    detectTapGestures {
                                        textAt = point(it)
                                        text = ""
                                    }
                                else
                                    detectDragGestures(
                                        onDragStart = {
                                            current =
                                                PdfMark(page, tool, listOf(point(it)), color, width)
                                        },
                                        onDragCancel = { current = null },
                                        onDragEnd = {
                                            current?.let { mark ->
                                                if (tool == "stamp") {
                                                    val a = mark.points.first()
                                                    val z = mark.points.last()
                                                    val left = min(a.x, z.x)
                                                    val top = min(a.y, z.y)
                                                    val w = abs(z.x - a.x)
                                                    val h = abs(z.y - a.y)
                                                    if (w > .005f && h > .005f)
                                                        vm.updateMarks(
                                                            currentMarks +
                                                                signature.map { s ->
                                                                    s.copy(
                                                                        id =
                                                                            java.util.UUID
                                                                                .randomUUID()
                                                                                .toString(),
                                                                        page = page,
                                                                        points =
                                                                            s.points.map {
                                                                                PointN(
                                                                                    left + it.x * w,
                                                                                    top + it.y * h,
                                                                                )
                                                                            },
                                                                        width = s.width * w,
                                                                    )
                                                                }
                                                        )
                                                } else add(mark)
                                            }
                                            current = null
                                        },
                                    ) { change, _ ->
                                        change.consume()
                                        current =
                                            current?.let { mark ->
                                                mark.copy(
                                                    points =
                                                        if (tool in listOf("pen", "signature"))
                                                            mark.points + point(change.position)
                                                        else
                                                            listOf(
                                                                mark.points.first(),
                                                                point(change.position),
                                                            )
                                                )
                                            }
                                    }
                            }
                        ) {
                            (marks.filter { it.page == page } + listOfNotNull(current)).forEach {
                                mark ->
                                if (mark.points.isEmpty()) return@forEach
                                val a = mark.points.first()
                                val z = mark.points.last()
                                val ink =
                                    if (mark.kind == "redact") Color.Black else Color(mark.color)
                                when (mark.kind) {
                                    "rect",
                                    "highlight",
                                    "redact",
                                    "stamp" ->
                                        drawRect(
                                            if (mark.kind == "highlight") ink.copy(alpha = .3f)
                                            else ink,
                                            Offset(
                                                min(a.x, z.x) * size.width,
                                                min(a.y, z.y) * size.height,
                                            ),
                                            Size(
                                                abs(z.x - a.x) * size.width,
                                                abs(z.y - a.y) * size.height,
                                            ),
                                            style =
                                                if (mark.kind in listOf("rect", "stamp"))
                                                    Stroke(
                                                        mark.width * min(size.width, size.height)
                                                    )
                                                else androidx.compose.ui.graphics.drawscope.Fill,
                                        )
                                    "underline",
                                    "strikeout" -> {
                                        val y =
                                            if (mark.kind == "underline") max(a.y, z.y)
                                            else (a.y + z.y) / 2
                                        drawLine(
                                            ink,
                                            Offset(min(a.x, z.x) * size.width, y * size.height),
                                            Offset(max(a.x, z.x) * size.width, y * size.height),
                                            mark.width * min(size.width, size.height),
                                        )
                                    }
                                    "note",
                                    "label",
                                    "text" ->
                                        drawContext.canvas.nativeCanvas.drawText(
                                            if (mark.kind == "note") "▣" else mark.text,
                                            a.x * size.width,
                                            a.y * size.height,
                                            android.graphics
                                                .Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                                .apply {
                                                    this.color = mark.color
                                                    textSize = max(10f, mark.width * size.width * 5)
                                                },
                                        )
                                    else -> {
                                        val path = Path()
                                        mark.points.forEachIndexed { i, p ->
                                            if (i == 0)
                                                path.moveTo(p.x * size.width, p.y * size.height)
                                            else path.lineTo(p.x * size.width, p.y * size.height)
                                        }
                                        drawPath(
                                            path,
                                            ink,
                                            style =
                                                Stroke(
                                                    mark.width * min(size.width, size.height),
                                                    cap = StrokeCap.Round,
                                                    join = StrokeJoin.Round,
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("上一页") }
                TextButton(onClick = { showList = true }, enabled = marks.isNotEmpty()) {
                    Text("批注列表")
                }
                TextButton(
                    enabled = marks.any { it.kind == "signature" && it.page == page },
                    onClick = {
                        val strokes = marks.filter { it.page == page && it.kind == "signature" }
                        val points = strokes.flatMap { it.points }
                        val left = points.minOf { it.x }
                        val top = points.minOf { it.y }
                        val w = (points.maxOf { it.x } - left).coerceAtLeast(.001f)
                        val h = (points.maxOf { it.y } - top).coerceAtLeast(.001f)
                        signature =
                            strokes.map { s ->
                                s.copy(
                                    points =
                                        s.points.map {
                                            PointN((it.x - left) / w, (it.y - top) / h)
                                        },
                                    width = s.width / w,
                                )
                            }
                        prefs
                            .edit()
                            .putString("strokes", JSONArray(signature.map { it.json() }).toString())
                            .apply()
                        tool = "stamp"
                    },
                ) {
                    Text("保存签名")
                }
                TextButton(enabled = page + 1 < (active?.count ?: 0), onClick = { page++ }) {
                    Text("下一页")
                }
            }
        }
    }
    if (textAt != null)
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = {
                Text(if (tool == "note") "添加便签" else if (tool == "label") "文字图章" else "添加文字")
            },
            text = { OutlinedTextField(text, { text = it }, label = { Text("批注文字") }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (text.isNotBlank())
                            add(
                                PdfMark(
                                    page,
                                    tool,
                                    listOf(textAt!!),
                                    color,
                                    width.coerceAtLeast(.005f),
                                    text,
                                )
                            )
                        textAt = null
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text("取消") } },
        )
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("保存批注副本") },
            text = {
                Column {
                    Text(
                        if (marks.any { it.kind == "redact" })
                            "包含安全脱敏：所有页会重新生成图片 PDF，文字、链接、表单及附件不复制到新文件。应用仍保留源文件，请分享新生成的副本。"
                        else "可编辑批注可由其他 PDF 阅读器继续修改；固定导出将新批注写入页面。原文件和草稿保留。手写签名保存为笔迹。"
                    )
                    if (marks.none { it.kind == "redact" })
                        Row {
                            FilterChip(editable, { editable = true }, label = { Text("可编辑批注") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(!editable, { editable = false }, label = { Text("固定到页面") })
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        vm.saveMarks(editable)
                    }
                ) {
                    Text("生成副本")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    if (showList)
        ModalBottomSheet(onDismissRequest = { showList = false }) {
            Column(
                Modifier.fillMaxHeight(.75f).padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Text("批注 · ${marks.size} 项", style = MaterialTheme.typography.titleLarge)
                marks.forEach { mark ->
                    ListItem(
                        headlineContent = {
                            Text(mark.text.ifBlank { markLabel(mark.kind) }.take(90))
                        },
                        supportingContent = {
                            Text("第 ${mark.page+1} 页 · ${markLabel(mark.kind)}")
                        },
                        trailingContent = {
                            Row {
                                TextButton(
                                    onClick = {
                                        page = mark.page
                                        editing = mark
                                        showList = false
                                    }
                                ) {
                                    Text("编辑")
                                }
                                TextButton(
                                    onClick = {
                                        vm.updateMarks(marks.filterNot { it.id == mark.id })
                                    }
                                ) {
                                    Text("删除")
                                }
                            }
                        },
                    )
                }
            }
        }
    editing?.let { selected ->
        var value by remember(selected.id) { mutableStateOf(selected.text) }
        var ink by remember(selected.id) { mutableIntStateOf(selected.color) }
        var weight by remember(selected.id) { mutableFloatStateOf(selected.width) }
        var dx by remember(selected.id) { mutableFloatStateOf(0f) }
        var dy by remember(selected.id) { mutableFloatStateOf(0f) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑批注") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (
                        selected.kind in
                            setOf("text", "note", "label", "highlight", "underline", "strikeout")
                    ) {
                        OutlinedTextField(value, { value = it }, label = { Text("文字 / 注释") })
                    }
                    Row {
                        listOf(
                                android.graphics.Color.RED,
                                android.graphics.Color.BLUE,
                                android.graphics.Color.BLACK,
                                android.graphics.Color.YELLOW,
                            )
                            .forEach { c ->
                                Box(
                                    Modifier.padding(6.dp)
                                        .size(30.dp)
                                        .background(Color(c))
                                        .border(
                                            if (ink == c) 3.dp else 1.dp,
                                            MaterialTheme.colorScheme.outline,
                                        )
                                        .clickable { ink = c }
                                )
                            }
                    }
                    Text("线宽 / 字号")
                    Slider(weight, { weight = it }, valueRange = 0.002f..0.014f)
                    Text("水平移动")
                    Slider(
                        dx,
                        { dx = it },
                        valueRange =
                            -selected.points.minOf { it.x }..(1 - selected.points.maxOf { it.x }),
                    )
                    Text("垂直移动")
                    Slider(
                        dy,
                        { dy = it },
                        valueRange =
                            -selected.points.minOf { it.y }..(1 - selected.points.maxOf { it.y }),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateMarks(
                            marks.map { mark ->
                                if (mark.id == selected.id)
                                    mark.copy(
                                        text = value,
                                        color = ink,
                                        width = weight,
                                        points = mark.points.map { p -> PointN(p.x + dx, p.y + dy) },
                                    )
                                else mark
                            }
                        )
                        editing = null
                    }
                ) {
                    Text("应用")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationScreen(vm: MainViewModel) {
    val doc by vm.selected.collectAsState()
    val marks by vm.marks.collectAsState()
    val busy by vm.busy.collectAsState()
    val file = doc?.pdfPath?.let { File(it) }
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
                    TextButton(
                        enabled = marks.isNotEmpty() && !busy,
                        onClick = { vm.updateMarks(marks.dropLast(1)) },
                    ) {
                        Text("撤销")
                    }
                    TextButton(
                        enabled = marks.isNotEmpty() && !busy,
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
                        "rect" to "矩形",
                        "text" to "文字",
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
                else if (tool == "stamp") "拖框放置已保存的签名。" else "批注草稿自动保存；导出为固定图层副本。",
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
                                if (tool == "text")
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
                                    "text" ->
                                        drawContext.canvas.nativeCanvas.drawText(
                                            mark.text,
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
            title = { Text("添加文字") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("批注文字") }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (text.isNotBlank())
                            add(
                                PdfMark(
                                    page,
                                    "text",
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
                Text(
                    if (marks.any { it.kind == "redact" })
                        "包含安全脱敏：所有页会重新生成图片 PDF，文字、链接、表单及附件不复制到新文件。应用仍保留源文件，请分享新生成的副本。"
                    else "批注将固定到新 PDF 中；原文件和可编辑草稿保留。手写签名是图像笔迹，不提供数字证书认证。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        vm.saveMarks()
                    }
                ) {
                    Text("生成副本")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
}

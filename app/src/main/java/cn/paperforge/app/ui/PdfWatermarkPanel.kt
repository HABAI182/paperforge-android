package cn.paperforge.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.model.OcrRegion
import cn.paperforge.app.pdf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Parses "1-3, 5" (1-based) into 0-based page numbers within [count]. */
internal fun parsePages(text: String, count: Int): Set<Int> =
    text.split(',', '，', ' ')
        .filter { it.isNotBlank() }
        .flatMap { part ->
            val bounds = part.split('-', '–', '~').mapNotNull { it.trim().toIntOrNull() }
            when (bounds.size) {
                1 -> listOf(bounds[0])
                2 -> (minOf(bounds[0], bounds[1])..maxOf(bounds[0], bounds[1])).toList()
                else -> emptyList()
            }
        }
        .map { it - 1 }
        .filter { it in 0 until count }
        .toSet()

/**
 * PDF watermark removal: detected candidates outlined on the page, tap or drag to choose, a scope, a preview of the
 * real result, and a separate output file. Object removal and scan-image restoration are kept apart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfWatermarkPanel(vm: MainViewModel, source: File, name: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsState()
    var scan by remember(source) { mutableStateOf<PdfWatermarkObjects.Scan?>(null) }
    var scanned by remember(source) { mutableIntStateOf(0) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var chosen by remember(source) { mutableStateOf(setOf<String>()) }
    var page by remember(source) { mutableIntStateOf(0) }
    var scopeKind by remember(source) { mutableStateOf("all") }
    var range by remember(source) { mutableStateOf("") }
    var after by remember(source) { mutableStateOf(false) }
    var picking by remember(source) { mutableStateOf(false) }
    // Region for repairing a watermark inside a scanned image, drawn on the page.
    var scanPicking by remember(source) { mutableStateOf(false) }
    var scanRegion by remember(source, page) { mutableStateOf<OcrRegion?>(null) }
    var draft by remember { mutableStateOf<OcrRegion?>(null) }
    var transform by remember(source, page) { mutableStateOf(PageTransform()) }
    LaunchedEffect(source) {
        try {
            val result =
                withContext(Dispatchers.IO) {
                    val s = PdfWatermarkObjects.scan(source)
                    val images =
                        com.tom_roush.pdfbox.pdmodel.PDDocument.load(source).use { pdf ->
                            ScanWatermarkRestore.pageImages(pdf).count { it != null && !it.bilevel }
                        }
                    s to images
                }
            scan = result.first
            scanned = result.second
            chosen = result.first.candidates.filter { it.suggested }.map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "无法分析这份 PDF"
        }
    }
    val found = scan
    val count = found?.pages ?: 1
    val original by
        produceState<PdfSession?>(null, source) {
            value = withContext(Dispatchers.IO) { runCatching { PdfSession(source) }.getOrNull() }
        }
    DisposableEffect(original) { onDispose { original?.close() } }
    // Preview: this page with the chosen objects removed.
    val previewFile = remember(source) { File(context.cacheDir, "wm-preview-${UUID.randomUUID()}.pdf") }
    DisposableEffect(previewFile) { onDispose { previewFile.delete() } }
    var removedHere by remember { mutableIntStateOf(0) }
    val previewed by
        produceState<PdfSession?>(null, source, page, chosen, after) {
            if (!after) {
                value = null
                return@produceState
            }
            delay(150)
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            removedHere = PdfWatermarkObjects.preview(source, previewFile, page, chosen).units
                            PdfSession(previewFile)
                        }
                        .getOrNull()
                }
        }
    DisposableEffect(previewed) { onDispose { previewed?.close() } }
    val candidates = found?.candidates.orEmpty()
    val here = candidates.flatMap { c -> c.units.filter { it.page == page }.map { c to it } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (found == null && error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在分析页面对象…")
        }
        if (found != null && !found.canModify)
            Text("这份 PDF 的权限设置禁止修改，不能去除水印。", color = MaterialTheme.colorScheme.error)
        if (found != null) {
            Text(
                if (candidates.isEmpty()) "没有发现可作为对象删除的水印。" + if (scanned > 0) "水印若印在扫描图像里，见下方“扫描图像中的水印”。" else ""
                else "发现 ${candidates.size} 类可能的水印，已勾选把握较大的 ${chosen.size} 类。点按页面上的标记可勾选或取消。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!after, { after = false }, label = { Text("原页（标出候选）") })
                FilterChip(after, { after = true }, label = { Text("去除后") }, modifier = Modifier.testTag("wm-after"))
                FilterChip(picking, { picking = !picking }, label = { Text("框选") }, enabled = !after)
            }
            val session = if (after) previewed else original
            Box(Modifier.fillMaxWidth().height(440.dp)) {
                if (session != null)
                    ReaderPage(
                        session,
                        if (after) 0 else page,
                        transform,
                        0,
                        true,
                        if (after) emptyList()
                        else
                            here.filter { it.first.id in chosen && it.second.box.size == 4 }.map { (c, u) ->
                                PdfSearch.Hit(page, c.label, u.box[0].x, u.box[0].y, u.box[2].x, u.box[2].y)
                            },
                        onTransform = { transform = it },
                        onTap = {},
                        onLinkTap = { p ->
                            val hit = here.filter { it.second.box.size == 4 && p.x in it.second.box[0].x..it.second.box[2].x && p.y in it.second.box[0].y..it.second.box[2].y }
                                .minByOrNull { it.second.area }?.first
                            if (hit != null && !after) {
                                chosen = if (hit.id in chosen) chosen - hit.id else chosen + hit.id
                                true
                            } else false
                        },
                        edgeTurning = false,
                        regionPicking = (picking || scanPicking) && !after,
                        regionDraft = draft ?: scanRegion,
                        onRegionSelection = { region, done ->
                            draft = region
                            if (done && scanPicking) {
                                scanRegion = region.takeIf { it.width > .01f && it.height > .01f }
                                draft = null
                                scanPicking = false
                            } else if (done) {
                                val inside = here.filter { (_, u) ->
                                    u.box.size == 4 && u.box[0].x < region.right && u.box[2].x > region.left && u.box[0].y < region.bottom && u.box[2].y > region.top
                                }.map { it.first.id }.toSet()
                                chosen = if (inside.all { it in chosen }) chosen - inside else chosen + inside
                                draft = null
                                picking = false
                            }
                        },
                    )
                else CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            if (count > 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ page-- }, enabled = page > 0) { Text("上一页") }
                    Text("${page + 1} / $count" + if (after) " · 本页去除 $removedHere 处" else "")
                    TextButton({ page++ }, enabled = page + 1 < count) { Text("下一页") }
                }
            candidates.forEach { c ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("wm-candidate")) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.id in chosen, { on -> chosen = if (on) chosen + c.id else chosen - c.id })
                        Column(Modifier.weight(1f)) {
                            Text("${c.kind.label}：${c.label.ifBlank { "（无文字）" }}", style = MaterialTheme.typography.titleSmall)
                            val pages = c.pages
                            Text(
                                (if (pages.size == 1) "第 ${pages.first() + 1} 页" else "${pages.size} 页（第 ${pages.first() + 1}–${pages.last() + 1} 页）") +
                                    " · ${c.units.size} 处 · " + c.reasons.joinToString("、"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                Text("范围", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(scopeKind == "page", { scopeKind = "page" }, label = { Text("本页") })
                    FilterChip(scopeKind == "range", { scopeKind = "range" }, label = { Text("指定页") })
                    FilterChip(scopeKind == "all", { scopeKind = "all" }, label = { Text("所有出现的页") })
                }
                if (scopeKind == "range")
                    OutlinedTextField(range, { range = it }, label = { Text("页码，如 1-3, 5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "删除对象：从 PDF 内容中直接删去所选水印，不覆盖、不转成图片；正文文字、字体、批注、链接和文字层保留，可继续搜索与复制。原文件不变，结果另存为新文档。",
                    style = MaterialTheme.typography.bodySmall,
                )
                val pages: Set<Int>? =
                    when (scopeKind) {
                        "page" -> setOf(page)
                        "range" -> parsePages(range, count)
                        else -> null
                    }
                Button(
                    onClick = {
                        val options =
                            JSONObject()
                                .put("mode", "OBJECTS")
                                .put("candidates", JSONArray(chosen.toList()))
                                .apply { pages?.let { put("pages", JSONArray(it.sorted())) } }
                        vm.cleanDocument(Uri.fromFile(source), options, name)
                    },
                    enabled = !busy && chosen.isNotEmpty() && found.canModify && (pages == null || pages.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().testTag("wm-apply"),
                ) {
                    Text("删除所选水印，另存副本")
                }
            }
            if (scanned > 0)
                ScanWatermarkSection(
                    vm, source, name, page, count, scanned, found.canModify,
                    scanRegion?.let { floatArrayOf(it.left, it.top, it.right, it.bottom) },
                    picking = scanPicking,
                    onPick = { scanPicking = !scanPicking; picking = false },
                )
        }
    }
}

@Composable
private fun ScanWatermarkSection(
    vm: MainViewModel,
    source: File,
    name: String,
    page: Int,
    count: Int,
    scanned: Int,
    canModify: Boolean,
    region: FloatArray?,
    picking: Boolean,
    onPick: () -> Unit,
) {
    val busy by vm.busy.collectAsState()
    var before by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var note by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(before, result) { onDispose { before?.recycle(); result?.recycle() } }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("扫描图像中的水印（图像修复）", style = MaterialTheme.typography.titleSmall)
            Text(
                "印在扫描图像里的水印不是独立对象，只能按像素修复：" +
                    if (scanned >= 3) "用多页对比估计每页同一位置的半透明水印，再把它从图像中除去；"
                    else "页数不足 3 页时，请填写区域，只把介于纸色与字迹之间的浅色像素还原为纸色；" +
                        "被水印完全遮住的笔画无法恢复，会保持原样，不会编造内容。只替换页面图像，文字层保留。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(picking, onPick, label = { Text(if (region == null) "在页面上框选水印区域" else "重新框选区域") }, modifier = Modifier.testTag("wm-scan-pick"))
                if (region != null) Text("已选区域", style = MaterialTheme.typography.labelMedium)
            }
            if (scanned < 3 && region == null)
                Text("这份文件扫描页少于 3 页，请先框选水印所在区域。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        running = true
                        note = ""
                        scope.launch {
                            try {
                                val pair = withContext(Dispatchers.IO) { ScanWatermarkRestore.preview(source, page, region) }
                                if (pair == null) note = "这一页没有找到可估计的半透明水印，原图保留。"
                                else {
                                    before = pair.first
                                    result = pair.second
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                note = e.message ?: "预览未能生成"
                            } finally {
                                running = false
                            }
                        }
                    },
                    enabled = !running && (scanned >= 3 || region != null),
                ) { Text("预览本页修复") }
                Button(
                    onClick = {
                        val options =
                            JSONObject().put("mode", "PIXELS").put("page", page).apply {
                                region?.let { put("region", JSONArray(it.map { v -> v.toDouble() })) }
                            }
                        vm.cleanDocument(Uri.fromFile(source), options, name)
                    },
                    enabled = !busy && canModify && result != null,
                ) { Text("修复并另存") }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
            val b = before
            val r = result
            if (b != null && r != null)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("修复前", style = MaterialTheme.typography.labelSmall)
                        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("修复后", style = MaterialTheme.typography.labelSmall)
                        Image(r.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                }
        }
    }
}

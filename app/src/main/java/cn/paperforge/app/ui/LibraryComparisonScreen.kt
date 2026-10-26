package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryComparisonScreen(vm: MainViewModel) {
    val controller = vm.comparison
    val documents by vm.repo.documents.collectAsState(emptyList())
    val docs = documents.associateBy { it.id }
    val report by controller.report.collectAsState()
    val running by controller.running.collectAsState()
    val progress by controller.progress.collectAsState()
    val tab by controller.tab.collectAsState()
    val scopeIds by controller.scopeIds.collectAsState()
    val families by controller.families.collectAsState()
    val familyTarget by controller.familyTarget.collectAsState()
    val busy by vm.busy.collectAsState()
    var grouping by remember { mutableStateOf<List<String>?>(null) }
    var renaming by remember { mutableStateOf<DocumentFamily?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("重复与版本") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            controller.cancel()
                            vm.screen.value = "home"
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { controller.tab.value = 0 },
                    text = { Text("内容比较") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { controller.tab.value = 1 },
                    text = { Text("版本分组") },
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (tab == 0) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (scopeIds == null) "比较整个文档库" else "比较已选 ${scopeIds!!.size} 份文档",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "相同内容集中显示，外观相似的页面可并排查看。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalButton(onClick = { vm.screen.value = "pdf-diff" }) {
                                Text("核对两份 PDF 的差异")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = controller::start, enabled = !running) {
                                    Text(if (report == null) "开始比较" else "重新比较")
                                }
                                if (running) TextButton(onClick = controller::cancel) { Text("停止") }
                                if (scopeIds != null)
                                    TextButton(onClick = { controller.configure(null) }) {
                                        Text("改为整个文档库")
                                    }
                            }
                            if (running) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(progress, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val result = report
                    if (result != null) {
                        val groups =
                            result.exact
                                .map { ids -> ids.mapNotNull(docs::get) }
                                .filter { it.size >= 2 }
                        val pairs =
                            result.similar.filter {
                                it.first.documentId in docs && it.second.documentId in docs
                            }
                        val pairGroups =
                            pairs
                                .groupBy {
                                    listOf(it.first.documentId, it.second.documentId)
                                        .sorted()
                                        .joinToString("|")
                                }
                                .values
                                .toList()
                        item {
                            Text(
                                "${groups.size} 组内容一致 · ${pairGroups.size} 组含相似页",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        if (groups.isEmpty() && pairs.isEmpty())
                            item { Text("本次范围内没有发现匹配项。相似比较只提供候选，仍可手工归为版本组。") }
                        items(groups, key = { it.joinToString("|") { doc -> doc.id } }) { group ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "内容一致 · ${group.size} 份",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    group.forEach { doc -> ComparisonDocumentRow(vm, doc, busy) }
                                    TextButton(onClick = { grouping = group.map { it.id } }) {
                                        Text("归为同一版本组")
                                    }
                                }
                            }
                        }
                        items(
                            pairGroups,
                            key = {
                                listOf(it.first().first.documentId, it.first().second.documentId)
                                    .sorted()
                                    .joinToString("|")
                            },
                        ) { matches ->
                            var chosen by remember(matches) { mutableIntStateOf(0) }
                            var pageMenu by remember { mutableStateOf(false) }
                            val pair = matches[chosen.coerceIn(matches.indices)]
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        if (pair.sameOriginal) "原图相同，当前处理可能不同" else "外观相近，请查看原页确认",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (matches.size > 1)
                                        Box {
                                            TextButton(onClick = { pageMenu = true }) {
                                                Text("匹配 ${chosen+1} / ${matches.size} · 切换页面")
                                            }
                                            DropdownMenu(
                                                pageMenu,
                                                { pageMenu = false },
                                                modifier = Modifier.heightIn(max = 320.dp),
                                            ) {
                                                matches.forEachIndexed { index, match ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                "第 ${match.first.index+1} 页 ↔ 第 ${match.second.index+1} 页"
                                                            )
                                                        },
                                                        onClick = {
                                                            chosen = index
                                                            pageMenu = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        ComparisonPagePreview(
                                            vm,
                                            docs.getValue(pair.first.documentId),
                                            pair.first,
                                            Modifier.weight(1f),
                                        )
                                        ComparisonPagePreview(
                                            vm,
                                            docs.getValue(pair.second.documentId),
                                            pair.second,
                                            Modifier.weight(1f),
                                        )
                                    }
                                    Row {
                                        TextButton(
                                            onClick = {
                                                grouping =
                                                    listOf(
                                                        pair.first.documentId,
                                                        pair.second.documentId,
                                                    )
                                            }
                                        ) {
                                            Text("归为版本组")
                                        }
                                        TextButton(onClick = { controller.ignore(pair.key) }) {
                                            Text("忽略此处匹配")
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                "已核对 ${result.documents} 份文档；相同内容的副本合并比较。扫描按原图、当前处理和识别文字核对，PDF 按文件内容核对。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (result.limited)
                                Text(
                                    "已达到 300 处候选，停止继续比较外观。可在文档库多选较小范围再比较。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            result.skipped.take(10).forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (result.skipped.size > 10) Text("另有 ${result.skipped.size-10} 项未能读取")
                        }
                    }
                } else {
                    val groups = families.filter { familyTarget == null || it.id == familyTarget }
                    item {
                        Text(
                            "将同一份资料的不同版本放在一起，并指定首选版本。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("文档库多选后，可从“更多”直接建立分组。", style = MaterialTheme.typography.bodySmall)
                        if (familyTarget != null)
                            TextButton(onClick = { controller.familyTarget.value = null }) {
                                Text("显示全部版本组")
                            }
                    }
                    if (groups.isEmpty()) item { Text("暂无版本分组") }
                    items(groups, key = { it.id }) { family ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val members = family.members.mapNotNull(docs::get)
                                Text(family.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${members.size} 份可用文档",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                members.forEach { doc ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                controller.update(family.id, preferred = doc.id)
                                            }
                                        ) {
                                            Icon(
                                                if (family.preferred == doc.id) Icons.Default.Star
                                                else Icons.Default.StarBorder,
                                                if (family.preferred == doc.id) "首选版本" else "设为首选版本",
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                doc.title,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (family.preferred == doc.id)
                                                Text(
                                                    "首选版本",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            Row {
                                                TextButton(
                                                    onClick = {
                                                        vm.openComparisonPage(
                                                            ComparePage(doc.id, null, 0)
                                                        )
                                                    }
                                                ) {
                                                    Text("打开")
                                                }
                                                TextButton(
                                                    onClick = {
                                                        controller.update(
                                                            family.id,
                                                            remove = doc.id,
                                                        )
                                                    }
                                                ) {
                                                    Text("移出分组")
                                                }
                                            }
                                        }
                                    }
                                }
                                Row {
                                    TextButton(onClick = { renaming = family }) { Text("重命名") }
                                    TextButton(onClick = { controller.dissolve(family.id) }) {
                                        Text("解散分组")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    grouping?.let { ids ->
        FamilyNameDialog("建立版本分组", docs[ids.firstOrNull()]?.title ?: "文档版本", { grouping = null }) {
            name ->
            controller.group(name, ids)
            grouping = null
        }
    }
    renaming?.let { family ->
        FamilyNameDialog("重命名版本组", family.name, { renaming = null }) { name ->
            controller.update(family.id, name = name)
            renaming = null
        }
    }
}

@Composable
fun FamilyNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("分组名称") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ComparisonDocumentRow(vm: MainViewModel, doc: DocumentRow, busy: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DocumentCover(doc, vm.repo)
        Column(Modifier.weight(1f)) {
            Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (doc.folder.isNotBlank())
                Text(doc.folder, style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(
                    onClick = { vm.openComparisonPage(ComparePage(doc.id, null, 0)) },
                    enabled = !busy,
                ) {
                    Text("查看")
                }
                TextButton(onClick = { vm.trashDocuments(setOf(doc.id)) }, enabled = !busy) {
                    Text("移入回收站")
                }
            }
        }
    }
}

@Composable
private fun ComparisonPagePreview(
    vm: MainViewModel,
    doc: DocumentRow,
    ref: ComparePage,
    modifier: Modifier,
) {
    val image by
        produceState<Bitmap?>(null, ref, doc.modified) {
            value =
                withContext(Dispatchers.IO) {
                    try {
                        if (doc.kind == "pdf")
                            PdfSession(File(checkNotNull(doc.pdfPath))).use {
                                it.render(ref.index, 700)
                            }
                        else
                            vm.repo.dao
                                .pages(doc.id)
                                .firstOrNull { it.id == ref.pageId }
                                ?.let { ScanProcessor.render(it, 700) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        null
                    }
                }
        }
    val currentImage = image
    DisposableEffect(currentImage) { onDispose { currentImage?.recycle() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            Modifier.fillMaxWidth().height(180.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            val bitmap = image
            if (bitmap != null)
                Image(
                    bitmap.asImageBitmap(),
                    "${doc.title} 第 ${ref.index+1} 页",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
        }
        Text(
            "${doc.title} · 第 ${ref.index+1} 页",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(onClick = { vm.openComparisonPage(ref) }) { Text("查看原页") }
    }
}

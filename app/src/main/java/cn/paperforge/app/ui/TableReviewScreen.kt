package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableReviewScreen(vm: MainViewModel) {
    val tables by vm.tableReview.collectAsState()
    val busy by vm.busy.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var text by remember { mutableStateOf("") }
    val table = tables.getOrNull(selected)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("表格预览与校正") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen.value = "convert" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { vm.exportPhoneTables(false) },
                    enabled = !busy && table != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导出 Excel")
                }
                OutlinedButton(
                    onClick = { vm.exportPhoneTables(true) },
                    enabled = !busy && table != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导出 CSV")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (table == null) Text("没有可预览的表格", Modifier.padding(24.dp))
            else {
                ScrollableTabRow(selectedTabIndex = selected) {
                    tables.forEachIndexed { index, item ->
                        Tab(
                            selected == index,
                            { selected = index },
                            text = { Text("表 ${index+1} · P${item.sourcePages.joinToString(",")}") },
                        )
                    }
                }
                Text(
                    "${table.rows.size} 行 × ${table.rows.first().size} 列 · ${table.method}\n轻触单元格校正；编号和金额按文本保留。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    LazyColumn(
                        Modifier.width((table.rows.first().size * 150).dp),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        items(table.rows.size) { r ->
                            Row {
                                table.rows[r].forEachIndexed { c, value ->
                                    val owner =
                                        table.cells.firstOrNull {
                                            r in it.row..it.rowEnd && c in it.column..it.columnEnd
                                        }
                                    val covered =
                                        owner != null && (owner.row != r || owner.column != c)
                                    Surface(
                                        Modifier.width(150.dp)
                                            .heightIn(min = 60.dp)
                                            .border(
                                                0.5.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                            )
                                            .clickable(enabled = !covered && !busy) {
                                                editing = r to c
                                                text = value
                                            },
                                        color =
                                            if (r == 0) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface,
                                    ) {
                                        Text(
                                            if (covered) "合并" else value,
                                            Modifier.padding(12.dp),
                                            maxLines = 4,
                                            color =
                                                if (covered) MaterialTheme.colorScheme.outline
                                                else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { (r, c) ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("第 ${r+1} 行，第 ${c+1} 列") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("单元格内容") }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.editTableCell(selected, r, c, text)
                        editing = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

package cn.paperforge.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings

internal val LocalDocumentFocus = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** One movable content tree keeps the actual reader/editor alive across pane-width changes. */
@Composable
fun DocumentWorkspace(vm: MainViewModel, screen: String, content: @Composable () -> Unit) {
    val latestContent by rememberUpdatedState(content)
    val pane = remember { movableContentOf { latestContent() } }
    val revision by UserSettings.revision.collectAsState()
    val enabled = remember(revision) { UserSettings.bool("documentSidebar", true) }
    var focus by rememberSaveable { mutableStateOf(false) }
    val motion = LocalPaperMotion.current
    val font = LocalDensity.current.fontScale
    val requestFocus: (Boolean) -> Unit = remember { { focus = it } }
    CompositionLocalProvider(LocalDocumentFocus provides requestFocus) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide =
                enabled &&
                    screen in setOf("reader", "editor") &&
                    maxWidth >= 1000.dp + ((font - 1).coerceAtLeast(0f) * 160).dp &&
                    maxHeight >= 460.dp
            if (wide)
                Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        !focus,
                        enter =
                            expandHorizontally(tween(motion.panelMs)) +
                                fadeIn(tween(motion.stateMs)),
                        exit =
                            shrinkHorizontally(tween(motion.panelMs)) +
                                fadeOut(tween(motion.stateMs)),
                    ) {
                        Row {
                            DocumentSidebar(
                                vm,
                                Modifier.width((320 * font.coerceIn(1f, 1.2f)).dp).fillMaxHeight(),
                            )
                            VerticalDivider()
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { pane() }
                }
            else pane()
        }
    }
}

@Composable
private fun DocumentSidebar(vm: MainViewModel, modifier: Modifier) {
    val docs by vm.documents.collectAsState()
    val currentDoc by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("ALL") }
    val feedback = selectionFeedback()
    val visible =
        docs
            .filter {
                (type == "ALL" || it.kind == type) &&
                    (query.isBlank() ||
                        listOf(it.title, it.tags, it.folder).any { value ->
                            value.contains(query, true)
                        })
            }
            .sortedByDescending { it.lastOpened }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.systemBarsPadding().padding(horizontal = PaperSpace.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.screen.value = "home" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回文档库")
                }
                Text(
                    "文档",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.screen.value = "tasks" }) {
                    Icon(Icons.Default.PendingActions, "任务中心")
                }
            }
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("查找文档") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (query.isNotEmpty())
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清空文档筛选") }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "全部", "pdf" to "PDF", "scan" to "扫描").forEach { (id, label) ->
                    FilterChip(type == id, { type = id }, { Text(label) })
                }
            }
            Text(
                "${visible.size} 份 · 选择后在右侧打开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (visible.isEmpty())
                PaperEmptyState(
                    Icons.Default.Search,
                    "没有匹配的文档",
                    "可清空筛选，或返回文档库导入。",
                    "清空筛选",
                    {
                        query = ""
                        type = "ALL"
                    },
                )
            else
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(visible, key = { it.id }) { doc ->
                        val active = currentDoc?.id == doc.id
                        val color by
                            animateColorAsState(
                                if (active) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLowest,
                                tween(LocalPaperMotion.current.stateMs),
                                label = "document selection",
                            )
                        Surface(
                            color = color,
                            shape = MaterialTheme.shapes.medium,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .semantics(mergeDescendants = true) { selected = active }
                                    .clickable(enabled = !busy, role = Role.Button) {
                                        feedback()
                                        vm.open(doc)
                                    },
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                DocumentCover(doc, vm.repo, Modifier.size(44.dp, 60.dp), 180)
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        doc.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        if (doc.kind == "pdf") "PDF" else "扫描文档",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

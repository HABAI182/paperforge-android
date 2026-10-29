package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.model.ToolCatalog

internal data class WorkspaceSection(
    val id: String,
    val title: String,
    val detail: String,
    val icon: ImageVector,
)

internal val workspaceSections =
    listOf(
        WorkspaceSection("capture", "扫描与识字", "拍摄、图片导入、文字识别", Icons.Default.DocumentScanner),
        WorkspaceSection("convert", "格式转换", "Office、表格、图片与电子书", Icons.Default.SwapHoriz),
        WorkspaceSection("pdf", "PDF 工具", "页面处理、批注、压缩与保护", Icons.Default.PictureAsPdf),
        WorkspaceSection("organize", "文档整理", "命名、笔记、备份与存储", Icons.Default.FolderOpen),
        WorkspaceSection("workflow", "任务与流程", "批量操作、自动化与导出记录", Icons.Default.PendingActions),
        WorkspaceSection("connect", "连接与智能", "传输、翻译与智能服务", Icons.Default.Wifi),
    )

internal val settingsSections =
    listOf(
        WorkspaceSection("appearance", "外观与布局", "主题、字号与文档布局", Icons.Default.Palette),
        WorkspaceSection("scan", "扫描与拍摄", "自动拍摄、裁边、画质与增强", Icons.Default.DocumentScanner),
        WorkspaceSection("ocr", "识字与模型", "离线 OCR、语言和版面识别", Icons.Default.Translate),
        WorkspaceSection("export", "导出与文件名", "默认纸张、清晰度、命名与保存位置", Icons.Default.SaveAlt),
        WorkspaceSection("advanced", "高级与系统", "存储、后台、桌面入口与设置备份", Icons.Default.Tune),
        WorkspaceSection("privacy", "隐私与安全", "应用锁、截图与解锁方式", Icons.Default.Lock),
    )

/** Every entry has exactly one destination; newly added PDF tools remain discoverable. */
internal fun workspaceSection(tool: HomeTool): String =
    when (tool.id) {
        "action:scan",
        "action:ocr-images",
        "tool:image-pdf",
        "tool:searchable" -> "capture"
        "action:tasks",
        "action:outputs",
        "tool:workflows",
        "tool:conversion-batch",
        "tool:auto-export" -> "workflow"
        "action:lan",
        "action:intelligence",
        "action:updates" -> "connect"
        "action:convert" -> "convert"
        "action:assembly",
        "action:open-pdf",
        "tool:merge",
        "tool:compress",
        "tool:split-each",
        "tool:pdf-diff",
        "tool:extract-images" -> "pdf"
        else ->
            if (tool.id.startsWith("tool:")) {
                if (ToolCatalog.conversion(tool.id.removePrefix("tool:")) != null) "convert"
                else "pdf"
            } else "organize"
    }

internal fun LazyListScope.workspaceContent(
    entries: List<HomeTool>,
    group: String,
    query: String,
    pinned: Set<String>,
    quickIds: List<String>,
    enabled: Boolean,
    onGroup: (String) -> Unit,
    onQuery: (String) -> Unit,
    onInfo: (cn.paperforge.app.model.ToolCapability) -> Unit,
) {
    val tools = entries.distinctBy { it.id }
    val searching = query.isNotBlank()
    val root = group.substringBefore('/')
    val family = group.substringAfter('/', "")
    val families =
        if (root == "convert") conversionFamilies
        else if (root == "pdf") pdfFamilies else emptyList()
    val familyLanding = !searching && family.isBlank() && families.isNotEmpty()
    item(key = "workspace-search") {
        OutlinedTextField(
            query,
            onQuery,
            placeholder = { Text("搜索工具、格式") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty())
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "清空搜索") }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!searching && group.isBlank()) {
        val quick =
            (pinned.toList() + quickIds)
                .distinct()
                .mapNotNull { id -> tools.find { it.id == id } }
                .take(4)
        if (quick.isNotEmpty())
            item(key = "workspace-quick") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "快捷使用",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = { onGroup("pinned") }) { Text("常用工具") }
                    }
                    quick.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { tool ->
                                FilledTonalButton(
                                    onClick = tool.action,
                                    enabled = enabled,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                ) {
                                    Text(tool.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        item(key = "workspace-heading") {
            Text("全部功能", style = MaterialTheme.typography.titleMedium)
        }
        item(key = "workspace-categories") {
            SectionCardGrid(workspaceSections, onSelect = onGroup)
        }
        item {
            Text(
                "在工具右侧菜单中固定常用功能。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val found =
            tools.filter { tool ->
                if (searching) {
                    ToolCatalog.matches(
                        query,
                        tool.title,
                        tool.description,
                        tool.id,
                        tool.aliases,
                    ) ||
                        (tool.id.startsWith("tool:") &&
                            ToolCatalog.pdfMatches(
                                tool.id.removePrefix("tool:"),
                                tool.title,
                                query,
                            ))
                } else if (group == "pinned") tool.id in pinned
                else
                    workspaceSection(tool) == root &&
                        (family.isBlank() || toolFamily(tool, root) == family)
            }
        item(key = "workspace-breadcrumb") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        onQuery("")
                        onGroup(if (family.isNotBlank()) root else "")
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (family.isNotBlank()) "返回分类" else "全部功能")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${found.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!searching)
            item(key = "workspace-section-title") {
                val section =
                    if (family.isNotBlank()) families.find { it.id == family }
                    else workspaceSections.find { it.id == root }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        section?.title ?: "常用工具",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        section?.detail ?: "固定后，常用功能会优先显示在工作台。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        if (found.isEmpty())
            item {
                Text(
                    if (searching) "没有找到匹配的工具，试试格式名称或关键词。" else "还没有固定工具。打开任意分类，在工具菜单中选择“固定到常用”。",
                    Modifier.padding(vertical = 24.dp),
                )
            }
        if (familyLanding)
            item(key = "family-cards-$root") {
                val choices =
                    families
                        .filter { f -> found.any { toolFamily(it, root) == f.id } }
                        .map { f ->
                            f.copy(
                                detail =
                                    "${found.count { toolFamily(it, root) == f.id }} 项 · ${f.detail}"
                            )
                        }
                SectionCardGrid(choices) { onGroup("$root/$it") }
            }
        else {
            val sections =
                if (searching) workspaceSections.map { it.id to it.title } else listOf(group to "")
            sections.forEach { (id, title) ->
                val rows = if (searching) found.filter { workspaceSection(it) == id } else found
                if (rows.isNotEmpty()) {
                    if (searching)
                        item(key = "result-$id") {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    // Conversions retain their format families instead of becoming another long
                    // mixed
                    // list.
                    rows
                        .groupBy { if (!searching && group == "convert") it.group else "" }
                        .forEach { (family, members) ->
                            if (family.isNotBlank())
                                item(key = "family-$id-$family") {
                                    Text(
                                        family,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            items(members, key = { it.id }) { tool ->
                                WorkspaceToolRow(tool, tool.id in pinned, enabled, onInfo)
                            }
                        }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceToolRow(
    tool: HomeTool,
    pinned: Boolean,
    enabled: Boolean,
    onInfo: (cn.paperforge.app.model.ToolCapability) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val capability =
        if (tool.id.startsWith("tool:")) ToolCatalog.conversion(tool.id.removePrefix("tool:"))
        else null
    Card(
        onClick = tool.action,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                tool.icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tool.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        if (pinned) Icons.Default.Star else Icons.Default.MoreVert,
                        "${tool.title}的菜单",
                        tint =
                            if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (pinned) "从常用移除" else "固定到常用") },
                        onClick = {
                            UserSettings.toggleTool(tool.id)
                            menu = false
                        },
                    )
                    capability?.let { info ->
                        DropdownMenuItem(
                            text = { Text("能力与格式说明") },
                            onClick = {
                                menu = false
                                onInfo(info)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsHub(vm: MainViewModel, onAbout: () -> Unit, onUpdates: () -> Unit) {
    val context = LocalContext.current
    val version =
        remember(context) {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            androidx.compose.foundation.Image(
                painterResource(cn.paperforge.app.R.drawable.brand_logo_source),
                null,
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "PaperForge",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$version · 扫描、阅读与文件转换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = onUpdates,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("检查更新")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/HABAI182/paperforge-android"),
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("GitHub 项目")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("偏好设置", style = MaterialTheme.typography.titleMedium)
        SectionCardGrid(settingsSections) { id ->
            vm.settingsSection.value = id
            vm.screen.value = "settings"
        }
        Spacer(Modifier.height(4.dp))
        Text("关于 PaperForge", style = MaterialTheme.typography.titleMedium)
        SectionNavigationRow(
            WorkspaceSection("about", "关于与项目主页", "版本信息、GitHub 与开源声明", Icons.Default.Info),
            onClick = onAbout,
        )
    }
}

@Composable
private fun SectionNavigationRow(section: WorkspaceSection, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(section.icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    section.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

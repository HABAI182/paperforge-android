package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A file result is a short action card, not a full-height settings dialog. */
@Composable
internal fun OutputResultDialog(
    title: String,
    name: String,
    size: String,
    notice: String,
    destination: String?,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onOpen: () -> Unit,
    onPickDirectory: () -> Unit,
    onPreview: (() -> Unit)?,
    onCompare: (() -> Unit)?,
    onLibrary: (() -> Unit)? = null,
) {
    var locations by remember { mutableStateOf(false) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .86f).dp
    val stacked =
        LocalDensity.current.fontScale > 1.4f || LocalConfiguration.current.screenWidthDp < 340
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(20.dp).widthIn(max = 520.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "关闭")
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Description,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                size,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "已保存在应用中",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodyMedium)
                // v200 feedback 1: the exported PDF is a document in the library, so the result card
                // links to it instead of leaving the user to hunt for a file.
                onLibrary?.let { action ->
                    FilledTonalButton(
                        action,
                        Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("在文档库打开", style = MaterialTheme.typography.titleSmall)
                    }
                }
                val actions =
                    listOf(
                        ResultAction("分享", "分享文件", Icons.Default.IosShare, onShare),
                        ResultAction("保存", "保存到手机", Icons.Default.SaveAlt, onSave),
                        ResultAction(
                            if (onPreview != null) "预览" else "位置",
                            if (onPreview != null) "预览文件" else "选择保存位置",
                            if (onPreview != null) Icons.Default.Visibility
                            else Icons.Default.FolderOpen,
                            onPreview ?: { locations = !locations },
                        ),
                        ResultAction("打开", "用其他应用打开", Icons.Default.OpenInNew, onOpen),
                    )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions.chunked(if (stacked) 1 else 2).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { action ->
                                FilledTonalButton(
                                    action.onClick,
                                    Modifier.weight(1f)
                                        .fillMaxHeight()
                                        .heightIn(min = 56.dp)
                                        .semantics { contentDescription = action.description },
                                    shape =
                                        androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                    colors =
                                        ButtonDefaults.filledTonalButtonColors(
                                            containerColor =
                                                MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor =
                                                MaterialTheme.colorScheme.onSecondaryContainer,
                                        ),
                                ) {
                                    Icon(action.icon, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        action.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                        }
                    }
                }
                onCompare?.let { TextButton(it, Modifier.fillMaxWidth()) { Text("原件与结果对照") } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(
                    onClick = { locations = !locations },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                "保存位置",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                destination ?: "保存时选择手机文件夹",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (locations) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            if (locations) "收起保存位置" else "展开保存位置",
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    locations,
                    enter =
                        androidx.compose.animation.expandVertically(
                            androidx.compose.animation.core.tween(
                                LocalPaperMotion.current.panelMs,
                                easing = PaperMotionEase,
                            )
                        ) + androidx.compose.animation.fadeIn(),
                    exit =
                        androidx.compose.animation.shrinkVertically(
                            androidx.compose.animation.core.tween(
                                LocalPaperMotion.current.panelMs,
                                easing = PaperMotionEase,
                            )
                        ) + androidx.compose.animation.fadeOut(),
                ) {
                    Column {
                        TextButton(onPickDirectory, Modifier.fillMaxWidth()) {
                            Text(if (destination == null) "设置默认保存目录" else "更换默认保存目录")
                        }
                        TextButton(onSaveAs, Modifier.fillMaxWidth()) { Text("另存到其他位置") }
                    }
                }
            }
        }
    }
}

private data class ResultAction(
    val label: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)

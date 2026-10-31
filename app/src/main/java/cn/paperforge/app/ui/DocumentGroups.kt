package cn.paperforge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.DocumentGroup

/**
 * Horizontal folder cards above the library list. Tapping a card filters the list to that folder
 * (subfolders included); tapping it again, or tapping "全部", clears the group filter. The existing
 * 全部/收藏/最近 chips stay functional and are not replaced.
 */
@Composable
fun FolderGroupRow(
    groups: List<DocumentGroup>,
    total: Int,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "文件夹",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "左右滑动 · 点按筛选",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        LazyRow(
            Modifier.fillMaxWidth().testTag("home-folder-groups"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "group:all") {
                FolderGroupCard(
                    label = "全部",
                    count = total,
                    selected = selectedKey == null,
                    icon = Icons.Default.FolderOpen,
                    tag = "home-group-all",
                ) {
                    onSelect(null)
                }
            }
            items(groups, key = { "group:${it.key}" }) { group ->
                FolderGroupCard(
                    label = if (group.isUnfiled) group.name else group.key,
                    count = group.count,
                    selected = selectedKey == group.key,
                    icon = if (group.isUnfiled) Icons.Default.Inbox else Icons.Default.Folder,
                    tag =
                        if (group.isUnfiled) "home-group-unfiled"
                        else "home-group-${group.key}",
                ) {
                    onSelect(if (selectedKey == group.key) null else group.key)
                }
            }
        }
    }
}

@Composable
private fun FolderGroupCard(
    label: String,
    count: Int,
    selected: Boolean,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    val content =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier =
            Modifier.width(152.dp)
                .height(64.dp)
                .testTag(tag)
                .semantics { contentDescription = "$label，$count 份文档" },
        shape = RoundedCornerShape(16.dp),
        color = container,
        border =
            BorderStroke(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                icon,
                null,
                tint =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = content,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$count 份",
                    color =
                        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

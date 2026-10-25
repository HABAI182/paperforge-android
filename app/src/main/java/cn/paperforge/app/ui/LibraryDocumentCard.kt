package cn.paperforge.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.Repository

@Composable
internal fun LibraryCollectionHeader(
    title: String,
    selecting: Boolean,
    count: Int,
    largeFont: Boolean,
    onSelect: () -> Unit,
    onAll: (() -> Unit)?,
) {
    @Composable
    fun actions() {
        if (!selecting) TextButton(onClick = onSelect) { Text("多选") }
        if (onAll != null) TextButton(onClick = onAll) { Text("全部文档") }
        else Text("$count 份", style = MaterialTheme.typography.labelMedium)
    }
    @Composable
    fun heading(modifier: Modifier) {
        Text(
            title,
            modifier,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
    if (largeFont)
        Column {
            heading(Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    else
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            heading(Modifier.weight(1f))
            actions()
        }
}

/** One set of document actions and semantics, shared by the list and cover layouts. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryDocumentCard(
    document: DocumentRow,
    repository: Repository,
    covers: Boolean,
    compact: Boolean,
    selecting: Boolean,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    menu: @Composable () -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    @Composable
    fun selection() {
        if (selecting)
            Checkbox(
                selected,
                { onSelect() },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "选择文档 ${document.title}" },
            )
    }
    Card(
        modifier
            .fillMaxWidth()
            .combinedClickable(enabled = enabled, onClick = onOpen, onLongClick = onLongClick),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected && selecting) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLowest
            ),
    ) {
        if (covers)
            Column(Modifier.padding(if (compact) 8.dp else 12.dp)) {
                Box {
                    DocumentCover(
                        document,
                        repository,
                        Modifier.fillMaxWidth().height(if (compact) 132.dp else 174.dp),
                        640,
                    )
                    Box(Modifier.align(Alignment.TopStart)) { selection() }
                    if (!selecting)
                        Surface(
                            Modifier.align(Alignment.TopEnd),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                        ) {
                            menu()
                        }
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), content = details)
            }
        else
            Row(
                Modifier.padding(if (compact) 8.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                selection()
                DocumentCover(document, repository)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp), content = details)
                if (!selecting) menu()
            }
    }
}

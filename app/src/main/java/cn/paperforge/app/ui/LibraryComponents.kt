package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.CjkSearch
import java.text.Normalizer
import java.time.*
import java.util.Locale

val libraryCollections =
    listOf(
        "ALL" to "全部",
        "INBOX" to "收件箱",
        "NO_OCR" to "缺少文字层",
        "NEEDS_EXPORT" to "待导出",
        "ANNOTATED" to "含批注",
    )
val librarySorts = listOf("MODIFIED" to "最近修改", "OPENED" to "最近打开", "TITLE" to "名称")

fun libraryDateGroup(time: Long): String {
    if (time <= 0) return "尚未打开"
    val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date >= today.minusDays(6) -> "本周"
        else -> "更早"
    }
}

@Composable
fun LibraryFilterControls(
    collection: String,
    sort: String,
    onCollection: (String) -> Unit,
    onSort: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().rootSwipeChild().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        libraryCollections.forEach { (key, title) ->
            FilterChip(collection == key, { onCollection(key) }, label = { Text(title) })
        }
    }
    Row(
        Modifier.fillMaxWidth().rootSwipeChild().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        librarySorts.forEach { (key, title) ->
            FilterChip(sort == key, { onSort(key) }, label = { Text(title) })
        }
    }
}

@Composable
fun LibrarySnippet(hit: LibrarySearchHit, query: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val excerpt =
        remember(hit.text, query, colors.secondaryContainer) {
            val text = hit.text.replace('\n', ' ')
            val normalized = StringBuilder()
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            var offset = 0
            text.codePoints().toArray().forEach { code ->
                val length = Character.charCount(code)
                val folded =
                    Normalizer.normalize(String(Character.toChars(code)), Normalizer.Form.NFKC)
                        .lowercase(Locale.ROOT)
                normalized.append(folded)
                repeat(folded.length) {
                    starts.add(offset)
                    ends.add(offset + length)
                }
                offset += length
            }
            val terms = CjkSearch.normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
            val at = terms.map { normalized.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val start = (starts.getOrNull(at) ?: 0).minus(24).coerceAtLeast(0)
            val end = (start + 112).coerceAtMost(text.length)
            buildAnnotatedString {
                if (start > 0) append("…")
                val prefix = length
                append(text.substring(start, end))
                if (end < text.length) append("…")
                terms.forEach { term ->
                    var from = normalized.indexOf(term)
                    while (from >= 0) {
                        val left = starts.getOrNull(from) ?: break
                        val right = ends.getOrNull(from + term.length - 1) ?: break
                        if (left < end && right > start)
                            addStyle(
                                SpanStyle(background = colors.secondaryContainer),
                                prefix + (left - start).coerceAtLeast(0),
                                prefix + (right - start).coerceAtMost(end - start),
                            )
                        from = normalized.indexOf(term, from + term.length)
                    }
                }
            }
        }
    Column(
        Modifier.fillMaxWidth()
            .testTag("library-hit-${hit.documentId}-${hit.page}")
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(
            "第 ${hit.page+1} 页",
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
        )
        Text(
            excerpt,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A chip that opens a small menu; shows the chosen value when it is not the default. */
@Composable
private fun MenuChip(
    label: String,
    active: Boolean,
    tag: String,
    items: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    footer: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            active,
            { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.height(32.dp).testTag(tag),
        )
        DropdownMenu(open, { open = false }) {
            items.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        open = false
                        onPick(key)
                    },
                    trailingIcon = { if (key == selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) },
                )
            }
            footer?.invoke(this) { open = false }
        }
    }
}

/**
 * The library's filters in one compact row: type, favourites, folder, tag and order as menu chips (each shows its
 * value when set), saved views, a clear chip when anything is set, and the layout switch at the end.
 */
@Composable
fun LibraryFilterBar(
    collection: String,
    onCollection: (String) -> Unit,
    favorites: Boolean,
    onFavorites: (Boolean) -> Unit,
    folder: String,
    folders: List<String>,
    onFolder: (String) -> Unit,
    tag: String,
    tags: List<String>,
    onTag: (String) -> Unit,
    sort: String,
    onSort: (String) -> Unit,
    covers: Boolean,
    onLayout: () -> Unit,
    saved: List<cn.paperforge.app.data.LibraryFilter>,
    onSaved: (cn.paperforge.app.data.LibraryFilter) -> Unit,
    onSave: () -> Unit,
    onDeleteSaved: (String) -> Unit,
    onClear: () -> Unit,
) {
    val active = collection != "ALL" || favorites || folder.isNotBlank() || tag.isNotBlank() || sort != "MODIFIED"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).rootSwipeChild().horizontalScroll(rememberScrollState()).testTag("library-filter-bar"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuChip(
                libraryCollections.first { it.first == collection }.second.let { if (collection == "ALL") "类型" else it },
                collection != "ALL",
                "filter-collection",
                libraryCollections,
                collection,
                onCollection,
            )
            FilterChip(favorites, { onFavorites(!favorites) }, label = { Text("收藏") }, modifier = Modifier.height(32.dp))
            if (folders.isNotEmpty())
                MenuChip(
                    if (folder.isBlank()) "文件夹" else folder.substringAfterLast('/'),
                    folder.isNotBlank(),
                    "filter-folder",
                    listOf("" to "全部文件夹") + folders.map { it to it },
                    folder,
                    onFolder,
                )
            if (tags.isNotEmpty())
                MenuChip(
                    if (tag.isBlank()) "标签" else "#$tag",
                    tag.isNotBlank(),
                    "filter-tag",
                    listOf("" to "全部标签") + tags.map { it to "#$it" },
                    tag,
                    onTag,
                )
            MenuChip(
                librarySorts.first { it.first == sort }.second,
                sort != "MODIFIED",
                "filter-sort",
                librarySorts,
                sort,
                onSort,
            ) { close ->
                HorizontalDivider()
                saved.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text("视图：" + preset.name) },
                        onClick = {
                            close()
                            onSaved(preset)
                        },
                        trailingIcon = {
                            IconButton(onClick = { onDeleteSaved(preset.name) }) { Icon(Icons.Default.Close, "删除视图 ${preset.name}", Modifier.size(16.dp)) }
                        },
                    )
                }
                if (active)
                    DropdownMenuItem(
                        text = { Text("把当前筛选存为视图…") },
                        onClick = {
                            close()
                            onSave()
                        },
                    )
            }
            if (active)
                AssistChip(onClick = onClear, label = { Text("清除") }, leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                    modifier = Modifier.height(32.dp).testTag("filter-clear"))
        }
        IconButton(onClick = onLayout, modifier = Modifier.size(40.dp)) {
            Icon(if (covers) Icons.Default.ViewList else Icons.Default.GridView, if (covers) "切换列表布局" else "切换封面布局")
        }
    }
}

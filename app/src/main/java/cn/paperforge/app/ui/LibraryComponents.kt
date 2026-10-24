package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        libraryCollections.forEach { (key, title) ->
            FilterChip(collection == key, { onCollection(key) }, label = { Text(title) })
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

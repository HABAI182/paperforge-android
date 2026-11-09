package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.model.SmartNaming
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * #25: optional offline naming suggestions inside a rename dialog.
 *
 * The button is explicit: nothing is read or suggested until the user asks. Picking a name only
 * fills the caller's text field; the dialog's own "save" still has to be pressed, and the category
 * is display-only (it is never written to folder/tags).
 */
@Composable
fun SmartNamingAssist(
    load: suspend () -> SmartNaming.Suggestion?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var suggestion by remember { mutableStateOf<SmartNaming.Suggestion?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    if (!loading) {
                        scope.launch {
                            loading = true
                            problem = null
                            try {
                                suggestion = load()
                                attempted = true
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                problem = failure.message ?: "首页分析未完成，请重试"
                                attempted = true
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.testTag("smart-naming-suggest"),
            ) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (loading) "读取首页…" else "命名建议")
            }
            Text(
                "离线分析首页，点选后仍需保存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (attempted) {
            val current = suggestion
            if (
                current == null ||
                    (current.category == SmartNaming.Category.OTHER && current.names.isEmpty())
            ) {
                Text(
                    problem ?: "没有足够的首页文字依据，暂不给出建议。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            "${current.category.label} · 规则依据 " +
                                "${(current.confidence * 100).roundToInt()}%",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "分类建议（不自动写入）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (current.names.isEmpty()) {
                    Text(
                        "没有新的候选名称（当前名称已覆盖该依据，或首页缺少日期/标题）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    current.names.forEachIndexed { index, name ->
                        AssistChip(
                            onClick = { onPick(name) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth().testTag("smart-naming-name-$index"),
                        )
                    }
                }
                if (current.evidence.isNotEmpty()) {
                    Text(
                        current.evidence.take(4).joinToString("；"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current.reviewNotes.forEach { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

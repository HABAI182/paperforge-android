package cn.paperforge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.paperforge.app.model.ReceiptFields
import kotlin.math.roundToInt

/**
 * #26: editable confirmation table for one extracted receipt record.
 *
 * Every field is a plain text box so a wrong OCR value can be corrected before export; the category
 * chips only change the exported value. Nothing is written until an export button is pressed, and the
 * dialog never talks to OCR itself (the caller passes the already-cached text result).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewDialog(
    initial: ReceiptFields.Record,
    sourceTitle: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onExport: (ReceiptFields.Record, Boolean) -> Unit,
) {
    var date by remember(initial) { mutableStateOf(initial.date) }
    var merchant by remember(initial) { mutableStateOf(initial.merchant) }
    var amount by remember(initial) { mutableStateOf(initial.amount) }
    var tax by remember(initial) { mutableStateOf(initial.tax) }
    var invoiceNo by remember(initial) { mutableStateOf(initial.invoiceNo) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    val record =
        initial.copy(
            date = date.trim(),
            merchant = merchant.trim(),
            amount = amount.trim(),
            tax = tax.trim(),
            invoiceNo = invoiceNo.trim(),
            category = category,
        )
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth(.92f)
                    .widthIn(max = 520.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("票据信息", style = MaterialTheme.typography.titleLarge)
                Text(
                    "来自「${sourceTitle.ifBlank { "当前文档" }}」已缓存的离线 OCR；请核对后再导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field("日期", date, "receipt-date") { date = it }
                Field("商家", merchant, "receipt-merchant") { merchant = it }
                Field("金额", amount, "receipt-amount") { amount = it }
                Field("税额", tax, "receipt-tax") { tax = it }
                Field("发票号", invoiceNo, "receipt-invoice") { invoiceNo = it }
                Text("分类", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ReceiptFields.Category.entries.forEach { entry ->
                        FilterChip(
                            selected = entry == category,
                            onClick = { category = entry },
                            label = { Text(entry.label) },
                            modifier = Modifier.testTag("receipt-category-${entry.name}"),
                        )
                    }
                }
                Text(
                    "识别置信度 ${(record.confidence * 100).roundToInt()}%" +
                        if (record.evidence.isEmpty()) " · 无可用依据"
                        else " · " + record.evidence.take(2).joinToString("；"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "识别错的字段可直接修改；留空的字段不会写入记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    TextButton(
                        onClick = { onExport(record, false) },
                        enabled = !busy && !record.isBlank,
                        modifier = Modifier.testTag("receipt-export-csv"),
                    ) {
                        Text("导出 CSV")
                    }
                    Button(
                        onClick = { onExport(record, true) },
                        enabled = !busy && !record.isBlank,
                        modifier = Modifier.testTag("receipt-export-xlsx"),
                    ) {
                        Text("导出 XLSX")
                    }
                }
                Text(
                    "文件写入应用导出目录 PaperForge/票据记录.csv、票据记录.xlsx；CSV 每次追加一行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

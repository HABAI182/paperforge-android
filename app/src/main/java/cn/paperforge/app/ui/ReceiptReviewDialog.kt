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
 * chips only change the exported value. Nothing is written until an export button is pressed, and
 * the dialog never talks to OCR itself (the caller passes the already-cached text result).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewDialog(
    initial: ReceiptFields.Record,
    sourceTitle: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSource: ((ReceiptFields.SourceLine) -> Unit)? = null,
    onExport: (ReceiptFields.Record, Boolean) -> Unit,
) {
    var date by remember(initial) { mutableStateOf(initial.date) }
    var merchant by remember(initial) { mutableStateOf(initial.merchant) }
    var amount by remember(initial) { mutableStateOf(initial.amount) }
    var tax by remember(initial) { mutableStateOf(initial.tax) }
    var invoiceNo by remember(initial) { mutableStateOf(initial.invoiceNo) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    var currency by remember(initial) { mutableStateOf(initial.currency) }
    var items by remember(initial) { mutableStateOf(initial.items) }
    var showItems by remember(initial) { mutableStateOf(false) }
    val record =
        initial.copy(
            date = date.trim(),
            merchant = merchant.trim(),
            amount = amount.trim(),
            tax = tax.trim(),
            invoiceNo = invoiceNo.trim(),
            category = category,
            currency = currency.trim(),
            items = items.filter { it.name.isNotBlank() || it.amount.isNotBlank() },
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
                    "来自「${sourceTitle.ifBlank { "当前文档" }}」的已识别文字；点字段下方来源可放大核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field("日期", date, "receipt-date", initial.sources["date"], onSource) { date = it }
                Field("商家", merchant, "receipt-merchant", initial.sources["merchant"], onSource) {
                    merchant = it
                }
                Field("金额", amount, "receipt-amount", initial.sources["amount"], onSource) {
                    amount = it
                }
                Field(
                    "币种 · 未识别可留空",
                    currency,
                    "receipt-currency",
                    initial.sources["currency"],
                    onSource,
                ) {
                    currency = it
                }
                Field("税额", tax, "receipt-tax", initial.sources["tax"], onSource) { tax = it }
                Field("发票号", invoiceNo, "receipt-invoice", initial.sources["invoice"], onSource) {
                    invoiceNo = it
                }
                TextButton(onClick = { showItems = !showItems }) {
                    Text("明细 ${items.size} 项${if (showItems) " · 收起" else " · 展开核对"}")
                }
                if (showItems) {
                    items.forEachIndexed { index, item ->
                        OutlinedCard {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Field(
                                    "明细 ${index + 1}",
                                    item.name,
                                    "receipt-item-$index",
                                    item.source,
                                    onSource,
                                ) { value ->
                                    items =
                                        items.toMutableList().apply {
                                            set(index, item.copy(name = value))
                                        }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        item.quantity,
                                        { value ->
                                            items =
                                                items.toMutableList().apply {
                                                    set(index, item.copy(quantity = value))
                                                }
                                        },
                                        label = { Text("数量") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        item.unitPrice,
                                        { value ->
                                            items =
                                                items.toMutableList().apply {
                                                    set(index, item.copy(unitPrice = value))
                                                }
                                        },
                                        label = { Text("单价") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Field("行金额", item.amount, "receipt-item-amount-$index") { value ->
                                    items =
                                        items.toMutableList().apply {
                                            set(index, item.copy(amount = value))
                                        }
                                }
                                TextButton(
                                    onClick = { items = items.filterIndexed { i, _ -> i != index } }
                                ) {
                                    Text("移除此行")
                                }
                            }
                        }
                    }
                    TextButton(onClick = { items = items + ReceiptFields.Item("") }) {
                        Text("添加明细")
                    }
                }
                ReceiptFields.consistency(record)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
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
                    "字段规则完整度 ${(record.confidence * 100).roundToInt()}%" +
                        if (record.evidence.isEmpty()) " · 无可用依据"
                        else " · " + record.evidence.take(2).joinToString("；"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "识别错的字段可直接修改；未识别到的字段保持空白。",
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
                    "写入 PaperForge/票据记录.csv、票据记录.xlsx。XLSX 含记录与明细两张表，相同文档的相同记录不重复追加。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    tag: String,
    source: ReceiptFields.SourceLine? = null,
    onSource: ((ReceiptFields.SourceLine) -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
    if (source != null && onSource != null) {
        TextButton(
            onClick = { onSource(source) },
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.testTag("$tag-source"),
        ) {
            Text(
                "第 ${source.page + 1} 页 · ${source.text.take(42)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

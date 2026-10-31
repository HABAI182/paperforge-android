package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.model.ReceiptFields
import kotlinx.coroutines.CancellationException

/** Identical receipt flow from document cards, scan editor and the workspace. */
@Composable
fun ReceiptDocumentDialog(vm: MainViewModel, document: DocumentRow, onDismiss: () -> Unit) {
    var draft by remember(document.id) { mutableStateOf<ReceiptFields.Record?>(null) }
    var status by remember(document.id) { mutableStateOf("正在读取已有文字，缺失时离线识别…") }
    var error by remember(document.id) { mutableStateOf<String?>(null) }
    var retry by remember(document.id) { mutableIntStateOf(0) }
    val busy by vm.busy.collectAsState()
    LaunchedEffect(document.id, retry) {
        error = null
        try {
            draft = vm.receiptDraft(document, recognizeMissing = true) { status = it }
            if (draft == null) error = "未识别到文字。可重试，或手动填写票据字段。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "票据识别未完成"
        }
    }
    draft?.let { record ->
        ReceiptReviewDialog(record, document.title, busy, onDismiss) { result, xlsx ->
            vm.exportReceipt(result, document, xlsx)
            onDismiss()
        }
    }
        ?: AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("票据信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(error ?: status)
                }
            },
            confirmButton = { if (error != null) TextButton(onClick = { retry++ }) { Text("重试") } },
            dismissButton = {
                Row {
                    if (error != null)
                        TextButton(onClick = { draft = ReceiptFields.parse("") }) { Text("手动填写") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            },
        )
}

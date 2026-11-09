package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.paperforge.app.data.PageResources
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.Quad

/** Both tools edit one draft; switching panels never commits or loses changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanGeometryDialog(page: PageRow, onDismiss: () -> Unit, onSave: (PageRow) -> Unit) {
    // A finished page is already cut out and flattened, so its crop is edited on the camera photo it came
    // from, with the outline it was cut along. Only there can the outline grow past the old page.
    val source = remember(page.id, page.revision) { PageResources.sourceAppearance(page) }
    var draft by remember(page.id, page.revision) { mutableStateOf(source) }
    var rotate by remember { mutableStateOf(false) }
    fun result(): PageRow {
        if (source === page) return draft
        val unchanged =
            Quad.decode(draft.corners).points.zip(Quad.decode(source.corners).points).all { (a, b) ->
                kotlin.math.abs(a.x - b.x) < 1e-4f && kotlin.math.abs(a.y - b.y) < 1e-4f
            } &&
                draft.rotation == source.rotation &&
                draft.deskewDegrees == source.deskewDegrees &&
                draft.outputAspect == source.outputAspect
        if (unchanged) return page
        // The page's own look is kept; the view model finishes it again from the photo.
        return draft.copy(
            filter = page.filter,
            enhanceStrength = page.enhanceStrength,
            brightness = page.brightness,
            contrast = page.contrast,
        )
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("裁剪与旋转", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { onSave(result()) },
                        enabled = Quad.decode(draft.corners).valid(),
                    ) {
                        Text("保存")
                    }
                }
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    listOf("裁剪边界", "旋转校正").forEachIndexed { index, label ->
                        SegmentedButton(
                            rotate == (index == 1),
                            { rotate = index == 1 },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) {
                            Text(label)
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (rotate)
                        ScanRotationDialog(
                            draft,
                            onDismiss,
                            onSave,
                            embedded = true,
                            onDraft = { draft = it },
                        )
                    else
                        CropDialog(
                            draft,
                            onDismiss,
                            { _, _ -> },
                            embedded = true,
                            onDraft = { quad, aspect ->
                                draft =
                                    draft.copy(
                                        corners = quad.encode(),
                                        outputAspect = aspect,
                                        detectionConfidence = null,
                                        detectionMethod = "manual",
                                    )
                            },
                        )
                }
            }
        }
    }
}

@Composable
internal fun ScanToolWindow(
    embedded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (embedded) content()
    else Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false), content)
}

package cn.paperforge.app.data

import cn.paperforge.app.imaging.ScanConfig

/** Page-specific tuning shares the existing reversible metadata; it never copies page resources. */
fun PageRow.workbookDetail(): Float =
    PageResources.metadata(this)
        .optDouble(
            "workbookDetail",
            ScanConfig.float("workbookDetailPreservation", .85f).toDouble(),
        )
        .toFloat()
        .coerceIn(0f, 1f)

fun PageRow.withWorkbookDetail(value: Float): PageRow =
    copy(
        processingMetadata =
            PageResources.metadata(this).put("workbookDetail", value.coerceIn(0f, 1f)).toString()
    )

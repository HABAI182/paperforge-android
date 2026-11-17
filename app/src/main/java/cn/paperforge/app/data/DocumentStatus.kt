package cn.paperforge.app.data

import android.content.Context
import cn.paperforge.app.R

/**
 * Processing states a document can carry, stored in [DocumentRow.statuses] as codes. A derived copy inherits its
 * source's states and adds its own, so a watermark-removed copy that is then edited shows both. Labels come from string
 * resources and follow the app's language; the codes never change.
 */
enum class DocumentStatus(val code: String, val label: Int) {
    EDITED("edited", R.string.status_edited),
    WATERMARK_REMOVED("watermark-removed", R.string.status_watermark_removed),
    ANNOTATED("annotated", R.string.status_annotated),
    PDFA("pdfa", R.string.status_pdfa);

    companion object {
        fun of(row: DocumentRow): List<DocumentStatus> =
            row.statuses.split(',').mapNotNull { code -> entries.firstOrNull { it.code == code.trim() } }

        fun encode(states: Collection<DocumentStatus>) = states.distinct().sortedBy { it.ordinal }.joinToString(",") { it.code }

        fun label(context: Context, status: DocumentStatus) = context.getString(status.label)
    }
}

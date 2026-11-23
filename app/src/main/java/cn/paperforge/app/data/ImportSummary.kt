package cn.paperforge.app.data

/**
 * What a gallery import did: photos taken in, and the names of those that could not be read. One unreadable photo (a
 * cloud item that is not on the phone, a file deleted meanwhile) is skipped and reported; it no longer stops the photos
 * after it.
 */
data class ImportOutcome(val imported: Int, val failed: List<String>)

internal object ImportSummary {
    /** Names listed in a message before "等": a long list would crowd the snackbar. */
    const val NAMES_SHOWN = 3

    fun progress(done: Int, total: Int): String = "正在导入图片 ${done.coerceIn(0, total)}/$total"

    /** The message after an import, or null when every photo came in. */
    fun message(outcome: ImportOutcome): String? {
        if (outcome.failed.isEmpty()) return null
        val names = outcome.failed.take(NAMES_SHOWN).joinToString("、") + if (outcome.failed.size > NAMES_SHOWN) " 等" else ""
        return if (outcome.imported == 0) "所选 ${outcome.failed.size} 张图片都无法读取：$names"
        else "已导入 ${outcome.imported} 张，${outcome.failed.size} 张无法读取：$names"
    }

    /** The message when the user stops an import part-way. */
    fun cancelled(imported: Int, total: Int): String =
        if (imported == 0) "已取消导入" else "已取消导入，已导入 $imported/$total 张"
}

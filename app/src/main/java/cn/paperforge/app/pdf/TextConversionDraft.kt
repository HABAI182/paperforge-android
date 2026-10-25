package cn.paperforge.app.pdf

data class TextConversionDraft(
    val taskId: String,
    val name: String,
    val editedPath: String,
    val chunks: List<String>,
    val options: TextPdfOptions,
    val revision: Int = 0,
) {
    companion object {
        fun chunks(text: String): List<String> {
            val result = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = (start + 12000).coerceAtMost(text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                if (end < text.length) {
                    val newline = text.lastIndexOf('\n', end - 1)
                    if (newline > start + 6000) end = newline + 1
                }
                result += text.substring(start, end)
                start = end
            }
            return result.ifEmpty { listOf("") }
        }
    }
}

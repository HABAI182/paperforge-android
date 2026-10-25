package cn.paperforge.app

import cn.paperforge.app.pdf.TextConversionDraft
import org.junit.Assert.*
import org.junit.Test

class TextConversionDraftTest {
    @Test
    fun longEditableChunksKeepUnicodeAndOriginalSequence() {
        val original = "a".repeat(11999) + "😀" + "中文\n".repeat(6000)
        val chunks = TextConversionDraft.chunks(original)
        assertTrue(chunks.size > 1)
        assertEquals(original, chunks.joinToString(""))
        assertTrue(
            chunks.none {
                it.isNotEmpty() &&
                    (Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()))
            }
        )
    }
}

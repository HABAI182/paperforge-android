package cn.paperforge.app.model

import java.text.Normalizer
import java.util.Locale

object CjkSearch {
    fun normalize(text: String)=Normalizer.normalize(text,Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
    private fun cjk(code: Int)=code in 0x3400..0x9fff || code in 0x20000..0x323af
    private fun terms(text: String,includeSingles: Boolean): List<String> {
        val result=linkedSetOf<String>();val chinese=mutableListOf<String>();val word=StringBuilder()
        fun flushChinese() { if(includeSingles || chinese.size==1) result.addAll(chinese);chinese.zipWithNext().forEach { (a,b) -> result.add(a+b) };chinese.clear() }
        fun flushWord() { if(word.isNotEmpty()) { result.add(word.toString());word.clear() } }
        normalize(text).codePoints().toArray().forEach { code ->
            when { cjk(code) -> { flushWord();chinese.add(String(Character.toChars(code))) }
                Character.isLetterOrDigit(code) -> { flushChinese();word.appendCodePoint(code) }
                else -> { flushChinese();flushWord() }
            }
        };flushChinese();flushWord();return result.toList()
    }
    fun tokens(text: String)=terms(text,true).joinToString(" ")
    // Whitespace is portable FTS4 implicit AND; explicit AND requires an optional SQLite build flag.
    fun expression(query: String)=terms(query,false).joinToString(" ") { "\"$it\"" }.ifBlank { "\"paperforgeemptyquerysentinel\"" }
}

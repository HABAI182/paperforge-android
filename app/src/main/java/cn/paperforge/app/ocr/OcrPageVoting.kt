package cn.paperforge.app.ocr

import cn.paperforge.app.model.*

/** Repeated-prefix agreement may choose an already observed per-line hypothesis only.
 * Requires >=5 rows, >=80% raw agreement and >=90% alternative support. Raw hypotheses stay in JSON.
 */
object OcrPageVoting {
    fun apply(layout: OcrLayout): OcrLayout {
        val families=mutableMapOf<String,MutableSet<Int>>()
        fun raw(line: OcrLine)=line.hypotheses.firstOrNull { it.variant=="original" || it.variant.startsWith("rotate-") }?.text ?: line.text
        layout.lines.forEachIndexed { index,line ->
            val prefix=raw(line).take(6)
            if(prefix.length==6 && prefix.all { it.isLetter() || it=='·' }) for(position in prefix.indices) {
                families.getOrPut(prefix.replaceRange(position,position+1,"*")) { mutableSetOf() }.add(index)
            }
        }
        val replacements=mutableMapOf<Int,String>()
        families.values.filter { it.size>=5 }.sortedByDescending { it.size }.forEach { indices ->
            val votes=indices.groupingBy { raw(layout.lines[it]).take(6) }.eachCount()
            val winner=votes.maxBy { it.value }
            if(winner.value<indices.size*.8) return@forEach
            fun normalized(s: String)=if(s.startsWith("|") || s.startsWith("｜")) s.drop(1) else s
            val supported=indices.count { i -> layout.lines[i].hypotheses.any { normalized(it.text).startsWith(winner.key) } }
            if(supported<indices.size*.9) return@forEach
            indices.forEach { i ->
                val line=layout.lines[i]
                val candidate=line.hypotheses.filter { normalized(it.text).startsWith(winner.key) }.maxByOrNull { it.confidence ?: 0f }
                if(candidate!=null && (candidate.confidence ?: 0f)>=(line.confidence ?: 0f)-.10f && !line.text.startsWith(winner.key)) replacements[i]=normalized(candidate.text)
            }
        }
        return layout.copy(lines=layout.lines.mapIndexed { i,l -> replacements[i]?.let { l.copy(text=it,elements=emptyList(),variant="page-agreement") } ?: l })
    }
}

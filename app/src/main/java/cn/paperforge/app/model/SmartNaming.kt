package cn.paperforge.app.model

import java.time.LocalDate
import java.util.Locale

/**
 * #25: explainable, fully offline naming/category suggestions from the first page's OCR text.
 *
 * No model and no network: every suggestion comes from a keyword, a date/amount pattern, or the
 * short-line layout. Returning [Category.OTHER] with empty names is the explicit "no evidence"
 * answer; callers must not invent a fallback name.
 */
object SmartNaming {
    const val DEFAULT_LINE_LIMIT = 24
    const val TITLE_LIMIT = 20

    private const val MIN_SCORE = 1f
    private const val MAX_HITS_PER_KEYWORD = 3
    private const val MIN_NOTE_LINES = 6
    private const val SHORT_LINE_LIMIT = 16

    enum class Category(val label: String) {
        RECEIPT("票据"),
        CONTRACT("合同"),
        WORKBOOK("练习册"),
        NOTE("笔记"),
        OTHER("其他"),
    }

    data class Input(
        val ocrText: String,
        val pageCount: Int = 1,
        val currentName: String = "",
        val date: LocalDate? = null,
        val lineLimit: Int = DEFAULT_LINE_LIMIT,
    )

    data class Suggestion(
        val names: List<String>,
        val category: Category,
        val confidence: Float,
        val evidence: List<String>,
    )

    private data class Line(val number: Int, val rank: Int, val text: String)

    private data class DateHit(val date: LocalDate, val text: String, val line: Int)

    private data class Keyword(val text: String, val weight: Float, val regex: Boolean = false)

    private data class Scored(val score: Float, val evidence: List<String>) {
        operator fun plus(other: Scored) = Scored(score + other.score, evidence + other.evidence)
    }

    private val RECEIPT_KEYWORDS =
        listOf(
            Keyword("发票", 1f),
            Keyword("收据", 1f),
            Keyword("合计", .8f),
            Keyword("总计", .8f),
            Keyword("金额", .8f),
            Keyword("价税合计", .9f),
            Keyword("应付", .7f),
            Keyword("实付", .7f),
            Keyword("税额", .7f),
            Keyword("￥", .6f),
            Keyword("¥", .6f),
            Keyword("$", .6f),
        )

    private val CONTRACT_KEYWORDS =
        listOf(
            Keyword("合同", 1f),
            Keyword("甲方", 1f),
            Keyword("乙方", 1f),
            Keyword("条款", .9f),
            Keyword("签订", .8f),
            Keyword("签署", .8f),
            Keyword("协议", .7f),
            Keyword("双方", .7f),
            Keyword("违约", .6f),
            Keyword("履行", .6f),
        )

    private val WORKBOOK_KEYWORDS =
        listOf(
            Keyword("练习册", 1f),
            Keyword("练习", .8f),
            Keyword("姓名", 1f),
            Keyword("班级", 1f),
            Keyword("学号", .7f),
            Keyword("年级", .7f),
            Keyword("第\\s*\\d+\\s*页", .7f, regex = true),
        )

    private val NOTE_KEYWORDS =
        listOf(
            Keyword("笔记", 1f),
            Keyword("会议记录", .9f),
            Keyword("会议", .7f),
            Keyword("待办", .8f),
            Keyword("备忘", .8f),
        )

    private val DATE_PATTERNS =
        listOf(
            Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)"""),
            Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日"""),
            Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)"""),
        )

    private val MONEY = Regex("""[¥￥$]\s*\d+(?:[.,]\d{1,2})?|\d+(?:\.\d{1,2})?\s*元""")

    private val DATE_ONLY =
        Regex("""^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}$|^\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日$""")

    private val GENERIC_TITLES =
        setOf("发票", "收据", "票据", "合同", "协议", "练习册", "练习", "笔记", "姓名", "班级", "金额", "合计", "总计")

    fun suggest(input: Input): Suggestion {
        val lines = lines(input)
        if (lines.isEmpty()) return emptySuggestion()

        val dateHit = findDate(lines)
        val scored =
            linkedMapOf(
                Category.RECEIPT to scoreKeywords(lines, RECEIPT_KEYWORDS) + scoreMoney(lines),
                Category.CONTRACT to scoreKeywords(lines, CONTRACT_KEYWORDS),
                Category.WORKBOOK to scoreKeywords(lines, WORKBOOK_KEYWORDS),
                Category.NOTE to scoreKeywords(lines, NOTE_KEYWORDS) + scoreNoteLayout(lines),
            )
        val best = scored.maxByOrNull { it.value.score } ?: return emptySuggestion()
        val second = scored.filterKeys { it != best.key }.values.maxOfOrNull { it.score } ?: 0f
        // A tie is ambiguous: do not pick a category by iteration order.
        if (best.value.score < MIN_SCORE || best.value.score <= second) return emptySuggestion()

        val title = titleLine(lines, best.key)
        val date = dateHit?.date ?: input.date
        val names = namesFor(best.key, date, title, input.currentName)

        val runnerUp = scored.filterKeys { it != best.key }.maxByOrNull { it.value.score }
        val evidence =
            buildList {
                    if (dateHit != null) add("第 ${dateHit.line} 行日期：${dateHit.text}")
                    else if (date != null) add("文档日期：$date")
                    addAll(best.value.evidence)
                    val score = buildString {
                        append("评分：${best.key.label} ${format(best.value.score)}")
                        if (second > 0f && runnerUp != null) {
                            append("，次高 ${runnerUp.key.label} ${format(second)}")
                        }
                    }
                    add(score)
                }
                .take(8)

        return Suggestion(
            names = names,
            category = best.key,
            confidence =
                (best.value.score / (best.value.score + second * .6f + .5f)).coerceIn(0f, .99f),
            evidence = evidence,
        )
    }

    private fun lines(input: Input): List<Line> {
        val limit = input.lineLimit.coerceIn(1, 200)
        var rank = 0
        return input.ocrText
            .split('\n')
            .asSequence()
            .take(limit)
            .mapIndexedNotNull { index, raw ->
                val text = raw.replace(Regex("[\\p{Cntrl}]"), "").trim()
                if (text.isBlank()) null else Line(index + 1, rank++, text)
            }
            .toList()
    }

    private fun scoreKeywords(lines: List<Line>, rules: List<Keyword>): Scored {
        var score = 0f
        val hits = mutableListOf<Pair<Int, String>>()
        rules.forEach { rule ->
            var count = 0
            lines.forEach { line ->
                if (count >= MAX_HITS_PER_KEYWORD) return@forEach
                val matched =
                    if (rule.regex) {
                        Regex(rule.text, RegexOption.IGNORE_CASE).find(line.text)?.value
                    } else if (line.text.contains(rule.text, ignoreCase = true)) {
                        rule.text
                    } else {
                        null
                    }
                if (matched != null) {
                    score += rule.weight * positionWeight(line.rank)
                    hits += line.number to "第 ${line.number} 行命中「$matched」"
                    count++
                }
            }
        }
        return Scored(score, hits.sortedBy { it.first }.map { it.second })
    }

    private fun scoreMoney(lines: List<Line>): Scored {
        var score = 0f
        val hits = mutableListOf<Pair<Int, String>>()
        var count = 0
        lines.forEach { line ->
            if (count >= 2) return@forEach
            val matched = MONEY.find(line.text)?.value ?: return@forEach
            score += .9f * positionWeight(line.rank)
            hits += line.number to "第 ${line.number} 行金额：${matched.trim()}"
            count++
        }
        return Scored(score, hits.sortedBy { it.first }.map { it.second })
    }

    /**
     * Notes rarely carry a label. Require Chinese content plus a short-line majority and a title
     * line near the top; formula/handwriting OCR noise without Chinese never qualifies.
     */
    private fun scoreNoteLayout(lines: List<Line>): Scored {
        if (lines.size < MIN_NOTE_LINES) return Scored(0f, emptyList())
        val short = lines.count { it.text.length <= SHORT_LINE_LIMIT }
        val chinese = lines.count { cjkCount(it.text) >= 4 }
        if (short * 10 < lines.size * 6) return Scored(0f, emptyList())
        if (chinese < 3 || chinese * 4 < lines.size) return Scored(0f, emptyList())
        val title =
            lines.firstOrNull { it.rank <= 2 && cjkCount(it.text) >= 2 && it.text.length <= 24 }
                ?: return Scored(0f, emptyList())
        val evidence = mutableListOf("版式：${lines.size} 行中 $short 行为短行（≤$SHORT_LINE_LIMIT 字）")
        var score = 1f
        if (title.rank == 0) {
            score += .3f
            evidence += "标题行：「${truncate(title.text, 24)}」"
        }
        return Scored(score, evidence)
    }

    private fun findDate(lines: List<Line>): DateHit? {
        lines.forEach { line ->
            DATE_PATTERNS.forEach { pattern ->
                val match = pattern.find(line.text) ?: return@forEach
                val date =
                    runCatching {
                            LocalDate.of(
                                match.groupValues[1].toInt(),
                                match.groupValues[2].toInt(),
                                match.groupValues[3].toInt(),
                            )
                        }
                        .getOrNull() ?: return@forEach
                if (date.year !in 1900..2100) return@forEach
                return DateHit(date, match.value, line.number)
            }
        }
        return null
    }

    private fun titleLine(lines: List<Line>, category: Category): String? {
        lines.forEach { line ->
            val text = line.text
            if (text.length < 2) return@forEach
            if (DATE_ONLY.matches(text)) return@forEach
            if (cjkCount(text) < 2 && latinCount(text) < 3) return@forEach
            if (text in GENERIC_TITLES) return@forEach
            if (category == Category.RECEIPT && MONEY.matches(text)) return@forEach
            return truncate(text, TITLE_LIMIT)
        }
        return null
    }

    private fun namesFor(
        category: Category,
        date: LocalDate?,
        title: String?,
        currentName: String,
    ): List<String> {
        if (date == null && title == null) return emptyList()
        val prefix = category.label
        val dateText = date?.toString()
        val raw = mutableListOf<String>()
        if (dateText != null && title != null) raw += "$prefix-$dateText-$title"
        if (dateText != null) raw += "$prefix-$dateText"
        if (title != null) raw += "$prefix-$title"
        val current = safeFileName(currentName)
        return raw.map { safeFileName(it) }
            .filter { it.isNotBlank() && it != current }
            .distinct()
            .take(3)
    }

    private fun emptySuggestion() =
        Suggestion(
            names = emptyList(),
            category = Category.OTHER,
            confidence = 0f,
            evidence = emptyList(),
        )

    private fun positionWeight(rank: Int) =
        when {
            rank <= 2 -> 1f
            rank <= 7 -> .7f
            rank <= 15 -> .45f
            else -> .25f
        }

    private fun truncate(value: String, max: Int): String {
        val points = value.codePointCount(0, value.length)
        if (points <= max) return value.trim()
        return value.substring(0, value.offsetByCodePoints(0, max)).trim()
    }

    private fun cjkCount(value: String) =
        value.count { it.code in 0x3400..0x9fff || it.code in 0xf900..0xfaff }

    private fun latinCount(value: String) = value.count { it in 'A'..'Z' || it in 'a'..'z' }

    private fun format(value: Float) = String.format(Locale.ROOT, "%.2f", value)
}

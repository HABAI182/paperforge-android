package cn.paperforge.app.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * #26: rule-based receipt / invoice field extraction from already-recognised OCR text.
 *
 * No model, no network and no OCR trigger: the caller passes the text lines that the offline engine
 * already cached on the page. Missing fields stay empty (never invented) and lower
 * [Record.confidence]; the caller shows the record for confirmation before exporting.
 *
 * The three date spellings are the same ones #25 `SmartNaming` accepts (`2026-09-29`,
 * `2026年9月29日`, `20260929`). `SmartNaming` keeps those patterns private and #25 is already
 * committed, so this file repeats the same three patterns instead of changing #25; the unit tests
 * pin both spellings here.
 */
object ReceiptFields {
    const val DEFAULT_LINE_LIMIT = 80
    const val MERCHANT_LIMIT = 30
    const val MAX_EVIDENCE = 8

    /** Coarse receipt buckets shown as chips in the review form. Display-only, never written to the library. */
    enum class Category(val label: String) {
        FOOD("餐饮"),
        TRANSPORT("交通"),
        SHOPPING("购物"),
        LODGING("住宿"),
        OFFICE("办公"),
        OTHER("其他"),
    }

    data class Record(
        val date: String = "",
        val merchant: String = "",
        val amount: String = "",
        val tax: String = "",
        val invoiceNo: String = "",
        val category: Category = Category.OTHER,
        val confidence: Float = 0f,
        val evidence: List<String> = emptyList(),
    ) {
        val isBlank: Boolean
            get() =
                date.isBlank() &&
                    merchant.isBlank() &&
                    amount.isBlank() &&
                    tax.isBlank() &&
                    invoiceNo.isBlank()
    }

    private data class Line(val number: Int, val rank: Int, val text: String)

    private data class AmountMatch(val value: String, val raw: String)

    private data class AmountHit(
        val value: String,
        val raw: String,
        val label: String,
        val priority: Int,
        val quality: Float,
        val line: Line,
    )

    private data class TaxHit(val value: String, val raw: String, val line: Line)

    private data class DateHit(val date: LocalDate, val text: String, val line: Line)

    private data class MerchantHit(
        val text: String,
        val line: Line,
        val score: Float,
        val reason: String,
        val quality: Float,
    )

    private data class InvoiceHit(val value: String, val line: Line, val labeled: Boolean)

    private data class CategoryHit(val category: Category, val evidence: String?)

    // --- date: same three spellings as SmartNaming ------------------------------------------------

    private val DATE_PATTERNS =
        listOf(
            Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)"""),
            Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日"""),
            Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)"""),
        )

    private val DATE_TEXT =
        Regex(
            """(?<!\d)\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?!\d)|\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日|(?<!\d)\d{8}(?!\d)"""
        )

    private val DATE_ONLY =
        Regex("""^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}$|^\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日$""")

    // --- money ------------------------------------------------------------------------------------

    /** Currency symbol / thousand separator / 1–2 decimals; identifiers and dates are filtered later. */
    private val MONEY =
        Regex("""(?:[¥￥$]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(?:元)?""")

    /** Bigger priority wins when a document carries several totals. */
    private val AMOUNT_LABELS =
        listOf(
            "价税合计" to 6,
            "合计" to 5,
            "总计" to 4,
            "应付" to 3,
            "实付" to 3,
            "金额" to 2,
        )

    private val TAX_LABELS = listOf("税额", "税金", "税款", "税费")

    private val IDENTIFIER_WORDS =
        listOf("电话", "手机", "传真", "号码", "代码", "编号", "身份", "No", "NO", "#")

    private val DATE_SUFFIX = setOf('年', '月', '日', '时', '分', '秒')

    // --- invoice ----------------------------------------------------------------------------------

    private val INVOICE_LABELED =
        Regex(
            """(?:发票代码|发票号码|发票号|票据号码|发票编号|收据号码|单据号码|号码|No\.?|NO\.?|NO)\s*[:：]?\s*([0-9](?:[ ]?[0-9]){7,19})""",
            RegexOption.IGNORE_CASE,
        )

    private val DIGIT_RUN = Regex("""(?<!\d)\d{8,20}(?!\d)""")

    // --- merchant ---------------------------------------------------------------------------------

    private val MERCHANT_SUFFIXES =
        listOf(
            "超市",
            "便利店",
            "商场",
            "商城",
            "百货",
            "餐厅",
            "饭店",
            "餐馆",
            "咖啡",
            "酒店",
            "宾馆",
            "旅馆",
            "民宿",
            "药房",
            "药店",
            "公司",
            "中心",
            "加油站",
            "书店",
            "市场",
            "门诊",
            "医院",
            "店",
        )

    private val MERCHANT_PREFIXES =
        listOf("销售方", "商户", "商家", "店名", "收款方", "开票方", "单位名称", "名称")

    private val GENERIC_MERCHANT =
        setOf(
            "发票",
            "收据",
            "票据",
            "小票",
            "单据",
            "欢迎光临",
            "谢谢惠顾",
            "谢谢光临",
            "销售清单",
            "购物清单",
            "消费清单",
        )

    // --- classification ---------------------------------------------------------------------------

    private val CATEGORY_KEYWORDS =
        linkedMapOf(
            Category.FOOD to
                listOf(
                    "餐厅" to 1f,
                    "饭店" to 1f,
                    "餐馆" to 1f,
                    "美食" to .9f,
                    "咖啡" to .9f,
                    "奶茶" to .9f,
                    "火锅" to .9f,
                    "烧烤" to .9f,
                    "食堂" to .8f,
                    "酒楼" to .8f,
                    "餐" to .6f,
                ),
            Category.TRANSPORT to
                listOf(
                    "地铁" to 1f,
                    "公交" to 1f,
                    "出租车" to 1f,
                    "打车" to .9f,
                    "高铁" to 1f,
                    "火车" to .9f,
                    "机票" to 1f,
                    "机场" to .8f,
                    "加油" to .9f,
                    "停车" to .8f,
                    "过路" to .8f,
                    "ETC" to .8f,
                ),
            Category.SHOPPING to
                listOf(
                    "超市" to 1f,
                    "便利店" to 1f,
                    "商场" to 1f,
                    "商城" to .9f,
                    "百货" to .9f,
                    "购物" to .8f,
                    "旗舰店" to .8f,
                    "专卖店" to .8f,
                ),
            Category.LODGING to
                listOf(
                    "酒店" to 1f,
                    "宾馆" to 1f,
                    "旅馆" to 1f,
                    "住宿" to .9f,
                    "民宿" to .9f,
                    "客房" to .8f,
                ),
            Category.OFFICE to
                listOf(
                    "文具" to 1f,
                    "办公" to 1f,
                    "打印" to .9f,
                    "复印" to .9f,
                    "耗材" to .9f,
                    "纸张" to .8f,
                    "图书" to .8f,
                    "书店" to .8f,
                ),
        )

    // --- confidence weights -----------------------------------------------------------------------

    private const val WEIGHT_DATE = .20f
    private const val WEIGHT_MERCHANT = .18f
    private const val WEIGHT_AMOUNT = .30f
    private const val WEIGHT_TAX = .12f
    private const val WEIGHT_INVOICE = .16f

    fun parse(ocrText: String, lineLimit: Int = DEFAULT_LINE_LIMIT): Record {
        val lines = lines(ocrText, lineLimit)
        if (lines.isEmpty()) return Record()

        val dateHit = findDate(lines)
        val amounts = amountHits(lines)
        val amount = amounts.firstOrNull()
        val tax = taxHit(lines)
        val invoice = invoiceHit(lines)
        val merchant = merchantHit(lines)
        val category = classify(lines)

        var confidence = 0f
        if (dateHit != null) confidence += WEIGHT_DATE * positionFactor(dateHit.line.rank)
        if (merchant != null) {
            confidence +=
                WEIGHT_MERCHANT * positionFactor(merchant.line.rank) * merchant.quality
        }
        if (amount != null) {
            confidence += WEIGHT_AMOUNT * positionFactor(amount.line.rank) * amount.quality
        }
        if (tax != null) confidence += WEIGHT_TAX * positionFactor(tax.line.rank)
        if (invoice != null) {
            confidence +=
                WEIGHT_INVOICE *
                    positionFactor(invoice.line.rank) *
                    (if (invoice.labeled) 1f else .8f)
        }

        val evidence =
            buildList {
                    if (dateHit != null) add("第 ${dateHit.line.number} 行日期：${dateHit.text}")
                    if (merchant != null) {
                        add("第 ${merchant.line.number} 行商家「${merchant.text}」（${merchant.reason}）")
                    }
                    if (amount != null) {
                        val label = amount.label.ifBlank { "货币符号/末行金额" }
                        add("第 ${amount.line.number} 行「$label」金额：${amount.raw}")
                        if (amounts.size > 1) add("另有 ${amounts.size - 1} 个金额候选，按标签优先级取此值")
                    }
                    if (tax != null) add("第 ${tax.line.number} 行税额：${tax.raw}")
                    if (invoice != null) {
                        add(
                            if (invoice.labeled) "第 ${invoice.line.number} 行发票号：${invoice.value}"
                            else "第 ${invoice.line.number} 行长数字串：${invoice.value}"
                        )
                    }
                    category.evidence?.let { add(it) }
                }
                .take(MAX_EVIDENCE)

        return Record(
            date = dateHit?.date?.toString().orEmpty(),
            merchant = merchant?.text.orEmpty(),
            amount = amount?.value.orEmpty(),
            tax = tax?.value.orEmpty(),
            invoiceNo = invoice?.value.orEmpty(),
            category = category.category,
            confidence = confidence.coerceIn(0f, .99f),
            evidence = evidence,
        )
    }

    // --- amount -----------------------------------------------------------------------------------

    /** One hit per line (its strongest label), best candidate first; labelled beats symbol-only. */
    private fun amountHits(lines: List<Line>): List<AmountHit> {
        val labeled = mutableListOf<AmountHit>()
        val fallback = mutableListOf<AmountHit>()
        for (line in lines) {
            var best: AmountHit? = null
            var bestPriority = -1
            for ((label, priority) in AMOUNT_LABELS) {
                if (bestPriority >= priority) continue
                val index = line.text.indexOf(label)
                if (index < 0) continue
                val match = firstMoney(line.text, index + label.length) ?: continue
                best =
                    AmountHit(
                        match.value,
                        match.raw,
                        label,
                        priority,
                        amountQuality(priority),
                        line,
                    )
                bestPriority = priority
            }
            val lineHit = best
            if (lineHit != null) {
                labeled += lineHit
                continue
            }
            if (hasAmountLabel(line.text) || hasTaxLabel(line.text)) continue
            if (DATE_TEXT.containsMatchIn(line.text)) continue
            val match = firstMoney(line.text, 0) ?: continue
            val raw = match.raw
            if (
                !raw.contains('¥') &&
                    !raw.contains('￥') &&
                    !raw.contains('$') &&
                    !raw.contains('元') &&
                    !raw.contains('.')
            )
                continue
            fallback += AmountHit(match.value, raw, "", 1, .7f, line)
        }
        val ordered =
            labeled.sortedWith(
                compareByDescending<AmountHit> { it.priority }.thenBy { it.line.rank }
            )
        // Without any total label the bottom-most currency/decimal amount is the best guess.
        return ordered.ifEmpty { fallback.lastOrNull()?.let(::listOf).orEmpty() }
    }

    private fun firstMoney(text: String, from: Int): AmountMatch? {
        for (match in MONEY.findAll(text, from)) {
            val value = normalizeMoney(match.value) ?: continue
            if (!plausibleMoney(text, match.range)) continue
            return AmountMatch(value, match.value.trim())
        }
        return null
    }

    private fun normalizeMoney(raw: String): String? {
        val cleaned = raw.filter { it.isDigit() || it == ',' || it == '.' }.replace(",", "")
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0) return null
        // Reject identifiers: phone numbers, invoice codes and dates never reach this shape anyway,
        // but an unbounded integer such as 13812345678 must not become an amount.
        if (value.precision() - value.scale() > 8) return null
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private fun plausibleMoney(line: String, range: IntRange): Boolean {
        val start = range.first
        val end = range.last + 1
        if (start > 0 && line[start - 1].isDigit()) return false
        if (end < line.length && line[end].isDigit()) return false
        val tail = line.substring(end).trimStart()
        if (tail.isNotEmpty() && (tail[0] in DATE_SUFFIX || tail[0] == '-' || tail[0] == '/' || tail[0] == '.')) {
            return false
        }
        val prefix = line.substring(maxOf(0, start - 6), start)
        if (IDENTIFIER_WORDS.any { prefix.contains(it, ignoreCase = true) }) return false
        return true
    }

    private fun amountQuality(priority: Int) =
        when {
            priority >= 5 -> 1f
            priority >= 3 -> .95f
            priority >= 2 -> .85f
            else -> .7f
        }

    private fun hasAmountLabel(text: String) = AMOUNT_LABELS.any { text.contains(it.first) }

    private fun hasTaxLabel(text: String) = TAX_LABELS.any { text.contains(it) }

    // --- tax --------------------------------------------------------------------------------------

    private fun taxHit(lines: List<Line>): TaxHit? {
        for (line in lines) {
            for (label in TAX_LABELS) {
                val index = line.text.indexOf(label)
                if (index < 0) continue
                val match = firstMoney(line.text, index + label.length) ?: continue
                return TaxHit(match.value, match.raw, line)
            }
        }
        return null
    }

    // --- date -------------------------------------------------------------------------------------

    private fun findDate(lines: List<Line>): DateHit? {
        for (line in lines) {
            for (pattern in DATE_PATTERNS) {
                val match = pattern.find(line.text) ?: continue
                val date =
                    runCatching {
                            LocalDate.of(
                                match.groupValues[1].toInt(),
                                match.groupValues[2].toInt(),
                                match.groupValues[3].toInt(),
                            )
                        }
                        .getOrNull() ?: continue
                if (date.year !in 1900..2100) continue
                return DateHit(date, match.value, line)
            }
        }
        return null
    }

    // --- invoice number ---------------------------------------------------------------------------

    private fun invoiceHit(lines: List<Line>): InvoiceHit? {
        for (line in lines) {
            val match = INVOICE_LABELED.find(line.text) ?: continue
            val prefix = line.text.substring(0, match.range.first)
            if (prefix.contains("电话") || prefix.contains("手机") || prefix.contains("传真")) {
                continue
            }
            val digits = match.groupValues[1].replace(" ", "")
            if (digits.length !in 8..20) continue
            if (isDateDigits(digits)) continue
            return InvoiceHit(digits, line, true)
        }
        for (line in lines) {
            if (line.text.contains("电话") || line.text.contains("手机") || line.text.contains("身份")) {
                continue
            }
            if (
                line.text.any { it == '¥' || it == '￥' || it == '$' } ||
                    hasAmountLabel(line.text) ||
                    hasTaxLabel(line.text)
            )
                continue
            val match = DIGIT_RUN.find(line.text) ?: continue
            if (isDateDigits(match.value)) continue
            return InvoiceHit(match.value, line, false)
        }
        return null
    }

    private fun isDateDigits(digits: String): Boolean {
        if (digits.length != 8) return false
        return runCatching {
                LocalDate.of(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
            }
            .isSuccess
    }

    // --- merchant ---------------------------------------------------------------------------------

    private fun merchantHit(lines: List<Line>): MerchantHit? {
        var best: MerchantHit? = null
        for (line in lines) {
            val hit = merchantCandidate(line) ?: continue
            if (best == null || hit.score > best.score) best = hit
        }
        return best
    }

    private fun merchantCandidate(line: Line): MerchantHit? {
        var text = line.text
        if (text.length < 2) return null
        if (DATE_ONLY.matches(text) || DATE_TEXT.containsMatchIn(text)) return null
        if (text.any { it == '¥' || it == '￥' || it == '$' }) return null
        if (hasAmountLabel(text) || hasTaxLabel(text)) return null
        val prefix = MERCHANT_PREFIXES.firstOrNull { text.startsWith(it) }
        if (prefix != null) text = text.removePrefix(prefix).trimStart('：', ':', ' ', '（', '(')
        if (text in GENERIC_MERCHANT) return null
        val cjk = cjkCount(text)
        val latin = latinCount(text)
        if (cjk < 2 && latin < 3) return null
        val suffix = MERCHANT_SUFFIXES.firstOrNull { text.contains(it) }
        val score =
            when {
                suffix != null -> 1f + (if (line.rank == 0) .3f else if (line.rank <= 2) .15f else 0f)
                line.rank == 0 -> .6f
                line.rank <= 2 -> .45f
                else -> return null
            }
        val clean = cleanMerchant(text)
        if (clean.length < 2) return null
        return MerchantHit(
            clean,
            line,
            score,
            if (suffix != null) "含「$suffix」" else "首行位置",
            if (suffix != null) 1f else .85f,
        )
    }

    private fun cleanMerchant(raw: String): String {
        var text = raw.trim().trim('：', ':', '-', '—', '|', '·', '，', ',', '。', '.', ' ')
        GENERIC_MERCHANT.forEach { generic ->
            if (text.length > generic.length + 1) text = text.removeSuffix(generic).trim()
        }
        text = text.replace(Regex("""\s+"""), " ")
        return truncate(text, MERCHANT_LIMIT)
    }

    // --- classification ---------------------------------------------------------------------------

    private fun classify(lines: List<Line>): CategoryHit {
        var bestCategory: Category? = null
        var bestScore = 0f
        var bestEvidence: String? = null
        CATEGORY_KEYWORDS.forEach { (category, keywords) ->
            var score = 0f
            var first: Pair<String, Line>? = null
            for (keyword in keywords) {
                for (line in lines) {
                    if (!line.text.contains(keyword.first, ignoreCase = true)) continue
                    score += keyword.second * positionFactor(line.rank)
                    if (first == null) first = keyword.first to line
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestCategory = category
                bestEvidence = first?.let { "第 ${it.second.number} 行分类词「${it.first}」" }
            }
        }
        return CategoryHit(bestCategory ?: Category.OTHER, bestEvidence)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun lines(text: String, lineLimit: Int): List<Line> {
        val limit = lineLimit.coerceIn(1, 200)
        var rank = 0
        val out = mutableListOf<Line>()
        val rows = text.split('\n')
        for (index in rows.indices) {
            if (index >= limit) break
            val cleaned = rows[index].replace(Regex("[\\p{Cntrl}]"), "").trim()
            if (cleaned.isEmpty()) continue
            out += Line(index + 1, rank++, cleaned)
        }
        return out
    }

    /** Hits near the receipt header carry more weight; totals at the bottom keep a mild floor. */
    private fun positionFactor(rank: Int) =
        when {
            rank <= 3 -> 1f
            rank <= 8 -> .92f
            rank <= 20 -> .82f
            else -> .7f
        }

    private fun truncate(value: String, max: Int): String {
        val points = value.codePointCount(0, value.length)
        if (points <= max) return value.trim()
        return value.substring(0, value.offsetByCodePoints(0, max)).trim()
    }

    private fun cjkCount(value: String) =
        value.count { it.code in 0x3400..0x9fff || it.code in 0xf900..0xfaff }

    private fun latinCount(value: String) = value.count { it in 'A'..'Z' || it in 'a'..'z' }
}

package cn.paperforge.app.pdf

/** Local hints only: the user chooses a match and explicitly creates a redacted copy. */
object SensitiveTextCandidates {
    data class Candidate(
        val kind: String,
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val source: String,
        val precise: Boolean,
    )

    private val email =
        Regex(
            "[A-Za-z0-9.!#\$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+"
        )
    private val id =
        Regex(
            "(?<![0-9A-Za-z])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9A-Za-z])"
        )
    private val phone =
        Regex(
            "(?<![0-9A-Za-z])(?:\\+?86[- ]?)?1[3-9][0-9][- ]?[0-9]{4}[- ]?[0-9]{4}(?![0-9A-Za-z])"
        )
    private val bank = Regex("(?<![0-9A-Za-z])(?:[0-9][- ]?){12,18}[0-9](?![0-9A-Za-z])")

    private fun luhn(value: String): Boolean {
        if (value.all { it == value.first() }) return false
        return value
            .reversed()
            .mapIndexed { index, char ->
                val digit = char - '0'
                if (index % 2 == 0) digit else (digit * 2).let { if (it > 9) it - 9 else it }
            }
            .sum() % 10 == 0
    }

    fun find(blocks: List<ReaderTextBlock>): List<Candidate> =
        buildList {
                blocks.forEach { block ->
                    // Full-width digits/Latin characters become ASCII without changing UTF-16
                    // offsets.
                    val value =
                        block.text
                            .map {
                                if (it in '\uFF01'..'\uFF5E') (it.code - 0xFEE0).toChar() else it
                            }
                            .joinToString("")
                    val occupied = mutableListOf<IntRange>()
                    listOf("邮箱" to email, "身份证格式" to id, "手机号" to phone, "银行卡格式" to bank).forEach {
                        (kind, pattern) ->
                        pattern.findAll(value).forEach match@{ match ->
                            if (
                                occupied.any {
                                    match.range.first <= it.last && match.range.last >= it.first
                                }
                            )
                                return@match
                            if (kind == "银行卡格式" && !luhn(match.value.filter(Char::isDigit)))
                                return@match
                            val units =
                                block.units.filter {
                                    it.start <= match.range.last && it.end > match.range.first
                                }
                            occupied += match.range
                            add(
                                Candidate(
                                    kind,
                                    block.text.substring(match.range),
                                    units.minOfOrNull { it.left } ?: block.left,
                                    units.minOfOrNull { it.top } ?: block.top,
                                    units.maxOfOrNull { it.right } ?: block.right,
                                    units.maxOfOrNull { it.bottom } ?: block.bottom,
                                    block.source,
                                    units.isNotEmpty(),
                                )
                            )
                        }
                    }
                }
            }
            .distinctBy { listOf(it.kind, it.text, it.left, it.top, it.right, it.bottom) }
            .sortedWith(compareBy<Candidate> { it.top }.thenBy { it.left })
            .take(200)
}

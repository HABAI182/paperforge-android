package cn.paperforge.app.model

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SmartNamingTest {
    private fun suggest(
        text: String,
        current: String = "",
        pageCount: Int = 1,
        date: LocalDate? = null,
        lineLimit: Int = SmartNaming.DEFAULT_LINE_LIMIT,
    ) =
        SmartNaming.suggest(
            SmartNaming.Input(
                ocrText = text,
                pageCount = pageCount,
                currentName = current,
                date = date,
                lineLimit = lineLimit,
            )
        )

    @Test
    fun receiptWithIsoDateProducesOrderedNamesAndEvidence() {
        val suggestion =
            suggest(
                """
                星河超市
                发票
                2026-09-29
                苹果 10.00
                合计 ￥128.00
                谢谢惠顾
                """
                    .trimIndent()
            )
        assertEquals(SmartNaming.Category.RECEIPT, suggestion.category)
        assertEquals(listOf("票据-2026-09-29-星河超市", "票据-2026-09-29", "票据-星河超市"), suggestion.names)
        assertTrue(suggestion.confidence > .7f)
        assertTrue(suggestion.evidence.any { it.contains("日期：2026-09-29") })
        assertTrue(suggestion.evidence.any { it.contains("发票") })
        assertTrue(suggestion.evidence.any { it.contains("合计") })
        assertTrue(suggestion.evidence.last().startsWith("评分：票据"))
    }

    @Test
    fun contractWithChineseDateProducesContractName() {
        val suggestion =
            suggest(
                """
                采购合同
                甲方：星河科技有限公司
                乙方：张三
                签订日期：2026年9月29日
                第一条 服务条款
                双方应履行本协议
                """
                    .trimIndent()
            )
        assertEquals(SmartNaming.Category.CONTRACT, suggestion.category)
        assertEquals("合同-2026-09-29-采购合同", suggestion.names.first())
        assertTrue(suggestion.evidence.any { it.contains("甲方") })
        assertTrue(suggestion.evidence.any { it.contains("签订") })
    }

    @Test
    fun workbookCatchesNameClassAndPageNumber() {
        val suggestion =
            suggest(
                """
                数学练习册
                姓名：李小明
                班级：三年二班
                学号：2026001
                第12页 口算练习
                """
                    .trimIndent()
            )
        assertEquals(SmartNaming.Category.WORKBOOK, suggestion.category)
        assertEquals("练习册-数学练习册", suggestion.names.first())
        assertTrue(suggestion.evidence.any { it.contains("姓名") })
        assertTrue(suggestion.evidence.any { it.contains("第12页") })
    }

    @Test
    fun noteUsesShortLineLayoutAndTitle() {
        val suggestion =
            suggest(
                """
                周末计划
                买菜
                读书
                跑步
                写周报
                整理照片
                给妈妈打电话
                早睡
                """
                    .trimIndent()
            )
        assertEquals(SmartNaming.Category.NOTE, suggestion.category)
        assertEquals(listOf("笔记-周末计划"), suggestion.names)
        assertTrue(suggestion.evidence.any { it.contains("短行") })
        assertTrue(suggestion.evidence.any { it.contains("标题行") })
    }

    @Test
    fun blankTextReturnsNoSuggestion() {
        val suggestion = suggest("   \n\t\n")
        assertEquals(SmartNaming.Category.OTHER, suggestion.category)
        assertTrue(suggestion.names.isEmpty())
        assertEquals(0f, suggestion.confidence, 0f)
        assertTrue(suggestion.evidence.isEmpty())
    }

    @Test
    fun formulaNoiseWithoutKeywordsReturnsNoSuggestion() {
        val suggestion =
            suggest(
                """
                3X, +
                X4:-》
                1S片-Xt-27r=3
                X,+2X、t
                R,3R
                Xy 2.
                """
                    .trimIndent()
            )
        assertEquals(SmartNaming.Category.OTHER, suggestion.category)
        assertTrue(suggestion.names.isEmpty())
        assertTrue(suggestion.evidence.isEmpty())
    }

    @Test
    fun overlongTitleIsTruncatedAndSanitized() {
        val title = "超长标题" + "字".repeat(80) + "/:*?\"<>|\u0001"
        val suggestion =
            suggest(
                """
                发票
                2026-09-29
                $title
                合计 ￥1.00
                """
                    .trimIndent()
            )
        assertEquals(SmartNaming.Category.RECEIPT, suggestion.category)
        val name = suggestion.names.first()
        assertFalse(name.contains("\n"))
        assertFalse(name.contains("\u0001"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
        assertTrue(name.length <= 34)
        // Prefix + date + a 20-character title.
        assertTrue(name.startsWith("票据-2026-09-29-超长标题"))
    }

    @Test
    fun moreWinningHitsRaiseConfidenceMonotonically() {
        val weak = suggest("发票\n合计 10.00")
        val strong = suggest("发票\n合计 10.00\n金额 10.00\n收据\n总计 10.00")
        assertEquals(SmartNaming.Category.RECEIPT, weak.category)
        assertEquals(SmartNaming.Category.RECEIPT, strong.category)
        assertTrue(strong.confidence > weak.confidence)
    }

    @Test
    fun currentNameIsRemovedWithoutReorderingOrDuplicates() {
        val text = "星河超市\n发票\n2026-09-29\n合计 ￥128.00"
        val first = suggest(text)
        val filtered = suggest(text, current = first.names.first())
        assertEquals(first.names.drop(1), filtered.names)
        assertEquals(filtered.names.distinct(), filtered.names)
    }

    @Test
    fun metadataDateIsUsedWhenOcrHasNone() {
        val suggestion = suggest("星河超市\n发票\n合计 ￥128.00", date = LocalDate.of(2026, 1, 2))
        assertTrue(suggestion.names.contains("票据-2026-01-02"))
        assertTrue(suggestion.evidence.any { it.contains("文档日期：2026-01-02") })
    }

    @Test
    fun onlyFirstConfiguredLinesAreRead() {
        val lines = (1..24).joinToString("\n") { "第 $it 行" } + "\n发票\n发票\n发票\n收据\n收据\n收据"
        val suggestion = suggest(lines, lineLimit = 24)
        assertEquals(SmartNaming.Category.OTHER, suggestion.category)
        assertTrue(suggestion.names.isEmpty())
        val withTail = suggest(lines, lineLimit = 40)
        assertEquals(SmartNaming.Category.RECEIPT, withTail.category)
    }

    @Test
    fun pageCountAndAmbiguousCategoriesDoNotForceAName() {
        val tie = suggest("甲方\n姓名", pageCount = 3)
        assertEquals(SmartNaming.Category.OTHER, tie.category)
        assertTrue(tie.names.isEmpty())
    }
}

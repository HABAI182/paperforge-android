package cn.paperforge.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #26: pure-rule checks for receipt field extraction. No Android, no OCR: every case is a text
 * fixture written in the test, so these numbers are deterministic.
 */
class ReceiptFieldsTest {

    @Test
    fun isoDateCurrencyAndTotalAreExtracted() {
        val record =
            ReceiptFields.parse(
                """
                星河超市
                发票
                2026-09-29
                苹果 10.00
                合计 ￥128.00
                """
                    .trimIndent()
            )
        assertEquals("2026-09-29", record.date)
        assertEquals("星河超市", record.merchant)
        assertEquals("128.00", record.amount)
        assertEquals("", record.tax)
        assertEquals("", record.invoiceNo)
        assertEquals(ReceiptFields.Category.SHOPPING, record.category)
        assertTrue("置信度应高于 0.6，实际 ${record.confidence}", record.confidence > .6f)
        assertTrue(record.evidence.any { it.contains("合计") })
        assertTrue(record.evidence.any { it.contains("日期") })
    }

    @Test
    fun chineseDateLabeledInvoiceAndTaxUsePriceTaxTotal() {
        val record =
            ReceiptFields.parse(
                """
                增值税发票
                2026年9月29日
                销售方：晨光文具店
                发票号码：0123456789
                金额 ￥100.00
                税额 ￥13.00
                价税合计 ￥113.00
                """
                    .trimIndent()
            )
        assertEquals("2026-09-29", record.date)
        assertEquals("晨光文具店", record.merchant)
        assertEquals("113.00", record.amount)
        assertEquals("13.00", record.tax)
        assertEquals("0123456789", record.invoiceNo)
        assertEquals(ReceiptFields.Category.OFFICE, record.category)
        assertTrue("置信度应高于 0.85，实际 ${record.confidence}", record.confidence > .85f)
    }

    @Test
    fun slashAndDotDatesAreAccepted() {
        assertEquals("2026-09-29", ReceiptFields.parse("2026/9/29\n合计 1.00").date)
        assertEquals("2026-09-29", ReceiptFields.parse("2026.9.29\n合计 1.00").date)
        // An impossible date is not invented; the field stays empty.
        assertEquals("", ReceiptFields.parse("2026-02-31\n合计 1.00").date)
    }

    @Test
    fun compactDateIsNotMistakenForInvoiceNumber() {
        val record =
            ReceiptFields.parse(
                """
                便利店
                20260929
                合计 9.90
                """
                    .trimIndent()
            )
        assertEquals("2026-09-29", record.date)
        assertEquals("9.90", record.amount)
        assertEquals("", record.invoiceNo)
    }

    @Test
    fun thousandSeparatorsAndDollarSignAreNormalised() {
        val record =
            ReceiptFields.parse(
                """
                Coffee Shop
                2026-09-29
                Latte $9.90
                Total $1,280.50
                """
                    .trimIndent()
            )
        assertEquals("1280.50", record.amount)
        assertEquals("Coffee Shop", record.merchant)
        assertEquals("2026-09-29", record.date)
    }

    @Test
    fun chineseTotalBeatsSmallerLateCandidates() {
        val record =
            ReceiptFields.parse(
                """
                餐厅
                2026-09-29
                餐费 48.00
                合计 ￥58.00
                实付 ￥50.00
                """
                    .trimIndent()
            )
        assertEquals("58.00", record.amount)
        assertTrue(record.evidence.any { it.contains("另有") })
        assertTrue(record.evidence.any { it.contains("合计") })
    }

    @Test
    fun taxIsReadOnThePriceTaxTotalLineButPriceTaxTotalIsNotTax() {
        val withBoth =
            ReceiptFields.parse(
                """
                酒店
                2026-09-29
                价税合计 ￥1,060.00 税额 ￥60.00
                """
                    .trimIndent()
            )
        assertEquals("1060.00", withBoth.amount)
        assertEquals("60.00", withBoth.tax)

        val totalOnly =
            ReceiptFields.parse(
                """
                酒店
                2026-09-29
                价税合计 ￥1,060.00
                """
                    .trimIndent()
            )
        assertEquals("1060.00", totalOnly.amount)
        assertEquals("价税合计不应被当成税额", "", totalOnly.tax)
    }

    @Test
    fun merchantFallsBackToTheFirstMeaningfulLine() {
        val record =
            ReceiptFields.parse(
                """
                老张面馆
                2026-09-29
                合计 20.00
                """
                    .trimIndent()
            )
        assertEquals("老张面馆", record.merchant)
        assertTrue(record.evidence.any { it.contains("首行位置") })
    }

    @Test
    fun missingFieldsStayEmptyAndLowerConfidence() {
        val record =
            ReceiptFields.parse(
                """
                收据
                2026-09-29
                """
                    .trimIndent()
            )
        assertEquals("2026-09-29", record.date)
        assertEquals("", record.merchant)
        assertEquals("", record.amount)
        assertEquals("", record.tax)
        assertEquals("", record.invoiceNo)
        assertEquals(ReceiptFields.Category.OTHER, record.category)
        assertTrue("只有日期时置信度应偏低，实际 ${record.confidence}", record.confidence < .3f)
    }

    @Test
    fun blankTextYieldsAnEmptyRecord() {
        for (text in listOf("", "   ", "\n\n\n", "\r\n\t ")) {
            val record = ReceiptFields.parse(text)
            assertTrue(record.isBlank)
            assertEquals("", record.date)
            assertEquals("", record.merchant)
            assertEquals("", record.amount)
            assertEquals(0f, record.confidence, 0f)
            assertTrue(record.evidence.isEmpty())
            assertEquals(ReceiptFields.Category.OTHER, record.category)
        }
    }

    @Test
    fun outOfOrderLinesAreStillExtracted() {
        val record =
            ReceiptFields.parse(
                """
                合计 ￥66.00
                2026-09-29
                发票号：12345678
                星海餐厅
                """
                    .trimIndent()
            )
        assertEquals("66.00", record.amount)
        assertEquals("2026-09-29", record.date)
        assertEquals("12345678", record.invoiceNo)
        assertEquals("星海餐厅", record.merchant)
    }

    @Test
    fun moreFieldsRaiseConfidenceMonotonically() {
        val dateOnly = ReceiptFields.parse("2026-09-29")
        val withMerchant = ReceiptFields.parse("星河超市\n2026-09-29")
        val withAmount = ReceiptFields.parse("星河超市\n2026-09-29\n合计 128.00")
        assertTrue(dateOnly.confidence > 0f)
        assertTrue(withMerchant.confidence > dateOnly.confidence)
        assertTrue(withAmount.confidence > withMerchant.confidence)
    }

    @Test
    fun theSameEvidenceLowerInTheDocumentScoresLess() {
        val top =
            ReceiptFields.parse(
                """
                星河超市
                2026-09-29
                合计 128.00
                """
                    .trimIndent()
            )
        val bottom =
            ReceiptFields.parse(
                buildString {
                    append("收据\n")
                    repeat(24) { append("商品 ${it + 1}\n") }
                    append("星河超市\n2026-09-29\n合计 128.00")
                }
            )
        assertEquals(top.amount, bottom.amount)
        assertTrue("靠后的依据不应更高：${top.confidence} vs ${bottom.confidence}", bottom.confidence < top.confidence)
    }

    @Test
    fun overlongTextIsBoundedAndLineLimitIsHonoured() {
        val junk = (1..5000).joinToString("\n") { "商品 $it 1.00" }
        val record = ReceiptFields.parse("合计 ￥9.00\n$junk")
        assertEquals("9.00", record.amount)
        assertTrue("evidence 超过上限", record.evidence.size <= ReceiptFields.MAX_EVIDENCE)

        // A total that sits past the configured line limit must not be guessed from far below.
        val hidden = ReceiptFields.parse("2026-09-29\n$a_junk_but_no_amount", lineLimit = 2)
        assertEquals("2026-09-29", hidden.date)
        assertEquals("", hidden.amount)

        val farTotal =
            ReceiptFields.parse(
                buildString {
                    append("2026-09-29\n")
                    repeat(30) { append("商品 ${it + 1}\n") }
                    append("合计 ￥9.00")
                },
                lineLimit = 12,
            )
        assertFalse("第 12 行之后的合计不应参与", farTotal.amount == "9.00")
    }

    private val a_junk_but_no_amount = (1..50).joinToString("\n") { "商品 $it" }

    @Test
    fun identifiersAreNotMistakenForAmountsOrInvoiceNumbers() {
        val record =
            ReceiptFields.parse(
                """
                便利店
                2026-09-29
                电话 13812345678
                订单号 87654321
                合计 ￥12.00
                """
                    .trimIndent()
            )
        assertEquals("12.00", record.amount)
        assertEquals("", record.tax)
    }
}

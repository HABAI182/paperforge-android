package cn.paperforge.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cn.paperforge.app.model.ReceiptFields
import cn.paperforge.app.ui.ReceiptReviewDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * #26: the confirmation form is editable. Field edits and a category chip must reach the export
 * callback, and both buttons must only fire when pressed. The file writing itself is covered by
 * [ReceiptExportFlowTest].
 */
@RunWith(AndroidJUnit4::class)
class ReceiptReviewDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editedFieldsAndCategoryChipReachTheExportCallback() {
        val record =
            ReceiptFields.Record(
                date = "2026-09-29",
                merchant = "星河超市",
                amount = "128.00",
                tax = "12.80",
                invoiceNo = "0123456789",
                category = ReceiptFields.Category.SHOPPING,
                confidence = .91f,
                evidence = listOf("第 2 行日期：2026-09-29"),
            )
        var exported: Pair<ReceiptFields.Record, Boolean>? = null
        compose.setContent {
            ReceiptReviewDialog(
                initial = record,
                sourceTitle = "测试票据",
                busy = false,
                onDismiss = {},
                onExport = { edited, xlsx -> exported = edited to xlsx },
            )
        }
        compose.onNodeWithTag("receipt-date").assertTextContains("2026-09-29", substring = true)
        compose.onNodeWithTag("receipt-merchant").assertTextContains("星河超市", substring = true)
        compose.onNodeWithTag("receipt-amount").performTextClearance()
        compose.onNodeWithTag("receipt-amount").performTextInput("256.00")
        compose.onNodeWithTag("receipt-category-FOOD").performClick()
        assertNull("未点导出前不应回调", exported)
        compose.onNodeWithTag("receipt-export-csv").performClick()
        val (edited, xlsx) = exported!!
        assertFalse(xlsx)
        assertEquals("256.00", edited.amount)
        assertEquals(ReceiptFields.Category.FOOD, edited.category)
        assertEquals("星河超市", edited.merchant)
        assertEquals("0123456789", edited.invoiceNo)

        compose.onNodeWithTag("receipt-export-xlsx").performClick()
        assertTrue("XLSX 按钮应带上 xlsx 标记", exported!!.second)
        assertEquals("256.00", exported!!.first.amount)
    }
}

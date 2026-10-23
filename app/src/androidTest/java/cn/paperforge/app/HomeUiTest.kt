package cn.paperforge.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

class HomeUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun homeToolsAndPrivacyAreUsable() {
        compose.onNodeWithText("纸匠",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithText("扫描文档").assertIsDisplayed()
        compose.onNodeWithText("工具",useUnmergedTree=true).performClick()
        compose.onNodeWithText("小工具，大用处").assertIsDisplayed()
        compose.onNodeWithContentDescription("关于与隐私").performClick()
        compose.onNodeWithText("纸匠 PaperForge · 0.2.0").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
    }
    @Test fun pdfRendersAndCanScrollAndJump() {
        val repo=(compose.activity.application as PaperForgeApp).repository
        val title="UI 阅读验收 ${System.currentTimeMillis()}"
        val file=File(repo.exports,"ui-reader-${System.currentTimeMillis()}.pdf")
        PDDocument().use { pdf ->
            repeat(3) { index ->
                val page=PDPage(PDRectangle.A4); pdf.addPage(page)
                PDPageContentStream(pdf,page).use { c -> c.beginText(); c.setFont(PDType1Font.HELVETICA,24f); c.newLineAtOffset(40f,700f); c.showText("READER PAGE ${index+1}"); c.endText() }
            }; pdf.save(file)
        }
        runBlocking { repo.registerPdf(file,title) }
        compose.waitUntil(15000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("第 1 页").assertIsDisplayed().performTouchInput { swipeUp() }
        compose.onNodeWithText("跳页").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("3")
        compose.onNodeWithText("跳转").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("第 3 页").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("第 3 页").assertIsDisplayed()
    }
}

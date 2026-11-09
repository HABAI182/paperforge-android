package cn.paperforge.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cn.paperforge.app.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationExpansionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cardOriginExpansionKeepsOneTreeAndCompletes() {
        lateinit var controller: NavigationExpansion
        compose.activity.setContent {
            PaperForgeTheme {
                val scope = rememberCoroutineScope()
                val expansion = remember { NavigationExpansion(scope) }.also { controller = it }
                var selected by remember { mutableStateOf(false) }
                val route = if (selected) "detail" else "root"
                SideEffect { expansion.observeRoute(route) }
                NavigationExpansionHost(expansion) {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = if (selected) Color(0xFF17385C) else Color(0xFF3A2034),
                    ) {
                        if (selected)
                            Column(Modifier.fillMaxSize().padding(24.dp)) {
                                Text("目的页面", style = MaterialTheme.typography.headlineLarge)
                                Text("保留同一份业务界面")
                                TextButton(onClick = returnAction { selected = false }) {
                                    Text("返回栏目")
                                }
                            }
                        else
                            Column(Modifier.fillMaxSize().padding(24.dp)) {
                                Text(
                                    "栏目",
                                    Modifier.testTag("root-title"),
                                    style = MaterialTheme.typography.headlineLarge,
                                )
                                Spacer(Modifier.height(80.dp))
                                SectionCardGrid(
                                    listOf(
                                        WorkspaceSection(
                                            "first",
                                            "展开卡片",
                                            "从卡片原位展开",
                                            Icons.Default.Description,
                                        ),
                                        WorkspaceSection(
                                            "second",
                                            "另一个栏目",
                                            "保持对称布局",
                                            Icons.Default.Description,
                                        ),
                                    )
                                ) {
                                    selected = true
                                }
                            }
                    }
                }
            }
        }
        compose.waitForIdle()
        val rootBounds = compose.onNodeWithTag("root-title").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("展开卡片").performClick()
        compose.waitUntil(4000) { controller.active }
        compose.mainClock.advanceTimeBy(96)
        assertTrue("no intermediate container state", controller.progress.value in 0.01f..0.99f)
        compose.onAllNodesWithText("目的页面").assertCountEquals(1)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(
            "outgoing frame mutated with the destination",
            Color(0xFF3A2034).toArgb(),
            bitmap.getPixel(bitmap.width - 4, 4),
        )
        File(compose.activity.filesDir, "motion-v7/card-expansion-mid.png")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse("outgoing frame retained", controller.active)
        compose.onNodeWithText("目的页面").assertIsDisplayed()
        compose.onAllNodesWithText("展开卡片").assertCountEquals(0)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("返回栏目").performClick()
        compose.waitUntil(4000) { controller.active && controller.exiting }
        compose.mainClock.advanceTimeBy(96)
        assertTrue("no reverse state", controller.progress.value in 0.01f..0.99f)
        compose.onAllNodesWithText("展开卡片").assertCountEquals(1)
        val during = compose.onNodeWithTag("root-title").fetchSemanticsNode().boundsInRoot
        assertEquals(rootBounds, during)
        val closing = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(
            "returning parent moved or changed colour",
            Color(0xFF3A2034).toArgb(),
            closing.getPixel(closing.width - 4, 4),
        )
        File(compose.activity.filesDir, "motion-v7/card-collapse-mid.png").outputStream().use {
            closing.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse(controller.active)
        val after = compose.onNodeWithTag("root-title").fetchSemanticsNode().boundsInRoot
        assertEquals(rootBounds, after)
        compose.onAllNodesWithText("目的页面").assertCountEquals(0)
    }
}

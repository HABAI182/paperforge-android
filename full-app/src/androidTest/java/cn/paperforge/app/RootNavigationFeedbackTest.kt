package cn.paperforge.app

import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.paperforge.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RootNavigationFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cardReturnRetainsViewportAndScanEntryExpands() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        lateinit var navigation: NavigationExpansion
        compose.runOnIdle {
            vm.screen.value = "home"
            vm.homeTab.value = 2
            vm.workspaceGroup.value = ""
            vm.workspaceQuery.value = ""
        }
        compose.activity.setContent {
            PaperForgeTheme {
                val scope = rememberCoroutineScope()
                val controller = remember { NavigationExpansion(scope) }.also { navigation = it }
                val screen by vm.screen.collectAsState()
                DocumentWorkspace(vm, screen, controller) {
                    if (screen == "home") HomeScreen(vm, {}, {}, {}, {})
                    else
                        TextButton(onClick = returnAction { vm.screen.value = "home" }) {
                            Text("返回扫描入口")
                        }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("workspace").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val parent = compose.onNodeWithText("连接与智能").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("workspace").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("在工具右侧菜单中固定常用功能。").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "excessive bottom scroll padding",
            list.bottom - footer.bottom < 40 * compose.density.density,
        )
        compose.onNodeWithText("连接与智能").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("全部功能").performClick()
        compose.waitForIdle()
        assertEquals(parent, compose.onNodeWithText("连接与智能").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { vm.homeTab.value = 0 }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("扫描", useUnmergedTree = true).performClick()
        compose.waitUntil(4000) { navigation.active }
        compose.mainClock.advanceTimeBy(96)
        assertEquals("capture", vm.screen.value)
        assertTrue(
            "scan origin not recorded",
            navigation.source.width > 0 && navigation.progress.value < 1f,
        )
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("返回扫描入口").performClick()
        compose.waitForIdle()
        assertEquals("home", vm.screen.value)
        assertFalse(navigation.active)
    }
}

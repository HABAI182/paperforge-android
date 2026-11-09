package cn.paperforge.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RefinedMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationKeepsContentDuringInterruptedSpringAndProjectFollowsHelp() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            vm.screen.value = "home"
            vm.homeTab.value = 0
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                vm.settingsSection.value = "appearance"
                vm.screen.value = "settings"
            }
            compose.mainClock.advanceTimeBy(48)
            // One incoming tree stays opaque; there is no outgoing snapshot to overlap it.
            compose.onNodeWithText("查找全部设置").assertExists()
            compose.runOnIdle {
                vm.screen.value = "home"
                vm.homeTab.value = 2
                vm.workspaceGroup.value = ""
            }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText("查找全部设置").assertDoesNotExist()
            compose.runOnIdle { vm.screen.value = "settings" }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText("查找全部设置").assertExists()
            compose.mainClock.advanceTimeBy(320)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.runOnIdle {
            vm.screen.value = "home"
            vm.homeTab.value = 3
        }
        compose.waitForIdle()
        compose.onNodeWithText("项目主页", substring = false).performScrollTo()
        val help =
            compose.onNodeWithText("使用说明", substring = false).fetchSemanticsNode().boundsInRoot
        val project =
            compose.onNodeWithText("项目主页", substring = false).fetchSemanticsNode().boundsInRoot
        assertTrue(project.top > help.bottom)
        compose.onNodeWithText("GitHub 项目", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            vm.screen.value = "home"
            vm.homeTab.value = 0
        }
    }
}

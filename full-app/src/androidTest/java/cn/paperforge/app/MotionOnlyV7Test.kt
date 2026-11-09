package cn.paperforge.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real root pages; the before images are captured from 141f61e before the pager is installed. */
class MotionOnlyV7Test {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm
        get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private val folder
        get() = File(vm.repo.context.filesDir, "motion-v7").apply { mkdirs() }

    private fun fixture(reduced: Boolean = false) {
        compose.runOnIdle {
            vm.screen.value = "home"
            vm.homeTab.value = 0
            vm.documentTab.value = 0
            vm.query.value = ""
            vm.workspaceGroup.value = ""
            vm.workspaceQuery.value = ""
        }
        compose.activity.setContent {
            PaperForgeTheme {
                CompositionLocalProvider(LocalPaperMotion provides PaperMotion(reduced)) {
                    DocumentWorkspace(vm, "home") { HomeScreen(vm, {}, {}, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun staticRootBaseline() {
        fixture()
        val stage = InstrumentationRegistry.getArguments().getString("motionStage") ?: "before"
        for (tab in listOf(0, 2, 3)) {
            compose.runOnIdle { vm.homeTab.value = tab }
            compose.waitForIdle()
            shot("$stage-root-$tab.png")
        }
    }

    @Test
    fun heldDragCancelCommitReverseAndNoOp() {
        fixture()
        val root = compose.onNodeWithTag("root-pager")
        val width = root.fetchSemanticsNode().boundsInRoot.width
        for (distance in listOf(30f, 60f, 100f, 160f)) {
            root.performTouchInput {
                // Use document content, not the folder carousel that owns horizontal input.
                down(Offset(width * .65f, height * .8f))
                moveBy(Offset(-distance * .5f, 0f), 80)
                moveBy(Offset(-distance * .5f, 0f), 80)
            }
            compose.waitForIdle()
            assertEquals("VM changes while finger is down", 0, vm.homeTab.value)
            val first = compose.onNodeWithTag("root-page-0").fetchSemanticsNode()
            val next = compose.onNodeWithTag("root-page-1").fetchSemanticsNode()
            assertTrue(
                "page did not follow held finger: distance=$distance x=${first.positionInRoot.x}",
                first.positionInRoot.x < -2f,
            )
            assertEquals(
                "real pages must meet without overlap",
                first.positionInRoot.x + first.size.width,
                next.positionInRoot.x,
                1.5f,
            )
            shot("held-${distance.toInt()}px.png")
            root.performTouchInput {
                moveBy(Offset(distance, 0f), 220)
                advanceEventTime(200)
                up()
            }
            compose.waitForIdle()
            assertEquals(0, vm.homeTab.value)
        }
        root.performTouchInput {
            down(Offset(width * .65f, height * .8f))
            repeat(4) { moveBy(Offset(-width * .0875f, 0f), 75) }
            advanceEventTime(150)
            up()
        }
        compose.waitForIdle()
        assertEquals(
            "snapped document x=${compose.onNodeWithTag("root-page-0").fetchSemanticsNode().positionInRoot.x}",
            2,
            vm.homeTab.value,
        )
        compose.waitForIdle()
        shot("settled-workspace.png")
        compose.onNodeWithTag("workspace").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("workspace").fetchSemanticsNode().config.toString()
        repeat(20) { compose.onNodeWithTag("root-nav-1").performClick() }
        compose.waitForIdle()
        assertEquals(
            "same destination reset list state",
            before,
            compose.onNodeWithTag("workspace").fetchSemanticsNode().config.toString(),
        )
        // Latest bottom navigation intent must cancel a still-held gesture/settle.
        root.performTouchInput {
            down(Offset(width * .65f, height * .8f))
            moveBy(Offset(-width * .3f, 0f), 200)
        }
        compose.onNodeWithTag("root-nav-0").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        root.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(0, vm.homeTab.value)
        shot("latest-click-documents.png")
        // Collection changes stay in the same real document page without root handoff.
        compose.onAllNodesWithText("全部").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(1, vm.homeTab.value)
        assertEquals(
            0f,
            compose.onNodeWithTag("root-page-0").fetchSemanticsNode().positionInRoot.x,
            1f,
        )
    }

    @Test
    fun reducedMotionAndChildInputKeepNavigationAccessible() {
        fixture(reduced = true)
        compose.onNodeWithTag("root-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, vm.homeTab.value)
        compose.onNodeWithTag("root-nav-1").performClick()
        compose.waitForIdle()
        assertEquals(2, vm.homeTab.value)
        compose.onNodeWithTag("root-nav-0").performClick()
        compose.waitForIdle()
        fixture() // Child ownership is checked with root motion enabled.
        val input =
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("document-list")))
        input.performTextInput("x² - 1")
        input.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, vm.homeTab.value)
        compose.onNodeWithText("x² - 1").assertExists()
    }
}

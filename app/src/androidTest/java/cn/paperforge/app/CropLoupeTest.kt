package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.Quad
import cn.paperforge.app.ui.CropDialog
import cn.paperforge.app.ui.LoupeSpot
import cn.paperforge.app.ui.PaperForgeTheme
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crop loupe: corners move with the finger instead of jumping under it, the loupe is placed clear of the finger
 * and inside the view, and the nudge keys move the selected corner in 0.1% steps. Saves screenshots of the loupe
 * mid-drag to files/phone-out/crop-loupe.
 */
@RunWith(AndroidJUnit4::class)
class CropLoupeTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val out = File(context.filesDir, "phone-out/crop-loupe").apply { mkdirs() }

    /** A 3000 x 4000 photo: a printed sheet on a desk, its corners at .2/.8 across and .2/.85 down. */
    private fun photo(): File {
        val file = File(context.cacheDir, "crop-loupe.jpg")
        val bitmap = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(96, 78, 62))
        val paper = Paint().apply { color = Color.rgb(244, 242, 236) }
        canvas.drawRect(600f, 800f, 2400f, 3400f, paper)
        val ink = Paint().apply { color = Color.rgb(30, 30, 34); textSize = 64f; isAntiAlias = true }
        var y = 1000f
        while (y < 3300f) {
            canvas.drawText("第 ${((y - 1000) / 110).toInt() + 1} 行：纸边对齐 Paper edge alignment 0123", 700f, y, ink)
            y += 110f
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return file
    }

    private fun shot(name: String) {
        // Animations run on the test clock: let the loupe fade in and glide into place before the picture.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        Thread.sleep(200)
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(out, name).outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screen.recycle()
    }

    @Test
    fun cornersFollowTheFingerAndTheLoupeStaysClearOfIt() {
        val corners = ".2,.2;.8,.2;.8,.85;.2,.85"
        val page = PageRow(id = "crop-loupe", documentId = "t", position = 0, original = photo().path, corners = corners, filter = "ORIGINAL")
        var draft = Quad.decode(corners)
        compose.setContent {
            PaperForgeTheme {
                CropDialog(page, onDismiss = {}, onSave = { _, _ -> }, embedded = true, onDraft = { q, _ -> draft = q })
            }
        }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("crop-canvas").fetchSemanticsNodes().isNotEmpty() }
        val canvas = compose.onNodeWithTag("crop-canvas")
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val w = bounds.width
        val h = bounds.height
        val dp = compose.density.density
        val loupeRadius = 56f * dp
        val fingerRadius = 40f * dp

        fun loupeClearOf(fingerInCanvas: Offset) {
            val node = compose.onNodeWithTag("crop-loupe", useUnmergedTree = true).fetchSemanticsNode()
            val spot = node.config[LoupeSpot]
            val view = node.boundsInRoot
            val finger = Offset(bounds.left + fingerInCanvas.x - view.left, bounds.top + fingerInCanvas.y - view.top)
            val radius = minOf(loupeRadius, minOf(view.width, view.height) * .26f)
            assertTrue("loupe $spot off the fingertip $finger", hypot(spot.x - finger.x, spot.y - finger.y) >= radius + fingerRadius - 1f)
            assertTrue("loupe $spot inside the view ${view.size}", spot.x - radius >= 0f && spot.x + radius <= view.width && spot.y - radius >= 0f && spot.y + radius <= view.height)
        }

        // Top-left corner, taken hold of 18 dp right of and 22 dp below it, then moved 30 dp right and 40 dp down.
        val corner = Offset(.2f * w, .2f * h)
        val grab = corner + Offset(18f * dp, 22f * dp)
        val move = Offset(30f * dp, 40f * dp)
        canvas.performTouchInput {
            down(grab)
            moveTo(grab + move * .5f)
            moveTo(grab + move)
        }
        compose.waitForIdle()
        loupeClearOf(grab + move)
        shot("drag-top-left.png")
        canvas.performTouchInput { up() }
        compose.waitForIdle()
        val moved = draft.points[0]
        assertEquals("corner moved with the finger, not under it", .2f + move.x / w, moved.x, .004f)
        assertEquals(.2f + move.y / h, moved.y, .004f)

        // The nudge keys: five steps right, 0.1% of the long side each.
        repeat(5) { compose.onNodeWithTag("crop-nudge-right").performClick() }
        compose.waitForIdle()
        val step = 4000f / 1000f / 3000f
        assertEquals(moved.x + 5 * step, draft.points[0].x, .0005f)
        assertEquals(moved.y, draft.points[0].y, .0001f)
        shot("nudge-top-left.png")

        // Top-right corner: no room above, so the loupe goes beside the finger.
        val right = Offset(.8f * w, .2f * h)
        val grabRight = right + Offset(-16f * dp, 20f * dp)
        canvas.performTouchInput {
            down(grabRight)
            moveTo(grabRight + Offset(-20f * dp, -24f * dp))
        }
        compose.waitForIdle()
        loupeClearOf(grabRight + Offset(-20f * dp, -24f * dp))
        shot("drag-top-right.png")
        canvas.performTouchInput { up() }

        // Bottom-left corner, held still: the loupe goes above the finger.
        val bottom = Offset(.2f * w, .85f * h) + Offset(14f * dp, 16f * dp)
        canvas.performTouchInput { down(bottom) }
        compose.waitForIdle()
        loupeClearOf(bottom)
        shot("hold-bottom-left.png")
        canvas.performTouchInput { up() }

        // The bottom edge, dragged up by its middle: the whole edge moves.
        val before = draft.points.map { it.y }
        val edge = Offset(.5f * w, .85f * h)
        canvas.performTouchInput {
            down(edge)
            moveTo(edge + Offset(0f, -30f * dp))
        }
        compose.waitForIdle()
        loupeClearOf(edge + Offset(0f, -30f * dp))
        shot("drag-bottom-edge.png")
        canvas.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue("bottom edge moved up", draft.points[2].y < before[2] - .02f && draft.points[3].y < before[3] - .02f)
    }
}

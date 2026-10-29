package cn.paperforge.app.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.flowOf

/** Physical window coordinates; a zero-width separating crease is still a boundary. */
internal data class WorkspaceFold(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val vertical: Boolean,
) {
    data class Pane(val x: Int, val y: Int, val width: Int, val height: Int)

    fun panes(width: Int, height: Int, originX: Int, originY: Int): Pair<Pane, Pane>? {
        val l = (left - originX).coerceIn(0, width)
        val r = (right - originX).coerceIn(0, width)
        val t = (top - originY).coerceIn(0, height)
        val b = (bottom - originY).coerceIn(0, height)
        return if (vertical && top <= originY && bottom >= originY + height && l > 0 && r < width)
            Pane(0, 0, l, height) to Pane(r, 0, width - r, height)
        else if (!vertical && left <= originX && right >= originX + width && t > 0 && b < height)
            Pane(0, 0, width, t) to Pane(0, b, width, height - b)
        else null
    }
}

@Composable
internal fun rememberWorkspaceFold(): WorkspaceFold? {
    val activity = LocalActivity.current
    val changes =
        remember(activity) {
            activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) }
                ?: flowOf(WindowLayoutInfo(emptyList()))
        }
    val layout by changes.collectAsStateWithLifecycle(WindowLayoutInfo(emptyList()))
    return layout.displayFeatures
        .filterIsInstance<FoldingFeature>()
        .firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
        ?.let {
            val rect = it.bounds
            WorkspaceFold(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                it.orientation == FoldingFeature.Orientation.VERTICAL,
            )
        }
}

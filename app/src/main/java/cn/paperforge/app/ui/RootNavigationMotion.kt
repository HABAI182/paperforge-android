package cn.paperforge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** One travelling indicator, using the existing icon centres and unchanged bar/item layout. */
@Composable
internal fun RootNavigationMotion(
    gate: RootSwipeGate,
    active: Int,
    content: @Composable RowScope.() -> Unit,
) {
    val indicator = remember { Animatable(active.toFloat()) }
    val reduced = LocalPaperMotion.current.reduced
    var origin by remember { mutableStateOf(Offset.Zero) }
    val color = MaterialTheme.colorScheme.primaryContainer
    LaunchedEffect(gate.moving, gate.pagePosition, active, reduced) {
        if (reduced) indicator.snapTo(active.toFloat())
        else if (gate.moving) indicator.snapTo(gate.pagePosition)
        else indicator.animateTo(active.toFloat(), spring(dampingRatio = 1f, stiffness = 300f))
    }
    Box(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .drawBehind {
                val position = indicator.value.coerceIn(0f, 2f)
                val left = floor(position).toInt()
                val right = ceil(position).toInt()
                val a = gate.iconCenters[left] ?: return@drawBehind
                val b = gate.iconCenters[right] ?: a
                val center = a + (b - a) * (position - left) - origin
                // Continuous zero-slope contraction and recovery; never teleport the selection.
                val phase = sin(PI * (position - floor(position))).toFloat().pow(2)
                val width = 64.dp.toPx() * (1f - .18f * phase)
                val height = 32.dp.toPx() * (1f - .12f * phase)
                drawRoundRect(
                    color,
                    center - Offset(width / 2, height / 2),
                    Size(width, height),
                    CornerRadius(height / 2),
                )
            }
    ) {
        NavigationBar(containerColor = Color.Transparent, content = content)
    }
}

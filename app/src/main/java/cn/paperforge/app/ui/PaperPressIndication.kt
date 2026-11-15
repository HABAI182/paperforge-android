package cn.paperforge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Default press feedback for everything that uses the theme's indication: the usual ripple plus a short, springy
 * shrink while pressed that settles back with a little overshoot on release. The shrink is at most 8 dp across (4 %
 * for small targets), so wide rows barely move. Under reduced motion only the ripple remains.
 */
internal class PaperPressIndication(private val ripple: IndicationNodeFactory, private val reduced: Boolean) :
    IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource, ripple.create(interactionSource), reduced)

    override fun equals(other: Any?) = other is PaperPressIndication && other.ripple == ripple && other.reduced == reduced

    override fun hashCode() = ripple.hashCode() * 31 + reduced.hashCode()
}

private class PressNode(
    private val source: InteractionSource,
    ripple: DelegatableNode,
    private val reduced: Boolean,
) : DelegatingNode(), DrawModifierNode {
    private val pressed = Animatable(0f)

    init {
        delegate(ripple)
    }

    override fun onAttach() {
        if (reduced) return
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press ->
                        launch { pressed.animateTo(1f, spring(dampingRatio = .9f, stiffness = 900f)) }
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        launch { pressed.animateTo(0f, spring(dampingRatio = .55f, stiffness = 500f)) }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        if (p == 0f) {
            drawContent()
            return
        }
        val longest = maxOf(size.width, size.height).coerceAtLeast(1f)
        val depth = minOf(.04f, 8.dp.toPx() / longest)
        scale(1f - depth * p) { this@draw.drawContent() }
    }
}

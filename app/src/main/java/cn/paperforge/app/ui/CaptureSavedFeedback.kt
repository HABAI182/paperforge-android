package cn.paperforge.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Acknowledges a saved photograph, independently of the slower page enhancement queue. */
@Composable
internal fun CaptureSavedFeedback(serial: Int, message: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(serial) {
        if (serial == 0) return@LaunchedEffect
        visible = true
        delay(1400)
        visible = false
    }
    AnimatedVisibility(
        visible,
        modifier,
        enter =
            fadeIn(tween(90)) +
                scaleIn(spring(dampingRatio = 1f, stiffness = 700f), initialScale = .94f),
        exit = fadeOut(tween(180)),
    ) {
        Surface(
            modifier = Modifier.padding(bottom = 8.dp),
            color = Color(0xE619493B),
            contentColor = Color.White,
            shape = RoundedCornerShape(50),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp))
                Text(message, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

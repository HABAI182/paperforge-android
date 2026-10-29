package cn.paperforge.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import cn.paperforge.app.R

/** The supplied artwork is retained intact; the viewport removes its presentation margin. */
@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(percent = 24))) {
        Image(
            painterResource(R.drawable.brand_logo_source),
            "PaperForge 标志",
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.388f
                scaleY = 1.388f
            },
        )
    }
}

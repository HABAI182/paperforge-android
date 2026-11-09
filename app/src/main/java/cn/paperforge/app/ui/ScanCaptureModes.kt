package cn.paperforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import kotlinx.coroutines.launch

internal data class CaptureMode(
    val id: String,
    val title: String,
    val filter: String,
    val aspect: Float,
    val detail: String,
)

internal val captureModes =
    listOf(
        CaptureMode("document", "扫描文档", "WORKBOOK", 0f, "自动裁边 · 练习册增强"),
        CaptureMode("color", "原色", "AUTO", 0f, "保留彩色内容与印章"),
        CaptureMode("clean", "去透页", "CLEAN", 0f, "增强纸白 · 抑制背面透字"),
        CaptureMode("card", "证件", "ORIGINAL", 85.6f / 54f, "证件比例 · 拍摄后可调整裁边"),
    )

/** Snap-to-centre mode rail. Changing a mode changes the durable capture recipe, not just text. */
@Composable
internal fun ScanCaptureModes(enabled: Boolean) {
    val initial = remember {
        val preferred = UserSettings.string("captureMode", "document")
        val filter = UserSettings[SettingKeys.Filter]
        val aspect = UserSettings[SettingKeys.ScanAspect]
        captureModes
            .indexOfFirst {
                it.id == preferred &&
                    ((it.filter == filter && it.aspect == aspect) || it.id == "card")
            }
            .takeIf { it >= 0 }
            ?: captureModes
                .indexOfFirst { it.filter == filter && it.aspect == aspect }
                .coerceAtLeast(0)
    }
    val pager = rememberPagerState(initialPage = initial, pageCount = { captureModes.size })
    val scope = rememberCoroutineScope()
    val mode = captureModes[pager.settledPage]
    LaunchedEffect(mode) {
        UserSettings.set("captureMode", mode.id)
        UserSettings.set(SettingKeys.Filter, mode.filter)
        UserSettings.set(SettingKeys.ScanAspect, mode.aspect)
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            HorizontalPager(
                pager,
                pageSize = PageSize.Fixed(112.dp),
                contentPadding =
                    PaddingValues(horizontal = ((maxWidth - 112.dp) / 2).coerceAtLeast(0.dp)),
                userScrollEnabled = enabled,
            ) { index ->
                val selected = index == pager.currentPage
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled) { scope.launch { pager.animateScrollToPage(index) } }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        captureModes[index].title,
                        color = if (selected) Color(0xFF86E5B8) else Color.White.copy(alpha = .65f),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.width(16.dp)
                            .height(2.dp)
                            .background(
                                if (selected) Color(0xFF86E5B8) else Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(1.dp),
                            )
                    )
                }
            }
        }
    }
}

@Composable
internal fun CaptureBatchToggle(enabled: Boolean) {
    var single by remember { mutableStateOf(UserSettings[SettingKeys.ReviewAfterCapture]) }
    Surface(
        color = Color.Black.copy(alpha = .5f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.padding(3.dp)) {
            listOf(true to "单页", false to "多页").forEach { (value, label) ->
                Surface(
                    modifier = Modifier.semantics { selected = single == value },
                    onClick = {
                        single = value
                        UserSettings.set(SettingKeys.ReviewAfterCapture, value)
                    },
                    enabled = enabled,
                    color =
                        if (single == value) Color.White.copy(alpha = .24f) else Color.Transparent,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                ) {
                    Text(
                        label,
                        Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

package cn.paperforge.app.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.paperforge.app.pdf.LibraryFont
import cn.paperforge.app.pdf.NativeFontChoice
import cn.paperforge.app.pdf.NativeFontLibrary
import java.io.File

/** One row of the picker: a library font, an imported file, or the original font. */
internal data class PickerFont(
    val id: String,
    val sha256: String,
    val nameZh: String,
    val nameEn: String,
    val category: String,
    val coverage: String,
    val typeface: () -> Typeface?,
)

internal object NativeFontPrefs {
    private const val KEY = "native-recent-fonts"

    fun recent(context: Context): List<String> =
        context.getSharedPreferences("native-edit", Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }

    fun use(context: Context, id: String) {
        val next = (listOf(id) + recent(context).filter { it != id }).take(6)
        context.getSharedPreferences("native-edit", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, next.joinToString("\n"))
            .apply()
    }
}

private val typefaces = mutableMapOf<String, Typeface?>()

internal fun pickerFonts(context: Context, imported: List<NativeFontChoice>): List<PickerFont> {
    fun asset(font: LibraryFont): () -> Typeface? = {
        synchronized(typefaces) {
            typefaces.getOrPut(font.asset) {
                runCatching { Typeface.createFromAsset(context.assets, font.asset) }.getOrNull()
            }
        }
    }
    val library =
        NativeFontLibrary.available(context).map {
            PickerFont(it.id, "", it.nameZh, it.nameEn, it.category.label, it.coverage, asset(it))
        }
    val files =
        imported
            .filter { it.id != "ORIGINAL" && it.id != "CJK" && it.path.isNotEmpty() }
            .map { choice ->
                PickerFont(choice.id, choice.sha256, choice.label, "导入的字体", "已导入", "取决于字体文件") {
                    synchronized(typefaces) {
                        typefaces.getOrPut(choice.path) {
                            runCatching { Typeface.createFromFile(File(choice.path)) }.getOrNull()
                        }
                    }
                }
            }
    return library + files
}

/** Characters of [text] the typeface cannot draw (whitespace ignored). */
internal fun lacking(typeface: Typeface?, text: String): List<String> {
    if (typeface == null) return emptyList()
    val paint = Paint().apply { this.typeface = typeface }
    return text.codePoints().toArray().distinct()
        .filterNot { Character.isWhitespace(it) }
        .map { String(Character.toChars(it)) }
        .filterNot { paint.hasGlyph(it) }
}

/**
 * Font chooser, docked under the page instead of covering it: the selected text stays in view and every tap applies
 * the font at once ([onPick]), so the page shows the result while the user keeps browsing. [onDone] keeps it,
 * [onCancel] puts back the fonts the selection had when the panel opened. Search, categories, recently used, a live
 * sample in each font, and a note for characters a font lacks (those keep the original font or a matching one).
 * [originalMissing] lists characters the PDF's own font lacks (null: not applicable).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeFontPicker(
    sample: String,
    imported: List<NativeFontChoice>,
    current: String?,
    originalName: String,
    originalMissing: List<String>?,
    onPick: (id: String, sha256: String) -> Unit,
    onImport: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val fonts = remember(imported) { pickerFonts(context, imported) }
    val recent = remember { NativeFontPrefs.recent(context) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val preview = sample.trim().ifEmpty { "永字八法 Typography 123" }.take(24)
    val categories = fonts.map { it.category }.distinct()
    val shown =
        fonts.filter { f ->
            (category == null || f.category == category) &&
                (query.isBlank() ||
                    listOf(f.nameZh, f.nameEn, f.category, f.coverage).any { it.contains(query.trim(), true) })
        }
    // About two fifths of the screen at most: the page above stays readable.
    val maxList = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .26f).dp.coerceAtLeast(140.dp)
    Surface(tonalElevation = 4.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().testTag("font-dock")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字体", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onImport, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("font-manage")) {
                    Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载或导入")
                }
                TextButton(onClick = onCancel, modifier = Modifier.testTag("font-cancel")) { Text("取消") }
                FilledTonalButton(onClick = onDone, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp).testTag("font-done")) { Text("完成") }
            }
            val alternatives = remember(originalName) { runCatching { cn.paperforge.app.pdf.FontStore.alternatives(context, originalName) }.getOrDefault(emptyList()) }
            if (alternatives.isNotEmpty())
                Text(
                    "原文字体“${originalName.substringAfter('+')}”是商业字体；可下载开源替代：" + alternatives.take(3).joinToString("、") { it.nameZh },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("font-search"),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("搜索：宋体、Times、楷…", maxLines = 1) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(category == null, { category = null }, { Text("全部") }) }
                items(categories) { c -> FilterChip(category == c, { category = if (category == c) null else c }, { Text(c) }) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxList).testTag("font-list"), contentPadding = PaddingValues(vertical = 4.dp)) {
                if (query.isBlank() && category == null) {
                    item {
                        FontRow(
                            "ORIGINAL",
                            "沿用原字体",
                            originalName.substringAfter('+').ifEmpty { "PDF 内嵌字体" },
                            when {
                                originalMissing == null -> "保留 PDF 中的字形"
                                originalMissing.isEmpty() -> "所选文字可用原字体显示"
                                else -> "原字体缺「${originalMissing.take(6).joinToString("")}」，这几个字用相近字体补齐"
                            },
                            false,
                            null,
                            preview,
                            current == "ORIGINAL",
                        ) { onPick("ORIGINAL", "") }
                    }
                    val recents = recent.mapNotNull { id -> fonts.firstOrNull { it.id == id } }
                    if (recents.isNotEmpty()) {
                        item { Text("最近使用", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                        items(recents, key = { "recent-" + it.id }) { f -> PickerRow(f, preview, current, onPick) }
                        item { Text("全部字体", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                    }
                }
                items(shown, key = { it.id }) { f -> PickerRow(f, preview, current, onPick) }
                if (shown.isEmpty()) item { Text("没有匹配的字体，可点“下载或导入”", Modifier.padding(12.dp)) }
            }
        }
    }
}

@Composable
private fun PickerRow(font: PickerFont, preview: String, current: String?, onPick: (String, String) -> Unit) {
    val typeface = remember(font.id) { font.typeface() }
    val missing = remember(font.id, preview) { lacking(typeface, preview) }
    FontRow(
        font.id,
        font.nameZh,
        font.nameEn,
        if (missing.isEmpty()) "${font.category} · ${font.coverage}"
        else "缺「${missing.take(6).joinToString("")}」，这几个字保留原字体 · ${font.coverage}",
        false,
        typeface,
        preview,
        current == font.id,
    ) { onPick(font.id, font.sha256) }
}

@Composable
private fun FontRow(
    id: String,
    title: String,
    subtitle: String,
    detail: String,
    warn: Boolean,
    typeface: Typeface?,
    preview: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).testTag("font-$id"),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.widthIn(max = 140.dp), overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
                Text(
                    preview,
                    fontFamily = typeface?.let { FontFamily(it) },
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "$subtitle · $detail",
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

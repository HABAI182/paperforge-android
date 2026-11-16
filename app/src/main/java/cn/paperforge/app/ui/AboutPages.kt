package cn.paperforge.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.paperforge.app.R
import org.json.JSONObject

/** One component in assets/licenses/index.json. */
private class LicenseEntry(val name: String, val holder: String, val license: String, val files: List<String>, val url: String?)

private fun loadLicenses(context: android.content.Context): List<Pair<String, List<LicenseEntry>>> =
    runCatching {
            val json = JSONObject(context.assets.open("licenses/index.json").bufferedReader().use { it.readText() })
            val groups = json.getJSONArray("groups")
            (0 until groups.length()).map { g ->
                val group = groups.getJSONObject(g)
                val items = group.getJSONArray("items")
                group.getString("id") to
                    (0 until items.length()).map { i ->
                        val it = items.getJSONObject(i)
                        val extra = it.optJSONArray("extra")
                        LicenseEntry(
                            it.getString("name"),
                            it.optString("holder"),
                            it.getString("license"),
                            listOfNotNull(it.optString("file").takeIf { f -> f.isNotBlank() }) +
                                (0 until (extra?.length() ?: 0)).map { k -> extra!!.getString(k) },
                            it.optString("url").takeIf { u -> u.isNotBlank() },
                        )
                    }
            }
        }
        .getOrDefault(emptyList())

/** A full-screen page with a back arrow, as used by About's sub-pages. */
@Composable
private fun AboutSubPage(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("about-page-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                content()
            }
        }
    }
}

/** The legally required attribution: every bundled component with its license, and its full license text on tap. */
@Composable
fun OpenSourceLicensesPage(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val groups = remember { loadLicenses(context) }
    var open by remember { mutableStateOf<String?>(null) }
    var fullNotices by remember { mutableStateOf(false) }
    val groupTitle = mapOf(
        "libraries" to stringResource(R.string.licenses_group_libraries),
        "fonts" to stringResource(R.string.licenses_group_fonts),
        "models" to stringResource(R.string.licenses_group_models),
        "data" to stringResource(R.string.licenses_group_data),
    )
    AboutSubPage(stringResource(R.string.about_licenses), onDismiss) {
        LazyColumn(Modifier.fillMaxSize().testTag("about-licenses"), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            groups.forEach { (id, items) ->
                item {
                    Text(
                        groupTitle[id] ?: id,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                items(items, key = { "$id/${it.name}" }) { entry ->
                    val key = "$id/${entry.name}"
                    val expanded = open == key
                    OutlinedCard(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.clickable { open = if (expanded) null else key }.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.licenses_license, entry.license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (entry.holder.isNotBlank())
                                        Text(stringResource(R.string.licenses_holder, entry.holder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (entry.files.isNotEmpty() || entry.url != null)
                                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                            }
                            if (expanded) {
                                entry.url?.let { url ->
                                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, contentPadding = PaddingValues(0.dp)) {
                                        Text(stringResource(R.string.licenses_open_project))
                                    }
                                }
                                entry.files.forEach { file ->
                                    val text = remember(file) {
                                        runCatching { context.assets.open(file).bufferedReader().use { it.readText() } }.getOrDefault("")
                                            .replace(Regex("<[^>]+>"), "")
                                    }
                                    if (text.isNotBlank())
                                        SelectionContainer {
                                            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(top = 8.dp))
                                        }
                                }
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { fullNotices = !fullNotices }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.licenses_full_notices)) }
                if (fullNotices)
                    SelectionContainer {
                        Text(
                            remember { runCatching { context.assets.open("LICENSE_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("") },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
        }
    }
}

/**
 * Optional thanks, kept apart from the legal attribution. Names are shown exactly as given (never translated); only the
 * headings follow the app's language. Listing implies no partnership, endorsement or authorship.
 */
@Composable
fun AcknowledgmentsPage(onDismiss: () -> Unit) {
    val ai = listOf("ChatGPT", "DeepSeek", "Claude", "Gemini")
    val community = listOf(
        "Oxygen Sleeping", "哦就是什么", "我本纯真呢", "斯帕克勒8", "挺山", "Stefzhao", "拙劣灬小丑", "bili_44196418074", "NKAN_", "xgybddchh",
    )
    AboutSubPage(stringResource(R.string.about_acknowledgments), onDismiss) {
        LazyColumn(Modifier.fillMaxSize().testTag("about-acknowledgments"), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.acknowledgments_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { NameSection(stringResource(R.string.acknowledgments_ai), ai) }
            item { NameSection(stringResource(R.string.acknowledgments_community), community) }
            item { Text(stringResource(R.string.acknowledgments_open_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameSection(title: String, names: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { name ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("ack-name"))
                }
            }
        }
    }
}

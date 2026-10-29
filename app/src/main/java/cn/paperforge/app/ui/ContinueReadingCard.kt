package cn.paperforge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.paperforge.app.*
import cn.paperforge.app.R
import cn.paperforge.app.data.DocumentRow

@Composable
fun ContinueReadingCard(vm: MainViewModel, documents: List<DocumentRow>, busy: Boolean) {
    val context = LocalContext.current
    val preferences = remember(context) { ReaderPreferences(context) }
    val doc =
        documents
            .filter { it.kind == "pdf" && it.lastOpened > 0 && it.trashedAt == null && it.listed }
            .sortedByDescending { it.lastOpened }
            .firstOrNull { preferences.hasPosition(it.id) } ?: return
    val position = preferences.load(doc.id)
    Card(
        onClick = { vm.open(doc) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("continue-reading"),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DocumentCover(doc, vm.repo, Modifier.size(40.dp, 56.dp), 180)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.continue_reading_page, position.page + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    doc.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
        }
    }
}

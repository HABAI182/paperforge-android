package cn.paperforge.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.Repository
import cn.paperforge.app.imaging.ScanProcessor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val coverWorkers = Semaphore(2)

/** Derived, disposable first-page previews. An edit changes modified and invalidates the key. */
@Composable
fun DocumentCover(
    document: DocumentRow,
    repository: Repository,
    modifier: Modifier = Modifier.size(width = 54.dp, height = 72.dp),
    previewEdge: Int = 260,
) {
    val cache = File(LocalContext.current.cacheDir, "document-covers")
    val image by
        produceState<Bitmap?>(null, document.id, document.modified, previewEdge) {
            value =
                withContext(Dispatchers.IO) {
                    coverWorkers.withPermit {
                        runCatching {
                                cache.mkdirs()
                                val target =
                                    File(
                                        cache,
                                        "${document.id}-${document.modified}-$previewEdge.jpg",
                                    )
                                BitmapFactory.decodeFile(target.path)
                                    ?: run {
                                        val bitmap =
                                            if (document.kind == "pdf") {
                                                PdfSession(File(requireNotNull(document.pdfPath)))
                                                    .use { it.render(0, previewEdge) }
                                            } else {
                                                repository.dao
                                                    .pages(document.id)
                                                    .firstOrNull()
                                                    ?.let { ScanProcessor.render(it, previewEdge) }
                                            }
                                        bitmap?.let {
                                            val temp = File(target.path + ".part")
                                            temp.outputStream().use { output ->
                                                it.compress(Bitmap.CompressFormat.JPEG, 82, output)
                                            }
                                            temp.renameTo(target)
                                            cache
                                                .listFiles()
                                                ?.filter { entry ->
                                                    entry.name.startsWith(document.id + "-") &&
                                                        entry != target
                                                }
                                                ?.forEach(File::delete)
                                            val files = cache.listFiles().orEmpty()
                                            if (files.size > 300)
                                                files
                                                    .sortedBy { entry -> entry.lastModified() }
                                                    .take(50)
                                                    .forEach(File::delete)
                                        }
                                        bitmap
                                    }
                            }
                            .getOrNull()
                    }
                }
        }
    Surface(
        modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border =
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            image?.let {
                Image(
                    it.asImageBitmap(),
                    "${document.title} 首页缩略图",
                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
            } ?: Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

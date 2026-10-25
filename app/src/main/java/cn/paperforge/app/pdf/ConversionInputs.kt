package cn.paperforge.app.pdf

import android.net.Uri
import cn.paperforge.app.data.Repository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object ConversionInputs {
    suspend fun retain(repo: Repository, uris: List<Uri>): List<Pair<File, String>> {
        val directory = File(repo.context.filesDir, "conversion-inputs").apply { mkdirs() }
        val results = mutableListOf<Pair<File, String>>()
        try {
            for (uri in uris) {
                currentCoroutineContext().ensureActive()
                val name =
                    if (uri.scheme == "file") File(requireNotNull(uri.path)).name
                    else repo.displayName(uri)
                val file =
                    File(
                        directory,
                        "${UUID.randomUUID()}.${name.substringAfterLast('.',"bin").filter { it.isLetterOrDigit() }.take(12).ifBlank { "bin" }}",
                    )
                val atomic = android.util.AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    repo.context.contentResolver.openInputStream(uri)?.use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                        }
                    } ?: error("无法读取$name")
                    atomic.finishWrite(stream)
                    results += file to name
                } catch (failure: Throwable) {
                    atomic.failWrite(stream)
                    throw failure
                }
            }
            return results
        } catch (failure: Throwable) {
            results.forEach { it.first.delete() }
            throw failure
        }
    }
}

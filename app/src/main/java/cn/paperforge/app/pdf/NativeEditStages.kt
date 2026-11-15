package cn.paperforge.app.pdf

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Staged native edits. Line edits may change forms as well as page contents, so the document is rewritten in one
 * pass (a job that is interrupted starts again from the unchanged source); the staging handle is kept for callers.
 */
object NativeEditStages {
    suspend fun apply(
        context: Context,
        input: File,
        output: File,
        request: JSONObject,
        fontFiles: Map<String, File>,
        @Suppress("UNUSED_PARAMETER") stages: JobFileStages,
        progress: suspend (Int) -> Unit,
    ): JSONObject = NativePdfEditor.rewrite(context, input, output, request, fontFiles, progress)
}

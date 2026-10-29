package cn.paperforge.app.data

import androidx.room.withTransaction
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class AutoExportPageSignal(
    val id: String,
    val documentId: String,
    val position: Int,
    val revision: Int,
    val processingMetadata: String,
    val ocrText: String?,
) {
    val signature
        get() =
            "$id:$position:$revision:${JSONObject(processingMetadata).optString("ocrRecord")}:${AutoExportRule.digest(ocrText.orEmpty())}"
}

data class AutoExportPendingSignal(val documentId: String, val pending: Int)

data class AutoExportSignal(
    val document: DocumentRow,
    val pageSignature: String,
    val pending: Int,
) {
    val fingerprint
        get() =
            AutoExportRule.digest(
                listOf(
                        document.id,
                        document.kind,
                        document.pdfPath,
                        document.title,
                        document.folder,
                        document.tags,
                        pageSignature.split(',').sorted().joinToString(","),
                    )
                    .joinToString("|")
            )

    val ready
        get() =
            pending == 0 &&
                (document.kind == "pdf" && document.pdfPath != null || pageSignature.isNotBlank())
}

/** Disabled rules create no document observer or automatic task. Outputs never become inputs. */
object AutoExportCoordinator {
    fun signals(repo: Repository): Flow<List<AutoExportSignal>> =
        combine(repo.documents, repo.dao.autoExportPageSignals(), repo.dao.autoExportPending()) {
            docs,
            pages,
            pending ->
            val grouped = pages.groupBy { it.documentId }
            val counts = pending.associate { it.documentId to it.pending }
            docs.map { doc ->
                AutoExportSignal(
                    doc,
                    grouped[doc.id].orEmpty().joinToString(",") { it.signature },
                    counts[doc.id] ?: 0,
                )
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(repo: Repository, scope: CoroutineScope) =
        scope.launch {
            UserSettings.revision
                .map { UserSettings[SettingKeys.AutoExportRules] }
                .distinctUntilChanged()
                .flatMapLatest {
                    if (AutoExportRules.all().any { it.enabled }) signals(repo) else emptyFlow()
                }
                .distinctUntilChanged()
                .collect { signals ->
                    val rules = AutoExportRules.all().filter { it.enabled && it.issue() == null }
                    val restoreState = BackupState(repo.context)
                    for (signal in signals.filter { it.ready }) {
                        val baseline = restoreState.baseline(signal.document.id)
                        if (baseline == signal.fingerprint) continue
                        if (baseline != null) {
                            val actual =
                                repo.database.withTransaction {
                                    val document =
                                        repo.dao.document(signal.document.id)
                                            ?: return@withTransaction null
                                    val pageSignal =
                                        repo.dao.pages(document.id).joinToString(",") { page ->
                                            AutoExportPageSignal(
                                                    page.id,
                                                    page.documentId,
                                                    page.position,
                                                    page.revision,
                                                    page.processingMetadata,
                                                    page.ocrText,
                                                )
                                                .signature
                                        }
                                    AutoExportSignal(document, pageSignal, 0).fingerprint
                                }
                            if (
                                actual == null || actual != signal.fingerprint || actual == baseline
                            )
                                continue
                            restoreState.clearBaseline(signal.document.id, baseline)
                        }
                        for (rule in rules.filter { it.matches(signal.document) }) {
                            try {
                                enqueue(repo, rule, signal)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                android.util.Log.w(
                                    "PaperForgeAutoExport",
                                    "Could not prepare automatic export",
                                    error,
                                )
                            }
                        }
                    }
                }
        }

    suspend fun enqueue(
        repo: Repository,
        rule: AutoExportRule,
        signal: AutoExportSignal,
        force: Boolean = false,
    ): LocalTaskRow? {
        rule.issue()?.let { error(it) }
        val fingerprint = rule.fingerprint(signal.fingerprint)
        val row =
            repo.database.withTransaction {
                val tasks = repo.dao.allTasks()
                if (!force && signal.document.pdfPath != null) {
                    val path = File(signal.document.pdfPath).canonicalPath
                    val generated =
                        tasks
                            .filter { it.type == "AUTO_EXPORT" }
                            .any { task ->
                                val resources = task.resourceEntries()
                                val original =
                                    resources
                                        .firstOrNull { it.id == "source" }
                                        ?.path
                                        ?.let { File(it).canonicalPath }
                                path != original &&
                                    resources.any { resource ->
                                        (resource.id == "result" ||
                                            resource.id == "auto-source-pdf" ||
                                            resource.id.startsWith("recipe-step-")) &&
                                            File(resource.path).canonicalPath == path
                                    }
                            }
                    if (generated) return@withTransaction null
                }
                val existing =
                    tasks.firstOrNull {
                        it.type == "AUTO_EXPORT" &&
                            JSONObject(it.payload).optJSONObject("input")?.let { input ->
                                input.optString("automationRule") == rule.id &&
                                    input.optString("automationFingerprint") == fingerprint
                            } == true
                    }
                if (!force && existing != null) return@withTransaction null
                val document = repo.dao.document(signal.document.id) ?: return@withTransaction null
                val pages = repo.dao.pages(signal.document.id)
                val fresh =
                    AutoExportSignal(
                        document,
                        pages.joinToString(",") {
                            AutoExportPageSignal(
                                    it.id,
                                    it.documentId,
                                    it.position,
                                    it.revision,
                                    it.processingMetadata,
                                    it.ocrText,
                                )
                                .signature
                        },
                        tasks.count {
                            it.documentId == document.id &&
                                it.type == "CAPTURE" &&
                                it.state in LocalTaskRow.active
                        },
                    )
                if (
                    !fresh.ready ||
                        fresh.fingerprint != signal.fingerprint ||
                        !rule.matches(document)
                )
                    return@withTransaction null
                val directory =
                    File(repo.context.filesDir, "automation-snapshots").apply { mkdirs() }
                val snapshot = File(directory, "${UUID.randomUUID()}.json")
                val atomic = android.util.AtomicFile(snapshot)
                val stream = atomic.startWrite()
                try {
                    val content =
                        JSONObject(HistoryState.snapshot(pages))
                            .put("document", DocumentCodec.document(signal.document))
                    stream.write(content.toString().toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
                try {
                    val prepared =
                        DocumentJobs.prepare(
                            repo,
                            "AUTO_EXPORT",
                            JobInput(
                                JSONObject()
                                    .put("document", signal.document.id)
                                    .put("name", signal.document.title)
                                    .put("automationRule", rule.id)
                                    .put("automationFingerprint", fingerprint)
                                    .put("sourceFingerprint", signal.fingerprint)
                                    .put("autoRule", rule.json())
                                    .put("snapshot", snapshot.path)
                                    .put("manual", force)
                                    .put("delaySeconds", if (force) 0 else rule.delaySeconds)
                                    .put(
                                        "options",
                                        JSONObject().put("recipe", rule.recipe.json()).toString(),
                                    )
                            ),
                        )
                    val resources =
                        prepared.resourceEntries() +
                            PageResource("auto-snapshot", "auto-snapshot", snapshot.path) +
                            pages.flatMap { page ->
                                PageResources.paths(page)
                                    .filter { File(it).isFile }
                                    .mapIndexed { index, path ->
                                        PageResource(
                                            "auto-page-${page.id}-$index",
                                            "auto-source",
                                            path,
                                        )
                                    }
                            }
                    prepared.copy(resources = DocumentResources.encode(resources).toString()).also {
                        repo.dao.putTask(it)
                    }
                } catch (error: Throwable) {
                    snapshot.delete()
                    throw error
                }
            }
        row?.let { DocumentJobs.schedule(repo, it) }
        return row
    }
}

package cn.paperforge.app.pdf

import cn.paperforge.app.data.*
import cn.paperforge.app.model.OcrRegion
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class TableDraft(
    val taskId: String,
    val source: String,
    val name: String,
    val statePath: String,
    val tables: List<PhoneTables.Table>,
    val options: TableExportOptions,
    val analysis: String,
    val sourceSha256: String,
)

class TableWorkspace(
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val navigate: (String) -> Unit,
    private val queued: (UUID) -> Unit,
    private val message: suspend (String) -> Unit,
) {
    val draft = MutableStateFlow<TableDraft?>(null)
    private val lock = Mutex()
    private val creation = Mutex()
    private var save: Job? = null

    suspend fun analyze(
        source: File,
        name: String,
        options: JSONObject,
        notify: Boolean = true,
        parent: LocalTaskRow? = null,
    ): UUID {
        val work =
            DocumentJobs.enqueue(
                repo,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "TABLE_ANALYZE")
                        .put("name", name)
                        .put("inputs", JSONArray(listOf(source.path)))
                        .put("names", JSONArray(listOf(name)))
                        .put("options", options.toString())
                        .also { if (parent != null) BatchConversions.copyGroup(parent, it) }
                ),
            )
        if (notify) withContext(Dispatchers.Main.immediate) { queued(work) }
        return work
    }

    suspend fun result(row: LocalTaskRow) =
        creation.withLock {
            val input = JSONObject(row.payload).getJSONObject("input")
            val source = input.getJSONArray("inputs").getString(0)
            val result = File(row.resourceEntries().first { it.id == "result" }.path)
            val hash = PdfAssembly.sha256(result)
            val previous =
                repo.dao.allTasks().firstOrNull { task ->
                    if (task.type != "CONVERT") false
                    else
                        JSONObject(task.payload).optJSONObject("input")?.let {
                            it.optString("kind") == "TABLE_REVIEW" &&
                                it.optString("analysisResultHash") == hash &&
                                it.optJSONArray("inputs")?.optString(0) == source &&
                                File(it.getJSONArray("inputs").getString(1)).isFile
                        } == true
                }
            if (previous != null) {
                open(previous)
                return@withLock
            }
            val state = JSONObject(result.readText())
            val options =
                TableExportOptions(
                    format =
                        if (JSONObject(input.optString("options", "{}")).optBoolean("outputCsv"))
                            "CSV"
                        else "XLSX"
                )
            val copy =
                File(repo.context.filesDir, "conversion-inputs/${UUID.randomUUID()}.pftables")
            TableJobs.atomic(copy, state.put("export", options.json()))
            val next =
                DocumentJobs.prepare(
                    repo,
                    "CONVERT",
                    JobInput(
                        JSONObject()
                            .put("kind", "TABLE_REVIEW")
                            .put("draft", true)
                            .put("analysisResultHash", hash)
                            .put("name", input.optString("name", "表格"))
                            .put("inputs", JSONArray(listOf(source, copy.path)))
                            .put(
                                "names",
                                JSONArray(
                                    listOf(input.optString("name", "原件"), "table-review.pftables")
                                ),
                            )
                            .also { BatchConversions.copyGroup(row, it) }
                    ),
                )
            repo.dao.putTask(next.copy(state = LocalTaskState.PARTIAL, detail = "表格待校对"))
            open(next)
        }

    suspend fun open(row: LocalTaskRow) {
        save?.join()
        val input = JSONObject(row.payload).getJSONObject("input")
        val paths = input.getJSONArray("inputs")
        val state = JSONObject(File(paths.getString(1)).readText())
        draft.value =
            TableDraft(
                row.id,
                paths.getString(0),
                input.optString("name", "表格"),
                paths.getString(1),
                TableData.decode(state),
                TableExportOptions.decode(state.optJSONObject("export") ?: JSONObject()),
                state.optJSONObject("analysisOptions")?.toString() ?: "{}",
                state.optString("sourceSha256"),
            )
        navigate("tables")
    }

    private fun update(value: TableDraft) {
        draft.value = value
        save?.cancel()
        save =
            scope.launch(Dispatchers.IO) {
                delay(180)
                try {
                    persist()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    message("表格草稿未能保存：${error.message}")
                }
            }
    }

    fun cell(table: Int, row: Int, column: Int, text: String) {
        val old = draft.value ?: return
        update(
            old.copy(
                tables =
                    old.tables.mapIndexed { i, t ->
                        if (i != table) t
                        else
                            t.copy(
                                rows =
                                    t.rows.mapIndexed { r, values ->
                                        values.mapIndexed { c, v ->
                                            if (r == row && c == column) text else v
                                        }
                                    },
                                cells =
                                    t.cells.map {
                                        if (it.row == row && it.column == column)
                                            it.copy(text = text, corrected = true)
                                        else it
                                    },
                            )
                    }
            )
        )
    }

    fun options(value: TableExportOptions) {
        draft.value?.let { update(it.copy(options = value)) }
    }

    suspend fun persist() =
        lock.withLock {
            val model = draft.value ?: return@withLock
            val row = repo.dao.task(model.taskId) ?: return@withLock
            if (!JSONObject(row.payload).getJSONObject("input").optBoolean("draft")) return@withLock
            TableJobs.atomic(
                File(model.statePath),
                TableData.encode(model.tables)
                    .put("export", model.options.json())
                    .put("analysisOptions", JSONObject(model.analysis))
                    .put("sourceSha256", model.sourceSha256),
            )
        }

    fun region(page: Int, region: OcrRegion) {
        val model = draft.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                persist()
                analyze(
                    File(model.source),
                    model.name,
                    JSONObject(model.analysis)
                        .put("pages", page.toString())
                        .put("joinTables", false)
                        .put(
                            "region",
                            JSONArray(listOf(region.left, region.top, region.right, region.bottom)),
                        ),
                    parent = repo.dao.task(model.taskId),
                )
            } catch (error: Exception) {
                message(error.message ?: "选区未能开始识别")
            }
        }
    }

    fun export(options: TableExportOptions) {
        val model = draft.value ?: return
        options(options)
        scope.launch(Dispatchers.IO) {
            try {
                persist()
                val snapshot =
                    File(repo.context.filesDir, "conversion-inputs/${UUID.randomUUID()}.pftables")
                TableJobs.atomic(
                    snapshot,
                    TableData.encode(model.tables)
                        .put("sourceSha256", model.sourceSha256)
                        .put("analysisOptions", JSONObject(model.analysis)),
                )
                val work =
                    DocumentJobs.enqueue(
                        repo,
                        "CONVERT",
                        JobInput(
                            JSONObject()
                                .put("kind", "TABLE_EXPORT")
                                .put("name", model.name)
                                .put("inputs", JSONArray(listOf(model.source, snapshot.path)))
                                .put(
                                    "names",
                                    JSONArray(listOf(model.name, "frozen-review.pftables")),
                                )
                                .put("options", options.json().toString())
                                .also { input ->
                                    repo.dao.task(model.taskId)?.let {
                                        BatchConversions.copyGroup(it, input)
                                    }
                                }
                        ),
                    )
                withContext(Dispatchers.Main.immediate) { queued(work) }
            } catch (error: Exception) {
                message(error.message ?: "表格未能导出")
            }
        }
    }

    fun close() {
        scope.launch {
            persist()
            val row = draft.value?.taskId?.let { repo.dao.task(it) }
            navigate(
                if (row != null && BatchConversions.batchId(row).isNotBlank()) "conversion-batch"
                else "convert"
            )
        }
    }
}

package cn.paperforge.app.pdf

import androidx.room.withTransaction
import cn.paperforge.app.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Editing is a persistent CONVERT draft; generation uses the same stable WorkManager task. */
class LongImageWorkspace(
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val navigate: (String) -> Unit,
    private val queued: (UUID) -> Unit,
    private val message: suspend (String) -> Unit,
) {
    val draft = MutableStateFlow<LongImageDraft?>(null)
    val working = MutableStateFlow("")
    private val lock = Mutex()
    private var saveJob: Job? = null
    private var analysis: Job? = null

    suspend fun create(
        inputs: List<Pair<File, String>>,
        options: LongImageOptions,
        returnScreen: String = "convert",
        returnDocument: String? = null,
    ) {
        val model =
            LongImageDraft(
                "",
                inputs.map { StitchSource(UUID.randomUUID().toString(), it.first.path, it.second) },
                options,
                returnScreen = returnScreen,
                returnDocument = returnDocument,
            )
        val row = DocumentJobs.prepare(repo, "CONVERT", JobInput(model.input()))
        repo.dao.putTask(row.copy(state = LocalTaskState.PARTIAL, detail = "拼接待调整"))
        open(row)
    }

    suspend fun copy(row: LocalTaskRow) {
        val original = LongImageDraft.decode(row)
        val next = DocumentJobs.prepare(repo, "CONVERT", JobInput(original.input()))
        repo.dao.putTask(next.copy(state = LocalTaskState.PARTIAL, detail = "拼接待调整"))
        open(next)
    }

    private fun destination(model: LongImageDraft) =
        if (model.returnScreen == "editor" && model.returnDocument != null)
            "document:${model.returnDocument}"
        else model.returnScreen

    fun open(row: LocalTaskRow) {
        analysis?.cancel()
        draft.value = LongImageDraft.decode(row)
        working.value = ""
        navigate("long-image-compose")
    }

    fun seam(index: Int, value: StitchSeam) {
        val old = draft.value ?: return
        if (index !in 1 until old.sources.size) return
        update(old.copy(seams = old.seams + (old.key(index) to value)))
    }

    fun options(value: LongImageOptions) {
        draft.value?.let { update(it.copy(options = value)) }
    }

    fun move(index: Int, delta: Int) {
        val old = draft.value ?: return
        if (index !in old.sources.indices || index + delta !in old.sources.indices) return
        val sources = old.sources.toMutableList()
        val source = sources.removeAt(index)
        sources.add(index + delta, source)
        update(old.copy(sources = sources))
    }

    fun remove(index: Int) {
        val old = draft.value ?: return
        if (old.sources.size > 1 && index in old.sources.indices)
            update(old.copy(sources = old.sources.filterIndexed { i, _ -> i != index }))
    }

    private fun update(next: LongImageDraft) {
        draft.value = next.copy(revision = next.revision + 1)
        saveJob?.cancel()
        saveJob =
            scope.launch(Dispatchers.IO) {
                delay(180)
                runCatching { persist() }
                    .onFailure {
                        if (it !is CancellationException) message("接缝草稿尚未保存：${it.message}")
                    }
            }
    }

    suspend fun persist() =
        lock.withLock {
            val latest = draft.value ?: return@withLock
            repo.database.withTransaction {
                val row = repo.dao.task(latest.taskId) ?: return@withTransaction
                val payload = JSONObject(row.payload)
                if (!payload.getJSONObject("input").optBoolean("draft")) return@withTransaction
                payload.put("input", latest.input())
                repo.dao.putTask(
                    row.copy(payload = payload.toString(), updated = System.currentTimeMillis())
                )
            }
        }

    fun analyze() {
        val snapshot = draft.value ?: return
        if (working.value.isNotBlank() || snapshot.sources.size < 2) return
        analysis =
            scope.launch {
                try {
                    val results = mutableMapOf<String, StitchSeam>()
                    for (i in 1 until snapshot.sources.size) {
                        working.value = "识别接缝 $i / ${snapshot.sources.size-1}"
                        val result =
                            withContext(Dispatchers.Default) {
                                runCatching {
                                        ReceiptAlignment.match(
                                            File(snapshot.sources[i - 1].path),
                                            File(snapshot.sources[i].path),
                                        )
                                    }
                                    .getOrElse { StitchSeam(mode = "UNRESOLVED") }
                            }
                        ensureActive()
                        results[snapshot.key(i)] = result
                    }
                    draft.value
                        ?.takeIf { it.taskId == snapshot.taskId }
                        ?.let { current -> update(current.copy(seams = current.seams + results)) }
                } finally {
                    working.value = ""
                }
            }
    }

    fun close() {
        analysis?.cancel()
        scope.launch {
            saveJob?.join()
            withContext(Dispatchers.IO) { persist() }
            navigate(draft.value?.let(::destination) ?: "convert")
        }
    }

    fun generate() {
        val model = draft.value ?: return
        if (working.value.isNotBlank()) return
        working.value = "保存并开始拼接"
        scope.launch {
            try {
                saveJob?.join()
                val id =
                    withContext(Dispatchers.IO + NonCancellable) {
                        persist()
                        val row = repo.dao.task(model.taskId) ?: error("拼接草稿已移除")
                        val payload = JSONObject(row.payload)
                        payload.getJSONObject("input").put("draft", false)
                        repo.dao.putTask(row.copy(payload = payload.toString()))
                        DocumentJobs.retry(repo, row)
                    }
                navigate(destination(model))
                queued(id)
            } catch (error: Exception) {
                message(error.message ?: "拼接未能开始")
            } finally {
                working.value = ""
            }
        }
    }
}

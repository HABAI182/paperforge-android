package cn.paperforge.app

import cn.paperforge.app.data.Repository
import cn.paperforge.app.model.parsePageRange
import cn.paperforge.app.pdf.*
import cn.paperforge.app.ui.PdfSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

class PdfAssemblyController(
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val messages: Channel<String>,
) {
    val plan = MutableStateFlow(AssemblyDraft.read(repo.context))
    val loading = MutableStateFlow(false)
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    private val past = java.util.ArrayDeque<AssemblyPlan>()
    private val future = java.util.ArrayDeque<AssemblyPlan>()
    private var saving: Job? = null
    private var reading: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()

    fun cancelLoading() {
        generation.incrementAndGet()
        reading?.cancel()
        loading.value = false
    }

    fun change(next: AssemblyPlan) {
        if (next == plan.value) return
        past.addLast(plan.value)
        while (past.size > 30) past.removeFirst()
        future.clear()
        plan.value = next
        save()
    }

    private fun save() {
        canUndo.value = past.isNotEmpty()
        canRedo.value = future.isNotEmpty()
        saving?.cancel()
        saving =
            scope.launch {
                delay(150)
                try {
                    withContext(Dispatchers.IO) { AssemblyDraft.write(repo.context, plan.value) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "组合草稿未保存")
                }
            }
    }

    fun undo() {
        if (past.isNotEmpty()) {
            future.addLast(plan.value)
            plan.value = past.removeLast()
            save()
        }
    }

    fun redo() {
        if (future.isNotEmpty()) {
            past.addLast(plan.value)
            plan.value = future.removeLast()
            save()
        }
    }

    fun addSources(ids: List<String>) {
        if (!loading.compareAndSet(false, true)) return
        val token = generation.incrementAndGet()
        reading =
            scope.launch {
                try {
                    val added =
                        withContext(Dispatchers.IO) {
                            ids.distinct()
                                .filter { id -> plan.value.sources.none { it.documentId == id } }
                                .map { id ->
                                    val doc =
                                        repo.dao.document(id)?.takeIf {
                                            it.kind == "pdf" && it.trashedAt == null
                                        } ?: error("来源 PDF 已移除")
                                    val file = File(checkNotNull(doc.pdfPath))
                                    AssemblySource(
                                        id,
                                        doc.title,
                                        PdfSession(file).use { it.count },
                                        PdfAssembly.sha256(file),
                                    )
                                }
                        }
                    if (token != generation.get()) return@launch
                    val current = plan.value
                    change(
                        current.copy(
                            sources = current.sources + added,
                            pages =
                                current.pages +
                                    added.flatMap { source ->
                                        List(source.count) {
                                            AssemblyPage(sourceId = source.documentId, page = it)
                                        }
                                    },
                        )
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "未能加入来源")
                } finally {
                    if (token == generation.get()) loading.value = false
                }
            }
    }

    fun sourcePages(id: String, title: String, range: String?): String? {
        try {
            val current = plan.value
            val source = current.sources.first { it.documentId == id }
            var pages = current.pages
            if (range != null) {
                val selected = parsePageRange(range, source.count)
                val insertion =
                    pages.indexOfFirst { it.sourceId == id }.takeIf { it >= 0 } ?: pages.size
                pages =
                    pages
                        .filter { it.sourceId != id }
                        .toMutableList()
                        .apply {
                            addAll(
                                insertion.coerceAtMost(size),
                                selected.map { AssemblyPage(sourceId = id, page = it) },
                            )
                        }
            }
            change(
                current.copy(
                    sources =
                        current.sources.map {
                            if (it.documentId == id) it.copy(title = title.ifBlank { it.title })
                            else it
                        },
                    pages = pages,
                )
            )
            return null
        } catch (error: Exception) {
            return error.message ?: "页码无法识别"
        }
    }

    fun refresh(id: String) {
        if (!loading.compareAndSet(false, true)) return
        val token = generation.incrementAndGet()
        reading =
            scope.launch {
                try {
                    val source =
                        withContext(Dispatchers.IO) {
                            val doc = repo.dao.document(id) ?: error("来源已移除")
                            val file = File(checkNotNull(doc.pdfPath))
                            val old = plan.value.sources.first { it.documentId == id }
                            old.copy(
                                count = PdfSession(file).use { it.count },
                                sha256 = PdfAssembly.sha256(file),
                            )
                        }
                    if (token != generation.get()) return@launch
                    val current = plan.value
                    val removed =
                        current.pages.count { it.sourceId == id && it.page >= source.count }
                    change(
                        current.copy(
                            sources =
                                current.sources.map { if (it.documentId == id) source else it },
                            pages =
                                current.pages.filter { it.sourceId != id || it.page < source.count },
                        )
                    )
                    messages.send("来源已更新" + if (removed > 0) "，移除了 $removed 个已不存在的页面" else "")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "来源未能更新")
                } finally {
                    if (token == generation.get()) loading.value = false
                }
            }
    }

    fun rotate(keys: Set<String>) =
        change(
            plan.value.copy(
                pages =
                    plan.value.pages.map {
                        if (it.key in keys) it.copy(rotation = (it.rotation + 90) % 360) else it
                    }
            )
        )

    fun remove(keys: Set<String>) =
        change(plan.value.copy(pages = plan.value.pages.filter { it.key !in keys }))

    fun duplicate(keys: Set<String>) =
        change(
            plan.value.copy(
                pages =
                    plan.value.pages.flatMap {
                        if (it.key in keys) listOf(it, it.copy(key = UUID.randomUUID().toString()))
                        else listOf(it)
                    }
            )
        )

    fun reverse(keys: Set<String>) {
        val current = plan.value
        val positions =
            current.pages.indices.filter { keys.isEmpty() || current.pages[it].key in keys }
        val values = positions.map { current.pages[it] }.reversed()
        change(
            current.copy(
                pages =
                    current.pages.toMutableList().apply {
                        positions.forEachIndexed { index, position ->
                            this[position] = values[index]
                        }
                    }
            )
        )
    }

    fun move(keys: Set<String>, index: Int) {
        val current = plan.value
        val chosen = current.pages.filter { it.key in keys }
        val rest = current.pages.filter { it.key !in keys }.toMutableList()
        rest.addAll(index.coerceIn(0, rest.size), chosen)
        change(current.copy(pages = rest))
    }

    fun blank(after: String?) {
        val current = plan.value
        val position =
            current.pages.indexOfFirst { it.key == after }.takeIf { it >= 0 }?.plus(1)
                ?: current.pages.size
        change(
            current.copy(
                pages = current.pages.toMutableList().apply { add(position, AssemblyPage()) }
            )
        )
    }

    fun interleave(first: String, second: String, reverse: Boolean) {
        try {
            change(plan.value.interleave(first, second, reverse))
        } catch (error: Exception) {
            scope.launch { messages.send(error.message ?: "交错合并未完成") }
        }
    }

    suspend fun flush() {
        saving?.cancelAndJoin()
        withContext(Dispatchers.IO) { AssemblyDraft.write(repo.context, plan.value) }
    }

    suspend fun reload() {
        saving?.cancelAndJoin()
        plan.value = withContext(Dispatchers.IO) { AssemblyDraft.read(repo.context) }
        past.clear()
        future.clear()
        canUndo.value = false
        canRedo.value = false
    }
}

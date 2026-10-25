package cn.paperforge.app

import cn.paperforge.app.data.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

class LibraryComparisonController(
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val messages: Channel<String>,
) {
    private val preferences = LibraryPreferences(repo.context)
    val families = MutableStateFlow(preferences.families())
    val report = MutableStateFlow<ComparisonReport?>(null)
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val tab = MutableStateFlow(0)
    val scopeIds = MutableStateFlow<Set<String>?>(null)
    val familyTarget = MutableStateFlow<String?>(null)
    private val generation = AtomicLong()
    private var task: Job? = null

    fun configure(ids: Set<String>?, versions: Boolean = false, familyId: String? = null) {
        if (scopeIds.value != ids) {
            cancel()
            scopeIds.value = ids
            report.value = null
        }
        tab.value = if (versions) 1 else 0
        familyTarget.value = familyId
        refreshFamilies()
    }

    fun refreshFamilies() {
        families.value = preferences.families()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val token = generation.incrementAndGet()
        val ids = scopeIds.value
        task =
            scope.launch {
                try {
                    val result =
                        repo.compareDocuments(ids) {
                            if (generation.get() == token) progress.value = it
                        }
                    if (generation.get() == token) report.value = result
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    messages.send(error.message ?: "比较未完成")
                } finally {
                    if (generation.get() == token) {
                        running.value = false
                        progress.value = ""
                    }
                }
            }
    }

    fun cancel() {
        generation.incrementAndGet()
        task?.cancel()
        running.value = false
        progress.value = ""
    }

    fun ignore(key: String) {
        report.value =
            report.value?.let {
                it.copy(similar = it.similar.filterNot { pair -> pair.key == key })
            }
    }

    fun group(name: String, ids: List<String>) = change {
        val live =
            repo.dao
                .allDocuments()
                .filter { it.listed && it.trashedAt == null && it.id in ids }
                .map { it.id }
        val group = preferences.group(name, live)
        familyTarget.value = group.id
        tab.value = 1
        messages.send("已归入“${group.name}”，文档内容保留")
    }

    fun update(
        id: String,
        name: String? = null,
        preferred: String? = null,
        remove: String? = null,
    ) = change { preferences.updateFamily(id, name, preferred, remove) }

    fun dissolve(id: String) = change { preferences.dissolveFamily(id) }

    private fun change(action: suspend () -> Unit) =
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                refreshFamilies()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                messages.send(error.message ?: "版本分组未保存")
            }
        }
}

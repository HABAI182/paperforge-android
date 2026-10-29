package cn.paperforge.app.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

/**
 * Inherited by background exports; interactive OCR requests retain priority between native passes.
 */
object BackgroundWork : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BackgroundWork>
}

class InteractiveGate {
    private data class Waiter(
        val id: String,
        val interactive: Boolean,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val monitor = Any()
    private var active: Waiter? = null
    private val queue = mutableListOf<Waiter>()
    val activeId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val waitingIds = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val preferredId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private fun publish() {
        activeId.value = active?.id
        waitingIds.value = queue.map { it.id }
    }

    val availablePermits: Int
        get() = synchronized(monitor) { if (active == null) 1 else 0 }

    fun prefer(id: String?) {
        synchronized(monitor) { preferredId.value = id }
    }

    private fun next() {
        val selected =
            queue.firstOrNull { it.interactive }
                ?: queue.firstOrNull { it.id == preferredId.value }
                ?: queue.firstOrNull()
        active = selected
        if (selected != null) {
            queue.remove(selected)
            if (selected.id == preferredId.value) preferredId.value = null
        }
        publish()
        selected?.ready?.complete(Unit)
    }

    suspend fun acquire(id: String = "", interactive: Boolean = false) {
        val waiter = Waiter(id, interactive)
        synchronized(monitor) {
            if (active == null) {
                active = waiter
                waiter.ready.complete(Unit)
            } else queue += waiter
            publish()
        }
        try {
            waiter.ready.await()
        } catch (failure: Throwable) {
            synchronized(monitor) {
                if (!queue.remove(waiter) && active === waiter) next() else publish()
            }
            throw failure
        }
    }

    fun release() {
        synchronized(monitor) {
            check(active != null) { "资源槽位没有被占用" }
            next()
        }
    }

    suspend fun <T> use(interactive: Boolean, action: suspend () -> T): T {
        acquire(interactive = interactive)
        try {
            return action()
        } finally {
            release()
        }
    }
}

object RuntimeBudget {
    // At most one heavy background pipeline holds full-resolution page buffers at a time.
    // Foreground reading uses its existing bounded tile cache and never waits on this slot.
    val background = InteractiveGate()
    private val memory = ThreadLocal<WorkingMemoryPlan?>()

    fun memoryContext(plan: WorkingMemoryPlan) = memory.asContextElement(plan)

    fun memoryPlan() =
        memory.get()
            ?: WorkingMemoryPlan.create(
                cn.paperforge.app.UserSettings[cn.paperforge.app.SettingKeys.TaskMemoryMB].toInt(),
                Runtime.getRuntime().maxMemory(),
            )

    val ocr = InteractiveGate()
}

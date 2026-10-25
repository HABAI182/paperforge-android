package cn.paperforge.app.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Inherited by background exports; interactive OCR requests retain priority between native passes.
 */
object BackgroundWork : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BackgroundWork>
}

class InteractiveGate {
    private data class Waiter(
        val interactive: Boolean,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Mutex()
    private var occupied = false
    private val queue = mutableListOf<Waiter>()

    private fun next() {
        val candidate = queue.firstOrNull { it.interactive } ?: queue.firstOrNull()
        if (candidate == null) occupied = false
        else {
            queue.remove(candidate)
            candidate.ready.complete(Unit)
        }
    }

    suspend fun <T> use(interactive: Boolean, action: suspend () -> T): T {
        val waiter = Waiter(interactive)
        lock.withLock {
            if (!occupied) {
                occupied = true
                waiter.ready.complete(Unit)
            } else queue += waiter
        }
        try {
            waiter.ready.await()
        } catch (failure: Throwable) {
            withContext(NonCancellable) { lock.withLock { if (!queue.remove(waiter)) next() } }
            throw failure
        }
        try {
            return action()
        } finally {
            withContext(NonCancellable) { lock.withLock { next() } }
        }
    }
}

object RuntimeBudget {
    // At most one heavy background pipeline holds full-resolution page buffers at a time.
    // Foreground reading uses its existing bounded tile cache and never waits on this slot.
    val background = Semaphore(1)
    val ocr = InteractiveGate()
}

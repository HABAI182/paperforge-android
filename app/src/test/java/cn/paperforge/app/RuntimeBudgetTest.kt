package cn.paperforge.app

import cn.paperforge.app.data.InteractiveGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeBudgetTest {
    @Test
    fun interactiveWorkPassesQueuedBackgroundAndCancellationReleasesItsPlace() =
        runBlocking<Unit> {
            val gate = InteractiveGate()
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val running =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    gate.use(false) {
                        order += "running"
                        release.await()
                    }
                }
            val background =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    gate.use(false) { order += "background" }
                }
            val cancelled =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    gate.use(true) { error("Cancelled request ran") }
                }
            val interactive =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    gate.use(true) { order += "interactive" }
                }
            cancelled.cancelAndJoin()
            release.complete(Unit)
            joinAll(running, background, interactive)
            assertEquals(listOf("running", "interactive", "background"), order)
            assertEquals(7, gate.use(true) { 7 })
        }
}

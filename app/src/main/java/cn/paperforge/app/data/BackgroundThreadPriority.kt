package cn.paperforge.app.data

import android.os.Process
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * Native calls use a background scheduling priority; shared coroutine threads regain their value.
 */
object BackgroundThreadPriority : ThreadContextElement<Int?>, AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BackgroundThreadPriority>

    override fun updateThreadContext(context: CoroutineContext): Int? =
        try {
            Process.getThreadPriority(Process.myTid()).also {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            }
        } catch (_: SecurityException) {
            null
        }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int?) {
        if (oldState != null) runCatching { Process.setThreadPriority(oldState) }
    }
}

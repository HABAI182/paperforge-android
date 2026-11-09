package cn.paperforge.app.imaging

import org.json.JSONObject

/**
 * Wall time of named steps while one page is made, on the thread making it, for the page's diagnostics.
 * Outside [record] nothing is kept, so the calls cost a clock read and nothing else.
 */
internal object StepTimes {
    private val current = ThreadLocal<JSONObject?>()

    /** Runs [block] with timing on; returns its result and the milliseconds spent per step. */
    inline fun <T> record(block: () -> T): Pair<T, JSONObject> {
        begin()
        try {
            return block() to finish()
        } finally {
            clear()
        }
    }

    @PublishedApi internal fun begin() = current.set(JSONObject())

    @PublishedApi internal fun finish(): JSONObject = current.get() ?: JSONObject()

    @PublishedApi internal fun clear() = current.remove()

    fun add(name: String, nanos: Long) {
        val times = current.get() ?: return
        times.put(name, times.optDouble(name, 0.0) + Math.round(nanos / 1e5) / 10.0)
    }

    inline fun <T> time(name: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            add(name, System.nanoTime() - started)
        }
    }
}

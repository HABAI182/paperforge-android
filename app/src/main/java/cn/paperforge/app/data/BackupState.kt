package cn.paperforge.app.data

import android.content.Context
import org.json.JSONObject

/** Stable source identities and the initial automatic-export baseline of restored documents. */
internal class BackupState(context: Context) {
    private val prefs = context.getSharedPreferences("backup-state", 0)

    private fun read(key: String): JSONObject = JSONObject(prefs.getString(key, "{}") ?: "{}")

    fun documentOrigins() = synchronized(lock) { read("documents") }

    fun pageOrigins() = synchronized(lock) { read("pages") }

    fun baseline(id: String): String? =
        synchronized(lock) { read("baseline").optString(id).takeIf { it.isNotEmpty() } }

    fun publish(
        documents: Map<String, String>,
        pages: Map<String, String>,
        baselines: Map<String, String>,
    ) =
        synchronized(lock) {
            val d = read("documents")
            val p = read("pages")
            val b = read("baseline")
            documents.forEach { (id, source) -> d.put(id, source) }
            pages.forEach { (id, source) -> p.put(id, source) }
            baselines.forEach { (id, fingerprint) -> b.put(id, fingerprint) }
            check(
                prefs
                    .edit()
                    .putString("documents", d.toString())
                    .putString("pages", p.toString())
                    .putString("baseline", b.toString())
                    .commit()
            ) {
                "无法保存恢复身份"
            }
        }

    fun clearBaseline(id: String, expected: String) =
        synchronized(lock) {
            val b = read("baseline")
            if (b.optString(id) == expected) {
                b.remove(id)
                check(prefs.edit().putString("baseline", b.toString()).commit())
            }
        }

    companion object {
        private val lock = Any()
    }
}

package cn.paperforge.app.data

import android.content.Context
import cn.paperforge.app.reader.StudyNotes

/** Companion state is restored before the Room transaction commits; failures roll it back. */
internal class RestoreSideState(private val context: Context) {
    private val preferences =
        listOf(
                "preferences",
                "profiles",
                "reader",
                "reader-view",
                "signature",
                "library",
                "backup-state",
            )
            .associateWith { context.getSharedPreferences(it, 0).all.toMap() }
    private val notes = StudyNotes.load(context)
    private val assembly = cn.paperforge.app.pdf.AssemblyDraft.read(context)

    fun restore() {
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                if (failure == null) failure = e else failure!!.addSuppressed(e)
            }
        }
        attempt { StudyNotes.replace(context, notes) }
        attempt { cn.paperforge.app.pdf.AssemblyDraft.write(context, assembly) }
        preferences.forEach { (name, values) ->
            attempt {
                val edit = context.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> edit.putString(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Float -> edit.putFloat(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Set<*> ->
                            edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                check(edit.commit()) { "未能恢复本地设置" }
            }
        }
        cn.paperforge.app.UserSettings.revision.value += 1
        failure?.let { throw it }
    }
}

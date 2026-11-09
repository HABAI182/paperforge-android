package cn.paperforge.app

import kotlinx.coroutines.launch

/** Large native engines live in their own processes; the app still has one installed identity. */
class FullPaperForgeApp : PaperForgeApp() {
    override fun onCreate() {
        super.onCreate()
        if (currentProcessName() == packageName)
            backgroundScope.launch {
                runCatching { cn.paperforge.network.AutomaticUpdates.sync(this@FullPaperForgeApp) }
                    .onFailure {
                        android.util.Log.w("PaperForgeUpdates", "Update schedule unavailable", it)
                    }
            }
        if (currentProcessName().endsWith(":formats")) {
            cn.paperforge.formats.FormatApp.initialize(this, true)
        }
    }
}

package cn.paperforge.app

/** Large native engines live in their own processes; the app still has one installed identity. */
class FullPaperForgeApp : PaperForgeApp() {
    override fun onCreate() {
        super.onCreate()
        if (currentProcessName().endsWith(":formats")) {
            cn.paperforge.formats.FormatApp.initialize(this, true)
        }
    }
}

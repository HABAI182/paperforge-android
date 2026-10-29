package cn.paperforge.formats

import android.app.Application
import android.os.Build
import android.webkit.WebView

class FormatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initialize(this, Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":worker"))
    }

    companion object {
        fun initialize(context: android.content.Context, worker: Boolean) {
            ru.noties.jlatexmath.JLatexMathAndroid.init(context)
            if (Build.VERSION.SDK_INT >= 28 && worker) {
                WebView.setDataDirectorySuffix("format-worker")
            }
            WebView.enableSlowWholeDocumentDraw()
        }
    }
}

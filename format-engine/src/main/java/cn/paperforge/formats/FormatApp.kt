package cn.paperforge.formats

import android.app.Application
import android.os.Build
import android.webkit.WebView

class FormatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":worker")) {
            WebView.setDataDirectorySuffix("format-worker")
        }
        WebView.enableSlowWholeDocumentDraw()
    }
}

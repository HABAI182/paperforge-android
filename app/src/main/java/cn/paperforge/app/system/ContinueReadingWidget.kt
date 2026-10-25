package cn.paperforge.app.system

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import cn.paperforge.app.PaperForgeApp
import kotlinx.coroutines.launch

class ContinueReadingWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (
            intent.action !in
                setOf(
                    AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_LOCALE_CHANGED,
                )
        )
            return
        val result = goAsync()
        (context.applicationContext as PaperForgeApp).backgroundScope.launch {
            try {
                SystemEntrypoints.refreshWidgets(context)
            } finally {
                result.finish()
            }
        }
    }
}

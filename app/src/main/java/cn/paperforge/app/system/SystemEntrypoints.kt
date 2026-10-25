package cn.paperforge.app.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import cn.paperforge.app.MainActivity
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.R
import cn.paperforge.app.ReaderPreferences
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.Repository
import java.io.File

object SystemEntrypoints {
    const val SCAN = "cn.paperforge.app.action.SCAN"
    const val OCR = "cn.paperforge.app.action.OCR"
    const val CONTINUE = "cn.paperforge.app.action.CONTINUE_READING"

    fun intent(context: Context, action: String) =
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

    private fun shortcuts(context: Context) =
        listOf(
                Triple(SCAN, R.string.shortcut_scan, R.drawable.ic_shortcut_scan),
                Triple(OCR, R.string.shortcut_ocr, R.drawable.ic_shortcut_ocr),
                Triple(CONTINUE, R.string.shortcut_continue, R.drawable.ic_shortcut_read),
            )
            .mapIndexed { rank, (action, label, icon) ->
                ShortcutInfo.Builder(context, action)
                    .setShortLabel(context.getString(label))
                    .setLongLabel(context.getString(label))
                    .setRank(rank)
                    .setIcon(Icon.createWithResource(context, icon))
                    .setIntent(intent(context, action))
                    .build()
            }

    fun register(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val desired = shortcuts(context)
        val current = manager.dynamicShortcuts
        if (
            desired.any { next ->
                current.none {
                    it.id == next.id &&
                        it.shortLabel == next.shortLabel &&
                        it.intent?.action == next.intent?.action
                }
            }
        ) {
            manager.dynamicShortcuts = desired.take(manager.maxShortcutCountPerActivity)
        }
    }

    fun pin(context: Context, action: String): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        return manager.requestPinShortcut(shortcuts(context).first { it.id == action }, null)
    }

    fun pending(context: Context, action: String) =
        PendingIntent.getActivity(
            context,
            action.hashCode(),
            intent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun latestReadable(repo: Repository): DocumentRow? =
        repo.dao
            .allDocuments()
            .asSequence()
            .filter {
                it.trashedAt == null &&
                    it.listed &&
                    it.kind == "pdf" &&
                    it.pdfPath?.let { path -> File(path).isFile } == true
            }
            .sortedWith(
                compareByDescending<DocumentRow> { it.lastOpened }.thenByDescending { it.modified }
            )
            .firstOrNull()

    suspend fun refreshWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ContinueReadingWidget::class.java))
        if (ids.isEmpty()) return
        val locked = context.getSharedPreferences("security", 0).getBoolean("appLock", false)
        val doc =
            if (locked) null
            else latestReadable((context.applicationContext as PaperForgeApp).repository)
        val title =
            when {
                locked -> context.getString(R.string.widget_unlock)
                doc != null -> doc.title
                else -> context.getString(R.string.shortcut_continue)
            }
        val subtitle =
            doc?.let { "第 ${ReaderPreferences(context).load(it.id).page + 1} 页" }
                ?: context.getString(
                    if (locked) R.string.widget_locked_description else R.string.widget_empty
                )
        val views =
            RemoteViews(context.packageName, R.layout.widget_continue_reading).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_subtitle, subtitle)
                setOnClickPendingIntent(R.id.widget_read, pending(context, CONTINUE))
                setOnClickPendingIntent(R.id.widget_scan, pending(context, SCAN))
            }
        manager.updateAppWidget(ids, views)
    }
}

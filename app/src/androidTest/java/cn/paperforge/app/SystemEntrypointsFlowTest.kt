package cn.paperforge.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.system.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android launcher/RemoteViews/PendingIntent integration; no old reader or camera suites. */
@RunWith(AndroidJUnit4::class)
class SystemEntrypointsFlowTest {
    @Test
    fun launcherWidgetContinuationAndDirectScanUseDistinctIntentsAndKeepDocuments() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val file = File(repo.exports, "entrypoints-${System.nanoTime()}.pdf")
        PDDocument().use {
            it.addPage(PDPage())
            it.addPage(PDPage())
            it.save(file)
        }
        val doc = repo.registerPdf(file, "桌面继续阅读 00123")
        repo.dao.opened(doc.id, System.currentTimeMillis() + 10000)
        ReaderPreferences(app).save(doc.id, ReaderPosition(page = 1))
        val originalAuto = UserSettings.prefs.all["autoShutter"]
        UserSettings.set("autoShutter", false)
        val security = app.getSharedPreferences("security", 0)
        val originalLock = security.getBoolean("appLock", false)
        security.edit().putBoolean("appLock", false).commit()
        val host = AppWidgetHost(app, 43025)
        val manager = AppWidgetManager.getInstance(app)
        val widgetId = host.allocateAppWidgetId()
        var createdScan: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        suspend fun until(condition: () -> Boolean) =
            withTimeout(15000) { while (!condition()) delay(25) }
        try {
            val shortcuts = app.getSystemService(ShortcutManager::class.java).dynamicShortcuts
            assertTrue(
                shortcuts
                    .map { it.id }
                    .containsAll(
                        listOf(
                            SystemEntrypoints.SCAN,
                            SystemEntrypoints.OCR,
                            SystemEntrypoints.CONTINUE,
                        )
                    )
            )
            assertEquals(3, shortcuts.map { it.intent?.action }.toSet().size)
            assertTrue(
                "Temporary emulator-only appwidget grantbind is required",
                manager.bindAppWidgetIdIfAllowed(
                    widgetId,
                    ComponentName(app, ContinueReadingWidget::class.java),
                ),
            )
            host.startListening()
            SystemEntrypoints.refreshWidgets(app)
            val info = manager.getAppWidgetInfo(widgetId)
            val view =
                withContext(Dispatchers.Main) {
                    host.createView(app, widgetId, info).apply {
                        measure(
                            View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(330, View.MeasureSpec.EXACTLY),
                        )
                        layout(0, 0, 840, 330)
                    }
                }
            until { view.findViewById<TextView>(R.id.widget_title)?.text == doc.title }
            assertEquals("第 2 页", view.findViewById<TextView>(R.id.widget_subtitle).text.toString())
            withContext(Dispatchers.Main) {
                val bitmap = Bitmap.createBitmap(840, 330, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(app.cacheDir, "continue-widget-proof.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            scenario =
                ActivityScenario.launch(SystemEntrypoints.intent(app, SystemEntrypoints.CONTINUE))
            var model: MainViewModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            until { model?.selected?.value?.id == doc.id && model?.screen?.value == "reader" }
            assertEquals(1, ReaderPreferences(app).load(doc.id).page)
            scenario.onActivity {
                it.startActivity(SystemEntrypoints.intent(it, SystemEntrypoints.SCAN))
            }
            until { model?.screen?.value == "capture" && model?.selected?.value?.kind == "scan" }
            createdScan = model!!.selected.value!!.id
            withContext(Dispatchers.Main) {
                view.findViewById<View>(R.id.widget_read).performClick()
            }
            until { model?.selected?.value?.id == doc.id && model?.screen?.value == "reader" }
            security.edit().putBoolean("appLock", true).commit()
            SystemEntrypoints.refreshWidgets(app)
            until {
                view.findViewById<TextView>(R.id.widget_title)?.text ==
                    app.getString(R.string.widget_unlock)
            }
            assertFalse(view.findViewById<TextView>(R.id.widget_title).text.contains(doc.title))
            assertNotNull(repo.dao.document(doc.id))
        } finally {
            security.edit().putBoolean("appLock", originalLock).commit()
            scenario?.close()
            host.stopListening()
            host.deleteAppWidgetId(widgetId)
            host.deleteHost()
            if (originalAuto == null) UserSettings.prefs.edit().remove("autoShutter").commit()
            else UserSettings.set("autoShutter", originalAuto as Boolean)
            createdScan?.let { repo.dao.document(it)?.let { row -> repo.deleteDocument(row) } }
            repo.deleteDocument(doc)
        }
    }
}

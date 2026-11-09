package cn.paperforge.app

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackWorkflowTest {
    @Test
    fun createWithExistingFilesAndBatchMoveAreUndoable() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val preferences = LibraryPreferences(app)
        val oldPaths = preferences.folders()
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        fun main(action: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        main {
            model =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    MainViewModel::class.java]
        }
        suspend fun perform(action: () -> Unit) {
            main(action)
            withTimeout(10_000) { model.busy.first { !it } }
        }
        val a = DocumentRow(UUID.randomUUID().toString(), "Feedback A")
        val b = DocumentRow(UUID.randomUUID().toString(), "Feedback B")
        val first = "feedback-${a.id}/first"
        val second = "feedback-${a.id}/second"
        try {
            repo.dao.putDocument(a)
            repo.dao.putDocument(b)
            perform { model.createLibraryFolder(first, setOf(a.id)) }
            assertEquals(first, repo.dao.document(a.id)!!.folder)
            assertEquals("", repo.dao.document(b.id)!!.folder)
            perform { model.categorizeDocuments(setOf(a.id, b.id), second, null) }
            assertEquals(second, repo.dao.document(a.id)!!.folder)
            assertEquals(second, repo.dao.document(b.id)!!.folder)
            assertTrue(second in preferences.folders())
            perform { model.undoLibraryFolder() }
            assertEquals(first, repo.dao.document(a.id)!!.folder)
            assertEquals("", repo.dao.document(b.id)!!.folder)
        } finally {
            repo.dao.deleteDocumentRaw(a.id)
            repo.dao.deleteDocumentRaw(b.id)
            preferences.folders(oldPaths)
            main { store.clear() }
        }
    }


    @Test
    fun sharedPdfOpensForEditingEvenWhenLegacyAutoProcessingIsEnabled() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val file = File(app.repository.exports, "feedback-shared-${UUID.randomUUID()}.pdf")
        PDDocument().use {
            it.addPage(PDPage())
            it.save(file)
        }
        val previous = UserSettings[SettingKeys.ShareProcess]
        UserSettings.set(SettingKeys.ShareProcess, true)
        var document: DocumentRow? = null
        val intent =
            Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(
                    Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(app, "${app.packageName}.files", file),
                )
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
                withTimeout(20_000) {
                    while (
                        model.selected.value?.title?.contains("feedback-shared") != true ||
                            model.screen.value != "reader"
                    ) delay(100)
                }
                document = model.selected.value
                assertEquals("pdf", document!!.kind)
                assertTrue(File(document!!.pdfPath!!).isFile)
            }
        } finally {
            document?.let { app.repository.deleteDocument(it) }
            file.delete()
            UserSettings.set(SettingKeys.ShareProcess, previous)
        }
    }
}

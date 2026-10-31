package cn.paperforge.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFolderRecoveryTest {
    @Test
    fun emptyFoldersMoveRenameRemoveAndUndoPreserveDocuments() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val preferences = LibraryPreferences(app)
        val originalPaths = preferences.folders()
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        fun main(action: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        main {
            vm =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    MainViewModel::class.java]
        }
        val repo = app.repository
        val id = UUID.randomUUID().toString()
        val base = "folder-recovery-$id"
        val renamed = "$base-renamed"
        suspend fun action(block: () -> Unit) {
            main(block)
            withTimeout(10_000) { vm.busy.first { !it } }
        }
        try {
            action { vm.createLibraryFolder("$base/child") }
            assertTrue(
                documentGroups(emptyList(), preferences.folders()).any {
                    it.key == base && it.count == 0
                }
            )
            repo.dao.putDocument(DocumentRow(id, "Folder recovery document"))
            action { vm.categorizeDocuments(setOf(id), "$base/child", null) }
            assertEquals("$base/child", repo.dao.document(id)!!.folder)
            action { vm.undoLibraryFolder() }
            assertEquals("", repo.dao.document(id)!!.folder)
            action { vm.categorizeDocuments(setOf(id), "$base/child", null) }
            action { vm.changeLibraryFolder(base, renamed) }
            assertEquals("$renamed/child", repo.dao.document(id)!!.folder)
            assertTrue(preferences.folders().contains("$renamed/child"))
            action { vm.changeLibraryFolder(renamed, null) }
            assertEquals("", repo.dao.document(id)!!.folder)
            assertEquals("Folder recovery document", repo.dao.document(id)!!.title)
            action { vm.undoLibraryFolder() }
            assertEquals("$renamed/child", repo.dao.document(id)!!.folder)
            val exported = preferences.export()
            preferences.folders(emptyList())
            preferences.restore(exported)
            assertTrue(preferences.folders().contains("$renamed/child"))
        } finally {
            repo.dao.deleteDocumentRaw(id)
            preferences.folders(originalPaths)
            main { store.clear() }
        }
    }
}

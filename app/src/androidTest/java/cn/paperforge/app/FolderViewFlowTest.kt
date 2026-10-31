package cn.paperforge.app

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.DocumentRow
import cn.paperforge.app.data.documentGroups
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #29: seed documents into the existing `documents.folder` column, tap a folder card on the real
 * HomeScreen, and prove the list only keeps that group. Writes the screenshot and JSON under the
 * app-private `files/folder-view/` for pulling back to `work/folder-view/`.
 */
@RunWith(AndroidJUnit4::class)
class FolderViewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tappingFolderCardFiltersLibraryAndWritesEvidence() {
        val app = compose.activity.application as PaperForgeApp
        val repo = app.repository
        val stamp = System.currentTimeMillis().toString().takeLast(6)
        val folderA = "验收29-票据-$stamp"
        val folderB = "验收29-合同-$stamp"
        val base = System.currentTimeMillis()
        val titlesA = listOf("验收票据甲-$stamp", "验收票据乙-$stamp", "验收票据丙-$stamp")
        val titleB = "验收合同-$stamp"
        val titleUnfiled = "验收未分类-$stamp"
        val created = mutableListOf<DocumentRow>()
        fun seed(folder: String, title: String, offset: Long) =
            runBlocking {
                val row = repo.newScan()
                val updated = row.copy(title = title, folder = folder, modified = base + offset)
                repo.dao.putDocument(updated)
                created += updated
                updated
            }
        try {
            // A previous interrupted run can leave test rows behind; clear only this test's titles.
            runBlocking {
                repo.dao
                    .allDocuments()
                    .filter { it.title.startsWith("验收票据") || it.title.startsWith("验收合同") || it.title.startsWith("验收未分类") }
                    .forEach { row -> runCatching { repo.deleteDocument(row) } }
            }
            titlesA.forEachIndexed { index, title -> seed(folderA, title, 10L + index) }
            seed(folderB, titleB, 3L)
            seed("", titleUnfiled, 0L)

            // The folder row lives on the 全部 (documents) tab; the recent tab keeps its own chips.
            compose.onAllNodesWithText("全部", useUnmergedTree = true).onFirst().performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("home-folder-groups").fetchSemanticsNodes().isNotEmpty()
            }
            // Prove the other groups are reachable before filtering, so their absence afterwards is
            // a real filter result rather than lazy-list virtualization.
            compose
                .onNodeWithTag("document-list")
                .performScrollToNode(hasText(titleB, substring = true))
            compose.onNodeWithText(titleB).assertIsDisplayed()
            compose.onNodeWithTag("document-list").performScrollToIndex(0)
            compose.waitForIdle()
            val cardA = "home-group-$folderA"
            compose.onNodeWithTag("home-folder-groups").performScrollToNode(hasTestTag(cardA))
            compose.waitForIdle()
            compose
                .onNodeWithTag(cardA)
                .assert(hasContentDescription("$folderA，${titlesA.size} 份文档"))
            compose.onNodeWithTag(cardA).performClick()
            compose.waitUntil(15000) {
                compose
                    .onAllNodes(
                        hasTestTag("collection-header") and hasAnyDescendant(hasText("${titlesA.size} 份")),
                        useUnmergedTree = true,
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                    compose.onAllNodesWithText(titleB).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText(titleB).assertDoesNotExist()
            compose.onNodeWithText(titleUnfiled).assertDoesNotExist()
            titlesA.forEach { title ->
                compose
                    .onNodeWithTag("document-list")
                    .performScrollToNode(hasText(title, substring = true))
                compose.onNodeWithText(title).assertIsDisplayed()
            }
            compose.onNodeWithTag("document-list").performScrollToIndex(0)
            compose.waitForIdle()

            compose.waitForIdle()
            val shot = compose.onRoot().captureToImage().asAndroidBitmap()
            val shotWidth = shot.width
            val shotHeight = shot.height
            val proof = File(app.filesDir, "folder-view").apply { mkdirs() }
            val shotFile = File(proof, "folder-filter.png")
            shotFile.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()

            // Tapping the same card again clears the group filter.
            compose.onNodeWithTag("home-folder-groups").performScrollToNode(hasTestTag(cardA))
            compose.onNodeWithTag(cardA).performClick()
            compose
                .onNodeWithTag("document-list")
                .performScrollToNode(hasText(titleB, substring = true))
            compose.onNodeWithText(titleB).assertIsDisplayed()
            val secondClickRestored = true

            // The row's 全部 card clears it as well.
            compose.onNodeWithTag("document-list").performScrollToIndex(0)
            compose.waitForIdle()
            compose
                .onNodeWithTag("home-folder-groups")
                .performScrollToNode(hasTestTag("home-group-all"))
            compose.onNodeWithTag("home-group-all").performClick()
            compose
                .onNodeWithTag("document-list")
                .performScrollToNode(hasText(titleUnfiled, substring = true))
            compose.onNodeWithText(titleUnfiled).assertIsDisplayed()
            val allCardRestored = true

            val allDocs = runBlocking { repo.dao.observeDocuments().first() }
            val groups = documentGroups(allDocs)
            val groupA = groups.single { it.key == folderA }
            val groupB = groups.single { it.key == folderB }
            val unfiled = groups.single { it.key == "" }
            val folders =
                JSONArray(
                    created.map { doc ->
                        JSONObject()
                            .put("title", doc.title)
                            .put("folder", doc.folder)
                            .put(
                                "databaseFolder",
                                runBlocking { repo.dao.document(doc.id)?.folder },
                            )
                    }
                )
            val result =
                JSONObject()
                    .put("issue", 29)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("emulator", "emulator-5554")
                    .put("groupingField", "documents.folder")
                    .put("seeded", folders)
                    .put(
                        "groupCards",
                        JSONArray(
                            listOf(
                                JSONObject().put("folder", folderA).put("count", groupA.count),
                                JSONObject().put("folder", folderB).put("count", groupB.count),
                                JSONObject().put("folder", "").put("count", unfiled.count),
                            )
                        ),
                    )
                    .put(
                        "filtered",
                        JSONObject()
                            .put("clicked", folderA)
                            .put("visibleTitles", JSONArray(titlesA))
                            .put("hiddenTitles", JSONArray(listOf(titleB, titleUnfiled)))
                            .put("countMatchesCard", groupA.count == titlesA.size),
                    )
                    .put(
                        "toggle",
                        JSONObject()
                            .put("secondClickCleared", secondClickRestored)
                            .put("allCardCleared", allCardRestored),
                    )
                    .put(
                        "screenshot",
                        JSONObject()
                            .put("file", "files/folder-view/folder-filter.png")
                            .put("bytes", shotFile.length())
                            .put("width", shotWidth)
                            .put("height", shotHeight),
                    )
            File(proof, "folder-view.json").writeText(result.toString(2))
            assertEquals(titlesA.size, groupA.count)
            assertTrue(groupB.count == 1)
            assertTrue(shotFile.length() > 0)
        } finally {
            runBlocking {
                created.forEach { row ->
                    runCatching { repo.deleteDocument(row) }
                    runCatching { repo.dao.clearHistory(row.id) }
                    runCatching { repo.dao.deleteDocument(row.id) }
                }
            }
        }
    }
}

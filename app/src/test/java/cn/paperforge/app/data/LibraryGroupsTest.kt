package cn.paperforge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupsTest {
    private fun doc(
        id: String,
        folder: String,
        modified: Long = 0L,
        title: String = id,
    ) = DocumentRow(id = id, title = title, modified = modified, folder = folder)

    @Test
    fun emptyLibraryHasNoGroups() {
        assertTrue(documentGroups(emptyList()).isEmpty())
    }

    @Test
    fun singleFolderProducesOneGroupWithExactCount() {
        val groups =
            documentGroups(listOf(doc("a", "工作/票据"), doc("b", "工作/票据")))

        // Nested paths produce one card per ancestor so the row can be navigated level by level.
        assertEquals(listOf("工作", "工作/票据"), groups.map { it.key })
        val group = groups.single { it.key == "工作/票据" }
        assertEquals("票据", group.name)
        assertEquals(2, group.count)
        assertEquals(listOf("a", "b"), group.documentIds)
        assertTrue(!group.isUnfiled)
    }

    @Test
    fun multipleFoldersAreSortedByCountThenPath() {
        val groups =
            documentGroups(
                listOf(
                    doc("a1", "甲"),
                    doc("a2", "甲"),
                    doc("b1", "乙"),
                    doc("c1", "丙"),
                    doc("c2", "丙"),
                    doc("c3", "丙"),
                )
            )

        assertEquals(listOf("丙", "甲", "乙"), groups.map { it.key })
        assertEquals(listOf(3, 2, 1), groups.map { it.count })
    }

    @Test
    fun unfiledGroupIsLastAndCountedSeparately() {
        val groups =
            documentGroups(
                listOf(
                    doc("filed", "票据"),
                    doc("free-1", ""),
                    doc("free-2", "  "),
                )
            )

        assertEquals(listOf("票据", ""), groups.map { it.key })
        val unfiled = groups.last()
        assertEquals("未分类", unfiled.name)
        assertEquals(2, unfiled.count)
        assertTrue(unfiled.isUnfiled)
    }

    @Test
    fun subfolderDocumentsCountTowardTheParentCard() {
        val groups =
            documentGroups(
                listOf(
                    doc("root", "项目"),
                    doc("invoice", "项目/票据"),
                    doc("contract", "项目/合同"),
                    doc("deep", "项目/票据/2026"),
                )
            )

        val root = groups.single { it.key == "项目" }
        val invoice = groups.single { it.key == "项目/票据" }
        assertEquals(4, root.count)
        assertEquals(2, invoice.count)
        assertEquals(setOf("root", "invoice", "contract", "deep"), root.documentIds.toSet())
        assertEquals(setOf("invoice", "deep"), invoice.documentIds.toSet())
    }

    @Test
    fun blankSegmentsAndTrailingSlashesMergeIntoOneGroup() {
        val groups =
            documentGroups(
                listOf(
                    doc("a", " 工作 / 票据 "),
                    doc("b", "工作/票据/"),
                    doc("c", "工作\\票据"),
                )
            )

        assertEquals(listOf("工作", "工作/票据"), groups.map { it.key })
        val leaf = groups.single { it.key == "工作/票据" }
        assertEquals(3, leaf.count)
        assertEquals("票据", leaf.name)
        assertEquals(3, groups.single { it.key == "工作" }.count)
    }

    @Test
    fun membersAreOrderedByModifiedAndCoverIsTheNewestDocument() {
        val groups =
            documentGroups(
                listOf(
                    doc("old", "归档", modified = 100),
                    doc("new", "归档", modified = 300),
                    doc("mid", "归档", modified = 200),
                )
            )

        val group = groups.single()
        assertEquals(listOf("new", "mid", "old"), group.documentIds)
        assertEquals("new", group.coverDocumentId)
    }

    @Test
    fun libraryWithOnlyUnfiledDocumentsHasASingleUnfiledCard() {
        val groups =
            documentGroups(listOf(doc("a", ""), doc("b", ""), doc("c", "")))

        assertEquals(1, groups.size)
        assertEquals("", groups.single().key)
        assertEquals(3, groups.single().count)
        assertEquals("a", groups.single().coverDocumentId)
        assertEquals(listOf("a", "b", "c"), groups.single().documentIds)
    }
}

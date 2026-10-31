package cn.paperforge.app.data

/**
 * One card in the document library's folder row.
 *
 * [key] is the normalised `documents.folder` path; an empty key means "未分类". [count] uses the
 * same "this folder and its subfolders" rule as the existing library folder filter, so tapping a
 * card shows exactly as many documents as the card says.
 */
data class DocumentGroup(
    val key: String,
    val name: String,
    val count: Int,
    val coverDocumentId: String?,
    val documentIds: List<String>,
) {
    val isUnfiled: Boolean get() = key.isEmpty()
}

/**
 * Groups documents by the existing `documents.folder` column. This is presentation only: no new
 * table, migration or folder tree is created. "未分类" always comes last; folder cards are ordered
 * by count and then path so the row is stable between recompositions.
 */
fun documentGroups(documents: List<DocumentRow>): List<DocumentGroup> {
    if (documents.isEmpty()) return emptyList()
    val normalised = documents.map { it to libraryFolder(it.folder) }
    val folderCards =
        normalised
            .map { (_, folder) -> folder }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { path ->
                group(
                    path,
                    normalised.filter { (_, folder) ->
                        folder == path || folder.startsWith("$path/")
                    },
                )
            }
            .sortedWith(compareByDescending<DocumentGroup> { it.count }.thenBy { it.key })
    val unfiled = normalised.filter { (_, folder) -> folder.isEmpty() }
    return if (unfiled.isEmpty()) folderCards else folderCards + group("", unfiled)
}

private fun group(key: String, members: List<Pair<DocumentRow, String>>): DocumentGroup {
    val ordered =
        members
            .map { it.first }
            .sortedWith(compareByDescending<DocumentRow> { it.modified }.thenBy { it.id })
    return DocumentGroup(
        key = key,
        name = key.substringAfterLast('/').ifEmpty { "未分类" },
        count = ordered.size,
        coverDocumentId = ordered.firstOrNull()?.id,
        documentIds = ordered.map { it.id },
    )
}

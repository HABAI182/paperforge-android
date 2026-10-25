package cn.paperforge.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.paperforge.app.model.CjkSearch
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentRow(
    @PrimaryKey val id: String,
    val title: String,
    val modified: Long = System.currentTimeMillis(),
    val kind: String = "scan",
    val pdfPath: String? = null,
    @ColumnInfo(defaultValue = "''") val folder: String = "",
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    val trashedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val lastOpened: Long = 0,
    @ColumnInfo(defaultValue = "1") val listed: Boolean = true,
)

@Entity(
    tableName = "pages",
    foreignKeys =
        [
            ForeignKey(
                entity = DocumentRow::class,
                parentColumns = ["id"],
                childColumns = ["documentId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("documentId")],
)
data class PageRow(
    @PrimaryKey val id: String,
    val documentId: String,
    val position: Int,
    val original: String,
    val corners: String = "0.0,0.0;1.0,0.0;1.0,1.0;0.0,1.0",
    val filter: String = "AUTO",
    val rotation: Int = 0,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val revision: Int = 0,
    val ocrText: String? = null,
    val ocrLayout: String? = null,
    val detectionConfidence: Float? = null,
    val detectionMethod: String? = null,
    @ColumnInfo(defaultValue = "0") val deskewDegrees: Float = 0f,
    @ColumnInfo(defaultValue = "1") val enhanceStrength: Float = 1f,
    @ColumnInfo(defaultValue = "0") val outputAspect: Float = 0f,
    @ColumnInfo(defaultValue = "'{}'") val processingMetadata: String = "{}",
)

@Entity(tableName = "edit_history", indices = [Index("documentId")])
data class EditHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val pageId: String,
    val beforeJson: String,
    val afterJson: String,
    val applied: Boolean = true,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "page_search")
data class PageSearch(val pageId: String, val documentId: String, val tokens: String)

@Dao
interface DocumentDao {
    @Query(
        "SELECT * FROM documents WHERE trashedAt IS NULL AND listed=1 ORDER BY favorite DESC,modified DESC"
    )
    fun observeDocuments(): Flow<List<DocumentRow>>

    @Query(
        "SELECT * FROM documents WHERE trashedAt IS NOT NULL AND listed=1 ORDER BY trashedAt DESC"
    )
    fun observeTrash(): Flow<List<DocumentRow>>

    @Query(
        "SELECT DISTINCT d.* FROM documents d WHERE d.trashedAt IS NULL AND d.listed=1 AND (d.title LIKE '%'||:query||'%' OR d.tags LIKE '%'||:query||'%' OR d.folder LIKE '%'||:query||'%' OR d.id IN (SELECT documentId FROM page_search WHERE page_search MATCH :expression) OR d.id IN (SELECT documentId FROM pdf_search WHERE pdf_search MATCH :expression)) ORDER BY d.favorite DESC,d.modified DESC"
    )
    fun search(
        query: String,
        expression: String = CjkSearch.expression(query),
    ): Flow<List<DocumentRow>>

    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY position")
    fun observePages(id: String): Flow<List<PageRow>>

    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY position")
    suspend fun pages(id: String): List<PageRow>

    @Query("SELECT * FROM documents WHERE id=:id") suspend fun document(id: String): DocumentRow?

    @Query("SELECT * FROM documents WHERE pdfPath=:path LIMIT 1")
    suspend fun documentByPath(path: String): DocumentRow?

    @Query("SELECT COUNT(*) FROM documents WHERE pdfPath=:path")
    suspend fun pdfReferences(path: String): Int

    @Query("SELECT * FROM documents") suspend fun allDocuments(): List<DocumentRow>

    @Query("SELECT * FROM pages") suspend fun allPages(): List<PageRow>

    @Query("SELECT * FROM edit_history ORDER BY id") suspend fun allHistory(): List<EditHistory>

    @Query(
        "SELECT p.documentId,p.position AS page,p.ocrText AS text,p.id AS pageId FROM pages p WHERE p.id IN (SELECT pageId FROM page_search WHERE page_search MATCH :expression) UNION ALL SELECT documentId,page,text,NULL AS pageId FROM pdf_search WHERE pdf_search MATCH :expression ORDER BY documentId,page"
    )
    fun searchHits(expression: String): Flow<List<LibrarySearchHit>>

    @Query(
        "SELECT d.id AS documentId,CASE WHEN d.kind='pdf' THEN COALESCE(i.pageCount,0) ELSE (SELECT COUNT(*) FROM pages p WHERE p.documentId=d.id) END AS pageCount,CASE WHEN d.kind='pdf' THEN COALESCE(i.textPages,0) ELSE (SELECT COUNT(*) FROM pages p WHERE p.documentId=d.id AND p.ocrText IS NOT NULL AND p.ocrText!='') END AS recognizedPages,EXISTS(SELECT 1 FROM document_outputs o WHERE o.sourceDocumentId=d.id) AS hasExport,COALESCE(i.annotations,0) AS annotations FROM documents d LEFT JOIN pdf_index i ON i.documentId=d.id"
    )
    fun libraryFacts(): Flow<List<LibraryFacts>>

    @Query("SELECT * FROM pdf_index WHERE documentId=:id")
    suspend fun pdfIndex(id: String): PdfIndexRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPdfIndex(row: PdfIndexRow)

    @Insert suspend fun putPdfTexts(rows: List<PdfLibraryText>)

    @Query("DELETE FROM pdf_search WHERE documentId=:id") suspend fun deletePdfTexts(id: String)

    @Query("DELETE FROM pdf_index WHERE documentId=:id") suspend fun deletePdfIndex(id: String)

    @Query("SELECT * FROM document_outputs ORDER BY created DESC")
    fun outputs(): Flow<List<OutputRow>>

    @Query("SELECT * FROM document_outputs ORDER BY created DESC")
    suspend fun allOutputs(): List<OutputRow>

    @Query("SELECT * FROM document_outputs WHERE path=:path")
    suspend fun output(path: String): OutputRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOutput(row: OutputRow)

    @Query("UPDATE document_outputs SET externalUri=:uri WHERE path=:path")
    suspend fun outputDestination(path: String, uri: String)

    @Query("DELETE FROM document_outputs WHERE path=:path") suspend fun removeOutput(path: String)

    @Query("SELECT * FROM local_tasks ORDER BY updated DESC")
    fun observeTasks(): Flow<List<LocalTaskRow>>

    @Query("SELECT * FROM local_tasks ORDER BY created,id")
    suspend fun allTasks(): List<LocalTaskRow>

    @Query("SELECT * FROM local_tasks WHERE id=:id") suspend fun task(id: String): LocalTaskRow?

    @Query("SELECT * FROM local_tasks WHERE documentId=:id ORDER BY created,id")
    suspend fun tasksForDocument(id: String): List<LocalTaskRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTask(row: LocalTaskRow)

    @Query(
        "UPDATE local_tasks SET state=:state,progress=:progress,detail=:detail,error=:error,updated=:now WHERE id=:id"
    )
    suspend fun taskState(
        id: String,
        state: LocalTaskState,
        progress: Int,
        detail: String,
        error: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query(
        "SELECT MAX(position) FROM local_tasks WHERE documentId=:id AND type='CAPTURE' AND targetPageId IS NULL AND state IN ('CAPTURING','QUEUED','RUNNING')"
    )
    suspend fun reservedCapturePosition(id: String): Int?

    @Query("SELECT COUNT(*) FROM pages WHERE original=:path")
    suspend fun originalReferences(path: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDocument(doc: DocumentRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPageRaw(page: PageRow)

    @Update suspend fun updatePageRaw(page: PageRow)

    @Insert suspend fun insertSearch(row: PageSearch)

    @Query("DELETE FROM page_search WHERE pageId=:id") suspend fun deleteSearch(id: String)

    @Query("DELETE FROM page_search WHERE documentId=:id")
    suspend fun deleteDocumentSearch(id: String)

    @Transaction
    suspend fun putPage(page: PageRow) {
        putPageRaw(page)
        index(page)
    }

    @Transaction
    suspend fun updatePage(page: PageRow) {
        updatePageRaw(page)
        index(page)
    }

    suspend fun index(page: PageRow) {
        deleteSearch(page.id)
        if (!page.ocrText.isNullOrBlank())
            insertSearch(PageSearch(page.id, page.documentId, CjkSearch.tokens(page.ocrText)))
    }

    @Update suspend fun updateDocument(doc: DocumentRow)

    @Insert suspend fun addHistory(history: EditHistory)

    @Update suspend fun updateHistory(history: EditHistory)

    @Query("SELECT * FROM edit_history WHERE documentId=:id AND applied=1 ORDER BY id DESC LIMIT 1")
    suspend fun undoEntry(id: String): EditHistory?

    @Query("SELECT * FROM edit_history WHERE documentId=:id AND applied=0 ORDER BY id ASC LIMIT 1")
    suspend fun redoEntry(id: String): EditHistory?

    @Query("SELECT * FROM edit_history WHERE documentId=:id AND applied=0")
    suspend fun redoEntries(id: String): List<EditHistory>

    @Query("SELECT * FROM edit_history WHERE pageId=:key ORDER BY id")
    suspend fun linkedHistory(key: String): List<EditHistory>

    @Query("DELETE FROM edit_history WHERE documentId=:id AND applied=0")
    suspend fun clearRedoRaw(id: String)

    @Transaction
    suspend fun clearRedo(id: String) {
        val pending = java.util.ArrayDeque<String>().apply { add(id) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val document = pending.removeFirst()
            if (!visited.add(document)) continue
            redoEntries(document)
                .filter { HistoryState.isTransfer(it.beforeJson) }
                .forEach { entry ->
                    linkedHistory(entry.pageId)
                        .filter { !it.applied }
                        .forEach { pending.add(it.documentId) }
                }
            clearRedoRaw(document)
        }
    }

    @Query("DELETE FROM edit_history WHERE documentId=:id") suspend fun clearHistory(id: String)

    @Query("UPDATE documents SET title=:title, modified=:now WHERE id=:id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE documents SET lastOpened=:now WHERE id=:id")
    suspend fun opened(id: String, now: Long)

    @Query("UPDATE documents SET modified=:now WHERE id=:id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM pages WHERE id=:id") suspend fun deletePageRaw(id: String)

    @Query("DELETE FROM documents WHERE id=:id") suspend fun deleteDocumentRaw(id: String)

    @Transaction
    suspend fun deletePage(id: String) {
        deleteSearch(id)
        deletePageRaw(id)
    }

    @Transaction
    suspend fun deleteDocument(id: String) {
        deleteDocumentSearch(id)
        deletePdfTexts(id)
        deletePdfIndex(id)
        deleteDocumentRaw(id)
    }
}

@Database(
    entities =
        [
            DocumentRow::class,
            PageRow::class,
            EditHistory::class,
            PageSearch::class,
            PdfLibraryText::class,
            PdfIndexRow::class,
            OutputRow::class,
            LocalTaskRow::class,
        ],
    version = 11,
    exportSchema = true,
)
@TypeConverters(TaskConverters::class)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun dao(): DocumentDao

    companion object {
        val Migration10To11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS local_tasks (id TEXT NOT NULL PRIMARY KEY,type TEXT NOT NULL,documentId TEXT,title TEXT NOT NULL,pageId TEXT,targetPageId TEXT,position INTEGER NOT NULL,state TEXT NOT NULL,progress INTEGER NOT NULL,detail TEXT NOT NULL,payload TEXT NOT NULL,resources TEXT NOT NULL,error TEXT,workId TEXT,created INTEGER NOT NULL,updated INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_local_tasks_documentId ON local_tasks(documentId)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_local_tasks_state ON local_tasks(state)"
                    )
                }
            }
        val Migration9To10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE documents ADD COLUMN listed INTEGER NOT NULL DEFAULT 1")
                }
            }
        val Migration8To9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS pdf_search USING FTS4(documentId TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,tokens TEXT NOT NULL,tokenize=unicode61)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS pdf_index (documentId TEXT NOT NULL PRIMARY KEY,fingerprint TEXT NOT NULL,pageCount INTEGER NOT NULL,textPages INTEGER NOT NULL,annotations INTEGER NOT NULL,updated INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS document_outputs (path TEXT NOT NULL PRIMARY KEY,sourceDocumentId TEXT,sourceTitle TEXT NOT NULL,format TEXT NOT NULL,created INTEGER NOT NULL,externalUri TEXT,outputDocumentId TEXT)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_document_outputs_sourceDocumentId ON document_outputs(sourceDocumentId)"
                    )
                }
            }
        val Migration7To8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE pages ADD COLUMN outputAspect REAL NOT NULL DEFAULT 0")
                    db.execSQL(
                        "ALTER TABLE pages ADD COLUMN processingMetadata TEXT NOT NULL DEFAULT '{}'"
                    )
                }
            }
        val Migration6To7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE documents ADD COLUMN lastOpened INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }

        val Migration5To6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE pages ADD COLUMN enhanceStrength REAL NOT NULL DEFAULT 1"
                    )
                }
            }
        val Migration4To5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS page_search USING FTS4(pageId TEXT NOT NULL, documentId TEXT NOT NULL, tokens TEXT NOT NULL, tokenize=unicode61)"
                    )
                    db.query("SELECT id,documentId,ocrText FROM pages WHERE ocrText IS NOT NULL")
                        .use { cursor ->
                            while (cursor.moveToNext()) db.execSQL(
                                "INSERT INTO page_search(pageId,documentId,tokens) VALUES(?,?,?)",
                                arrayOf(
                                    cursor.getString(0),
                                    cursor.getString(1),
                                    CjkSearch.tokens(cursor.getString(2)),
                                ),
                            )
                        }
                }
            }
        val Migration3To4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE pages ADD COLUMN deskewDegrees REAL NOT NULL DEFAULT 0")
                }
            }
        val Migration2To3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE pages ADD COLUMN detectionConfidence REAL")
                    db.execSQL("ALTER TABLE pages ADD COLUMN detectionMethod TEXT")
                }
            }
        val Migration1To2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE documents ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE documents ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                    db.execSQL(
                        "ALTER TABLE documents ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("ALTER TABLE documents ADD COLUMN trashedAt INTEGER")
                    db.execSQL("ALTER TABLE pages ADD COLUMN ocrLayout TEXT")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS edit_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,documentId TEXT NOT NULL,pageId TEXT NOT NULL,beforeJson TEXT NOT NULL,afterJson TEXT NOT NULL,applied INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_edit_history_documentId ON edit_history(documentId)"
                    )
                }
            }
    }
}

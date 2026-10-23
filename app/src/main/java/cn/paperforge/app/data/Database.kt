package cn.paperforge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName="documents")
data class DocumentRow(@PrimaryKey val id: String, val title: String, val modified: Long=System.currentTimeMillis(), val kind: String="scan", val pdfPath: String?=null,
    @ColumnInfo(defaultValue="''") val folder: String="", @ColumnInfo(defaultValue="''") val tags: String="",
    @ColumnInfo(defaultValue="0") val favorite: Boolean=false, val trashedAt: Long?=null)

@Entity(tableName="pages", foreignKeys=[ForeignKey(entity=DocumentRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.CASCADE)], indices=[Index("documentId")])
data class PageRow(@PrimaryKey val id: String, val documentId: String, val position: Int, val original: String,
    val corners: String="0.0,0.0;1.0,0.0;1.0,1.0;0.0,1.0", val filter: String="AUTO", val rotation: Int=0,
    val brightness: Float=0f, val contrast: Float=1f, val revision: Int=0, val ocrText: String?=null,val ocrLayout: String?=null)

@Entity(tableName="edit_history",indices=[Index("documentId")])
data class EditHistory(@PrimaryKey(autoGenerate=true) val id: Long=0,val documentId: String,val pageId: String,val beforeJson: String,val afterJson: String,val applied: Boolean=true)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE trashedAt IS NULL ORDER BY favorite DESC,modified DESC") fun observeDocuments(): Flow<List<DocumentRow>>
    @Query("SELECT * FROM documents WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC") fun observeTrash(): Flow<List<DocumentRow>>
    @Query("SELECT DISTINCT d.* FROM documents d LEFT JOIN pages p ON d.id=p.documentId WHERE d.trashedAt IS NULL AND (d.title LIKE '%'||:query||'%' OR d.tags LIKE '%'||:query||'%' OR d.folder LIKE '%'||:query||'%' OR p.ocrText LIKE '%'||:query||'%') ORDER BY d.favorite DESC,d.modified DESC") fun search(query: String): Flow<List<DocumentRow>>
    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY position") fun observePages(id: String): Flow<List<PageRow>>
    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY position") suspend fun pages(id: String): List<PageRow>
    @Query("SELECT * FROM documents WHERE id=:id") suspend fun document(id: String): DocumentRow?
    @Query("SELECT * FROM documents WHERE pdfPath=:path LIMIT 1") suspend fun documentByPath(path: String): DocumentRow?
    @Query("SELECT COUNT(*) FROM documents WHERE pdfPath=:path") suspend fun pdfReferences(path: String): Int
    @Query("SELECT * FROM documents") suspend fun allDocuments(): List<DocumentRow>
    @Query("SELECT * FROM pages") suspend fun allPages(): List<PageRow>
    @Query("SELECT * FROM edit_history ORDER BY id") suspend fun allHistory(): List<EditHistory>
    @Query("SELECT COUNT(*) FROM pages WHERE original=:path") suspend fun originalReferences(path: String): Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putDocument(doc: DocumentRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPage(page: PageRow)
    @Update suspend fun updatePage(page: PageRow)
    @Update suspend fun updateDocument(doc: DocumentRow)
    @Insert suspend fun addHistory(history: EditHistory)
    @Update suspend fun updateHistory(history: EditHistory)
    @Query("SELECT * FROM edit_history WHERE documentId=:id AND applied=1 ORDER BY id DESC LIMIT 1") suspend fun undoEntry(id: String): EditHistory?
    @Query("SELECT * FROM edit_history WHERE documentId=:id AND applied=0 ORDER BY id ASC LIMIT 1") suspend fun redoEntry(id: String): EditHistory?
    @Query("DELETE FROM edit_history WHERE documentId=:id AND applied=0") suspend fun clearRedo(id: String)
    @Query("DELETE FROM edit_history WHERE documentId=:id") suspend fun clearHistory(id: String)
    @Query("UPDATE documents SET title=:title, modified=:now WHERE id=:id") suspend fun rename(id: String,title: String,now: Long)
    @Query("UPDATE documents SET modified=:now WHERE id=:id") suspend fun touch(id: String,now: Long)
    @Query("DELETE FROM pages WHERE id=:id") suspend fun deletePage(id: String)
    @Query("DELETE FROM documents WHERE id=:id") suspend fun deleteDocument(id: String)
}

@Database(entities=[DocumentRow::class,PageRow::class,EditHistory::class],version=2,exportSchema=true)
abstract class PaperDatabase: RoomDatabase() {
    abstract fun dao(): DocumentDao
    companion object {
        val Migration1To2=object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN trashedAt INTEGER")
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrLayout TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS edit_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,documentId TEXT NOT NULL,pageId TEXT NOT NULL,beforeJson TEXT NOT NULL,afterJson TEXT NOT NULL,applied INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_edit_history_documentId ON edit_history(documentId)")
            }
        }
    }
}

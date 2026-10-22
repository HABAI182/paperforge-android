package cn.paperforge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="documents")
data class DocumentRow(@PrimaryKey val id: String, val title: String, val modified: Long=System.currentTimeMillis(), val kind: String="scan", val pdfPath: String?=null)

@Entity(tableName="pages", foreignKeys=[ForeignKey(entity=DocumentRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.CASCADE)], indices=[Index("documentId")])
data class PageRow(@PrimaryKey val id: String, val documentId: String, val position: Int, val original: String,
    val corners: String="0.0,0.0;1.0,0.0;1.0,1.0;0.0,1.0", val filter: String="AUTO", val rotation: Int=0,
    val brightness: Float=0f, val contrast: Float=1f, val revision: Int=0, val ocrText: String?=null)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY modified DESC") fun observeDocuments(): Flow<List<DocumentRow>>
    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY position") fun observePages(id: String): Flow<List<PageRow>>
    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY position") suspend fun pages(id: String): List<PageRow>
    @Query("SELECT * FROM documents WHERE id=:id") suspend fun document(id: String): DocumentRow?
    @Query("SELECT * FROM documents WHERE pdfPath=:path LIMIT 1") suspend fun documentByPath(path: String): DocumentRow?
    @Query("SELECT COUNT(*) FROM documents WHERE pdfPath=:path") suspend fun pdfReferences(path: String): Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putDocument(doc: DocumentRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPage(page: PageRow)
    @Update suspend fun updatePage(page: PageRow)
    @Query("UPDATE documents SET title=:title, modified=:now WHERE id=:id") suspend fun rename(id: String,title: String,now: Long)
    @Query("UPDATE documents SET modified=:now WHERE id=:id") suspend fun touch(id: String,now: Long)
    @Query("DELETE FROM pages WHERE id=:id") suspend fun deletePage(id: String)
    @Query("DELETE FROM documents WHERE id=:id") suspend fun deleteDocument(id: String)
}

@Database(entities=[DocumentRow::class,PageRow::class],version=1,exportSchema=true)
abstract class PaperDatabase: RoomDatabase() { abstract fun dao(): DocumentDao }

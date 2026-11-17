package cn.paperforge.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.DocumentStatus
import cn.paperforge.app.data.PaperDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Updating from 2.0.1 keeps the library: a database exactly as 2.0.1 leaves it (schema 11, created from Room's exported
 * schema, rows written the way 2.0.1 writes them) is opened through the app's own migration list. Room then checks
 * every table against the current entities, so a migration that drops, renames or mistypes anything fails here.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeTest {
    private val v11 =
        listOf(
            "CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `modified` INTEGER NOT NULL, `kind` TEXT NOT NULL, `pdfPath` TEXT, `folder` TEXT NOT NULL DEFAULT '', `tags` TEXT NOT NULL DEFAULT '', `favorite` INTEGER NOT NULL DEFAULT 0, `trashedAt` INTEGER, `lastOpened` INTEGER NOT NULL DEFAULT 0, `listed` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `pages` (`id` TEXT NOT NULL, `documentId` TEXT NOT NULL, `position` INTEGER NOT NULL, `original` TEXT NOT NULL, `corners` TEXT NOT NULL, `filter` TEXT NOT NULL, `rotation` INTEGER NOT NULL, `brightness` REAL NOT NULL, `contrast` REAL NOT NULL, `revision` INTEGER NOT NULL, `ocrText` TEXT, `ocrLayout` TEXT, `detectionConfidence` REAL, `detectionMethod` TEXT, `deskewDegrees` REAL NOT NULL DEFAULT 0, `enhanceStrength` REAL NOT NULL DEFAULT 1, `outputAspect` REAL NOT NULL DEFAULT 0, `processingMetadata` TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(`id`), FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_pages_documentId` ON `pages` (`documentId`)",
            "CREATE TABLE IF NOT EXISTS `edit_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` TEXT NOT NULL, `pageId` TEXT NOT NULL, `beforeJson` TEXT NOT NULL, `afterJson` TEXT NOT NULL, `applied` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_edit_history_documentId` ON `edit_history` (`documentId`)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `page_search` USING FTS4(`pageId` TEXT NOT NULL, `documentId` TEXT NOT NULL, `tokens` TEXT NOT NULL, tokenize=unicode61)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `pdf_search` USING FTS4(`documentId` TEXT NOT NULL, `page` INTEGER NOT NULL, `text` TEXT NOT NULL, `tokens` TEXT NOT NULL, tokenize=unicode61)",
            "CREATE TABLE IF NOT EXISTS `pdf_index` (`documentId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, `textPages` INTEGER NOT NULL, `annotations` INTEGER NOT NULL, `updated` INTEGER NOT NULL, PRIMARY KEY(`documentId`))",
            "CREATE TABLE IF NOT EXISTS `document_outputs` (`path` TEXT NOT NULL, `sourceDocumentId` TEXT, `sourceTitle` TEXT NOT NULL, `format` TEXT NOT NULL, `created` INTEGER NOT NULL, `externalUri` TEXT, `outputDocumentId` TEXT, PRIMARY KEY(`path`))",
            "CREATE INDEX IF NOT EXISTS `index_document_outputs_sourceDocumentId` ON `document_outputs` (`sourceDocumentId`)",
            "CREATE TABLE IF NOT EXISTS `local_tasks` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `documentId` TEXT, `title` TEXT NOT NULL, `pageId` TEXT, `targetPageId` TEXT, `position` INTEGER NOT NULL, `state` TEXT NOT NULL, `progress` INTEGER NOT NULL, `detail` TEXT NOT NULL, `payload` TEXT NOT NULL, `resources` TEXT NOT NULL, `error` TEXT, `workId` TEXT, `created` INTEGER NOT NULL, `updated` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_local_tasks_documentId` ON `local_tasks` (`documentId`)",
            "CREATE INDEX IF NOT EXISTS `index_local_tasks_state` ON `local_tasks` (`state`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '6f35be0b9ce6e8e399940274cdc7d891')",
        )

    @Test
    fun libraryFrom201SurvivesTheUpdate() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "upgrade-test-201.db"
        app.deleteDatabase(name)
        val file = app.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v11.forEach(db::execSQL)
            db.execSQL("INSERT INTO documents(id,title,modified,kind,pdfPath,folder,tags,favorite,trashedAt,lastOpened,listed) VALUES('d-pdf','期中试卷',1700000000000,'pdf','/data/x/document.pdf','课程','数学,期中',1,NULL,1700000001000,1)")
            db.execSQL("INSERT INTO documents(id,title,modified,kind,pdfPath,folder,tags,favorite,trashedAt,lastOpened,listed) VALUES('d-scan','课堂笔记',1700000002000,'scan',NULL,'','笔记',0,NULL,0,1)")
            db.execSQL("INSERT INTO documents(id,title,modified,kind,pdfPath,folder,tags,favorite,trashedAt,lastOpened,listed) VALUES('d-trash','旧文件',1700000003000,'pdf','/data/x/old.pdf','','',0,1700000004000,0,0)")
            db.execSQL("INSERT INTO pages(id,documentId,position,original,corners,filter,rotation,brightness,contrast,revision,ocrText,deskewDegrees,enhanceStrength,outputAspect,processingMetadata) VALUES('p1','d-scan',0,'/data/x/p1.jpg','0.05,0.04;0.95,0.04;0.95,0.96;0.05,0.96','AUTO',0,0.0,1.0,0,'return 0;',0,1,0,'{}')")
            db.execSQL("INSERT INTO pages(id,documentId,position,original,corners,filter,rotation,brightness,contrast,revision,deskewDegrees,enhanceStrength,outputAspect,processingMetadata) VALUES('p2','d-scan',1,'/data/x/p2.png','0.0,0.0;1.0,0.0;1.0,1.0;0.0,1.0','AUTO',90,0.1,1.2,2,1.5,0.8,1.414,'{\"k\":1}')")
            db.version = 11
        }
        // Every migration PaperDatabase defines, as the app registers them all (PaperForgeApp).
        val migrations =
            PaperDatabase.Companion::class.java.declaredMethods
                .filter { it.name.startsWith("getMigration") && it.parameterCount == 0 }
                .map { it.invoke(PaperDatabase.Companion) as androidx.room.migration.Migration }
                .sortedBy { it.startVersion }
        assertEquals((1..11).toList(), migrations.map { it.startVersion })
        val room = Room.databaseBuilder(app, PaperDatabase::class.java, name).addMigrations(*migrations.toTypedArray()).build()
        try {
            runBlocking {
                val dao = room.dao()
                val documents = dao.allDocuments().associateBy { it.id }
                assertEquals(setOf("d-pdf", "d-scan", "d-trash"), documents.keys)
                documents.getValue("d-pdf").let {
                    assertEquals("期中试卷", it.title)
                    assertEquals("pdf", it.kind)
                    assertEquals("/data/x/document.pdf", it.pdfPath)
                    assertEquals("课程", it.folder)
                    assertEquals("数学,期中", it.tags)
                    assertTrue(it.favorite)
                    assertEquals(1700000001000L, it.lastOpened)
                    assertEquals("", it.statuses)
                    assertTrue(DocumentStatus.of(it).isEmpty())
                }
                documents.getValue("d-trash").let {
                    assertEquals(1700000004000L, it.trashedAt)
                    assertFalse(it.listed)
                }
                val pages = dao.pages("d-scan").sortedBy { it.position }
                assertEquals(listOf("p1", "p2"), pages.map { it.id })
                assertEquals("return 0;", pages[0].ocrText)
                assertEquals("0.05,0.04;0.95,0.04;0.95,0.96;0.05,0.96", pages[0].corners)
                assertEquals(90, pages[1].rotation)
                assertEquals(1.2f, pages[1].contrast)
                assertEquals(2, pages[1].revision)
                assertEquals(1.414f, pages[1].outputAspect)
                assertEquals("{\"k\":1}", pages[1].processingMetadata)
            }
            room.openHelper.readableDatabase.query("PRAGMA user_version").use { c ->
                c.moveToFirst()
                assertEquals(12, c.getInt(0))
            }
        } finally {
            room.close()
            app.deleteDatabase(name)
        }
    }
}

package cn.paperforge.app

import android.app.Application
import androidx.room.Room
import cn.paperforge.app.data.PaperDatabase
import cn.paperforge.app.data.Repository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader

class PaperForgeApp : Application() {
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ocrEngine: cn.paperforge.app.ocr.OcrEngine by lazy {
        cn.paperforge.app.ocr.BundledOcrEngine()
    }
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        UserSettings.initialize(this)
        cn.paperforge.app.imaging.LearnedDocumentDetector.initialize(this)
        cn.paperforge.app.imaging.ScanModel.initialize(this)
        PDFBoxResourceLoader.init(this)
        OpenCVLoader.initLocal()
        cn.paperforge.app.imaging.ScanConfig.load(assets)
        repository =
            Repository(
                this,
                Room.databaseBuilder(this, PaperDatabase::class.java, "paperforge.db")
                    .addMigrations(
                        PaperDatabase.Migration1To2,
                        PaperDatabase.Migration2To3,
                        PaperDatabase.Migration3To4,
                        PaperDatabase.Migration4To5,
                        PaperDatabase.Migration5To6,
                        PaperDatabase.Migration6To7,
                        PaperDatabase.Migration7To8,
                        PaperDatabase.Migration8To9,
                        PaperDatabase.Migration9To10,
                        PaperDatabase.Migration10To11,
                    )
                    .build(),
            )
        backgroundScope.launch {
            runCatching { cn.paperforge.app.imaging.CaptureTasks.recover(repository) }
                .onFailure {
                    android.util.Log.e("PaperForgeTasks", "Pending capture recovery failed", it)
                }
        }
    }
}

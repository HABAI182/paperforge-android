package cn.paperforge.app

import android.app.Application
import androidx.room.Room
import cn.paperforge.app.data.PaperDatabase
import cn.paperforge.app.data.Repository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class PaperForgeApp: Application() {
    lateinit var repository: Repository; private set
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        OpenCVLoader.initLocal()
        repository=Repository(this,Room.databaseBuilder(this,PaperDatabase::class.java,"paperforge.db").build())
    }
}

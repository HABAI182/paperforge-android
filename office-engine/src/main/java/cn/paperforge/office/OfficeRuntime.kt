package cn.paperforge.office

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office

/** All calls into one LOK instance stay on its owning thread. */
internal object OfficeRuntime {
    val executor = Executors.newSingleThreadExecutor()
    private var office: Office? = null
    val formats = setOf("pdf", "docx", "odt", "rtf", "xlsx", "ods", "csv", "pptx", "odp")

    fun convert(
        context: Context,
        input: File,
        output: File,
        format: String,
        progress: (Int, String) -> Unit,
    ) {
        require(format in formats) { "目标格式不支持" }
        progress(10, "准备手机排版引擎")
        val preferences = context.getSharedPreferences("engine", 0)
        RandomAccessFile(File(context.filesDir, "runtime-install.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                if (!preferences.getBoolean("runtime-26.2.6.3", false)) {
                    fun copy(path: String, destination: File) {
                        val children = context.assets.list(path).orEmpty()
                        if (children.isEmpty()) {
                            destination.parentFile!!.mkdirs()
                            context.assets.open(path).use { source ->
                                destination.outputStream().use { source.copyTo(it) }
                            }
                        } else {
                            destination.mkdirs()
                            children.forEach { copy("$path/$it", File(destination, it)) }
                        }
                    }
                    copy("unpack", File(context.applicationInfo.dataDir))
                    check(preferences.edit().putBoolean("runtime-26.2.6.3", true).commit())
                }
                // Share the app's canonical CJK font instead of storing a second APK copy.
                // Existing standalone runtimes already unpacked this file; keep that path valid.
                val font =
                    File(context.applicationInfo.dataDir, "user/fonts/PaperForgeCJK-Regular.ttf")
                if (!font.isFile) {
                    font.parentFile!!.mkdirs()
                    context.assets.open("fonts/PaperForgeCJK-Regular.ttf").use { source ->
                        val part = File(font.parentFile, "${font.name}.part")
                        part.outputStream().use { source.copyTo(it) }
                        check(part.renameTo(font)) { "无法准备中文排版字体" }
                    }
                }
            }
        }
        LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts")
        LibreOfficeKit.init(context)
        val runtime =
            office ?: Office(LibreOfficeKit.getLibreOfficeKitHandle()).also { office = it }
        progress(30, "正在手机上排版并转换")
        val document =
            runtime.documentLoad(input.absolutePath) ?: error(runtime.error ?: "无法打开此 Office 文件")
        try {
            document.initializeForRendering()
            document.saveAs(Uri.fromFile(output).toString(), format, "")
        } finally {
            document.destroy()
        }
        require(output.isFile && output.length() > 0) { runtime.error ?: "转换未生成结果，请检查文件格式和密码" }
        progress(90, "接收转换结果")
    }
}

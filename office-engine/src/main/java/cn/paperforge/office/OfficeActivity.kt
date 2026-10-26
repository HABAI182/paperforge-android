package cn.paperforge.office

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

class OfficeActivity : Activity() {
    private lateinit var status: TextView
    private var jobStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(40, 60, 40, 60)
                setBackgroundColor(Color.rgb(246, 248, 244))
            }
        column.addView(
            TextView(this).apply {
                text = "纸匠 Office 手机引擎"
                textSize = 26f
                setTextColor(Color.rgb(23, 107, 84))
            }
        )
        status =
            TextView(this).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
        column.addView(status)
        setContentView(column)
        if (intent.action == "cn.paperforge.office.CONVERT" && intent.data != null) {
            column.addView(ProgressBar(this))
            convert(intent.data!!, intent.getStringExtra("format") ?: "pdf")
        } else {
            status.text =
                "引擎已安装。回到纸匠 → 转换中心，选择 Office 转换。\n\n完全在手机本机运行，无需电脑或网络。\n引擎：LibreOfficeKit 26.2.6.3"
            column.addView(
                Button(this).apply {
                    text = "返回纸匠"
                    setOnClickListener {
                        packageManager
                            .getLaunchIntentForPackage("cn.paperforge.app")
                            ?.let(::startActivity)
                        finish()
                    }
                }
            )
        }
    }

    private fun convert(uri: Uri, format: String) {
        if (jobStarted) return
        jobStarted = true
        status.text = "正在准备手机排版引擎…"
        OfficeRuntime.executor.execute {
            try {
                require(
                    format in
                        setOf("pdf", "docx", "odt", "rtf", "xlsx", "ods", "csv", "pptx", "odp")
                ) {
                    "目标格式不支持"
                }
                val name =
                    if (uri.scheme == "file") File(uri.path!!).name
                    else
                        contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?: "input.docx"
                val extension =
                    name.substringAfterLast('.', "docx").lowercase().filter { it.isLetterOrDigit() }
                val jobs = File(cacheDir, "jobs").apply { mkdirs() }
                jobs
                    .listFiles()
                    ?.filter {
                        it.isDirectory &&
                            System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000
                    }
                    ?.forEach { it.deleteRecursively() }
                val directory = File(jobs, UUID.randomUUID().toString()).apply { mkdirs() }
                val input = File(directory, "input.$extension")
                contentResolver.openInputStream(uri)!!.use { source ->
                    input.outputStream().use { source.copyTo(it) }
                }
                val safeName =
                    name.substringBeforeLast('.').replace('/', '_').replace('\\', '_').take(80)
                val output = File(directory, "PaperForge-$safeName.$format")
                try {
                    OfficeRuntime.convert(this, input, output, format) { _, detail ->
                        runOnUiThread { status.text = detail }
                    }
                } finally {
                    input.delete()
                }
                val result = FileProvider.getUriForFile(this, "cn.paperforge.office.files", output)
                runOnUiThread {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .setData(result)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            .apply { clipData = ClipData.newRawUri("Office 转换结果", result) },
                    )
                    finish()
                }
            } catch (error: Throwable) {
                android.util.Log.e("PaperForgeOffice", "Conversion failed", error)
                runOnUiThread {
                    status.text = "转换未完成：${error.message}"
                    setResult(RESULT_CANCELED, Intent().putExtra("error", error.message))
                    (status.parent as LinearLayout).addView(
                        Button(this).apply {
                            text = "返回"
                            setOnClickListener { finish() }
                        }
                    )
                }
            }
        }
    }
}

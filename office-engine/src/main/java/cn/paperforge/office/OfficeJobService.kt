package cn.paperforge.office

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.File
import java.util.UUID

/** Signature-permission RPC; file descriptors keep source and result in the caller's ownership. */
class OfficeJobService : Service() {
    private var active: String? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())
    private val messenger =
        Messenger(
            Handler(Looper.getMainLooper()) { request ->
                if (request.what == 5) {
                    if (active == request.data.getString("id")) Process.killProcess(Process.myPid())
                } else if (request.what == 1) begin(request)
                true
            }
        )

    override fun onBind(intent: Intent): IBinder {
        bound = true
        return messenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        bound = false
        // Native saveAs is synchronous; a dedicated process makes cancellation release it promptly.
        if (active != null) Process.killProcess(Process.myPid()) else releaseIdle()
        return false
    }

    private fun releaseIdle() {
        main.postDelayed(
            { if (!bound && active == null) Process.killProcess(Process.myPid()) },
            1500,
        )
    }

    @Suppress("DEPRECATION")
    private fun begin(request: Message) {
        val reply = request.replyTo ?: return
        val id = request.data.getString("id") ?: return
        fun send(code: Int, percent: Int = 0, detail: String = "") {
            runCatching {
                reply.send(
                    Message.obtain(null, code).apply {
                        data =
                            Bundle().apply {
                                putString("id", id)
                                putInt("progress", percent)
                                putString("detail", detail)
                                putInt("protocol", 1)
                            }
                    }
                )
            }
        }
        val input = request.data.getParcelable<ParcelFileDescriptor>("input")
        val output = request.data.getParcelable<ParcelFileDescriptor>("output")
        if (active != null || input == null || output == null) {
            input?.close()
            output?.close()
            send(4, detail = "Office 引擎正忙或请求文件缺失")
            return
        }
        active = id
        val format = request.data.getString("format") ?: "pdf"
        val extension =
            request.data
                .getString("extension", "docx")
                .filter { it.isLetterOrDigit() }
                .take(12)
                .ifBlank { "docx" }
        OfficeRuntime.executor.execute {
            val jobs = File(cacheDir, "worker-jobs").apply { mkdirs() }
            // No other conversion runs in this process; old work directories are disposable.
            jobs.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
            val directory = File(jobs, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                val source = File(directory, "input.$extension")
                ParcelFileDescriptor.AutoCloseInputStream(input).use { original ->
                    source.outputStream().use { original.copyTo(it) }
                }
                val result = File(directory, "output.$format")
                OfficeRuntime.convert(this, source, result, format) { percent, detail ->
                    send(2, percent, detail)
                }
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { target ->
                    result.inputStream().use { it.copyTo(target) }
                    target.flush()
                }
                send(3, 100, "转换完成")
            } catch (error: Throwable) {
                send(4, detail = error.message ?: "Office 转换失败")
            } finally {
                runCatching { input.close() }
                runCatching { output.close() }
                directory.deleteRecursively()
                main.post {
                    active = null
                    releaseIdle()
                }
            }
        }
    }
}

package cn.paperforge.app.pdf

import android.content.*
import android.os.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Optional on-phone LOK service. No activity or external URI permission must stay alive. */
object OfficeBridge {
    suspend fun convert(
        context: Context,
        id: String,
        source: File,
        output: FileOutputStream,
        format: String,
        progress: suspend (Int, String) -> Unit,
    ) = coroutineScope {
        val updates = Channel<Pair<Int, String>>(Channel.CONFLATED)
        val reporter = launch { for ((percent, detail) in updates) progress(percent, detail) }
        var prepared = source
        try {
            prepared =
                withContext(Dispatchers.IO) {
                    DxfPreflight.prepare(
                        source,
                        File(
                            context.cacheDir,
                            "office-dxf/${java.util.UUID.randomUUID()}/input.dxf",
                        ),
                    )
                }
            if (prepared != source) progress(5, "已补齐 DXF 图纸范围，原件保留")
            request(context, id, prepared, output, format) { percent, detail ->
                updates.trySend(percent to detail)
            }
        } finally {
            withContext(NonCancellable) {
                updates.close()
                reporter.cancelAndJoin()
                if (prepared != source)
                    withContext(Dispatchers.IO) {
                        prepared.delete()
                        prepared.parentFile?.delete()
                    }
            }
        }
    }

    private suspend fun request(
        context: Context,
        id: String,
        source: File,
        output: FileOutputStream,
        format: String,
        progress: (Int, String) -> Unit,
    ) =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val main = Handler(Looper.getMainLooper())
                val done = AtomicBoolean(false)
                var bound = false
                var remote: Messenger? = null
                var inputFd: ParcelFileDescriptor? = null
                var outputFd: ParcelFileDescriptor? = null
                lateinit var connection: ServiceConnection
                fun cleanup() {
                    if (bound) {
                        runCatching { context.unbindService(connection) }
                        bound = false
                    }
                    runCatching { inputFd?.close() }
                    runCatching { outputFd?.close() }
                }
                fun finish(error: Throwable? = null) {
                    if (!done.compareAndSet(false, true)) return
                    cleanup()
                    if (continuation.isActive) {
                        if (error == null) continuation.resume(Unit)
                        else continuation.resumeWithException(error)
                    }
                }
                val reply =
                    Messenger(
                        Handler(Looper.getMainLooper()) { response ->
                            if (response.data.getString("id") == id)
                                when (response.what) {
                                    2 ->
                                        progress(
                                            response.data.getInt("progress"),
                                            response.data.getString("detail", "手机 Office 转换"),
                                        )
                                    3 ->
                                        if (response.data.getInt("protocol") == 1) finish()
                                        else finish(IllegalStateException("请更新 Office 手机引擎"))
                                    4 ->
                                        finish(
                                            IllegalStateException(
                                                response.data.getString("detail", "Office 转换未完成")
                                            )
                                        )
                                }
                            true
                        }
                    )
                connection =
                    object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (done.get()) return
                            try {
                                remote = Messenger(binder)
                                remote!!.send(
                                    Message.obtain(null, 1).apply {
                                        replyTo = reply
                                        data =
                                            Bundle().apply {
                                                putInt("protocol", 1)
                                                putString("id", id)
                                                putString("format", format)
                                                putString("extension", source.extension)
                                                putParcelable("input", inputFd)
                                                putParcelable("output", outputFd)
                                            }
                                    }
                                )
                            } catch (error: Exception) {
                                finish(error)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            finish(IllegalStateException("Office 引擎已退出，原件保留，可在任务中心重试"))
                        }

                        override fun onBindingDied(name: ComponentName) {
                            onServiceDisconnected(name)
                        }

                        override fun onNullBinding(name: ComponentName) {
                            finish(IllegalStateException("请安装或更新 Office 手机引擎"))
                        }
                    }
                continuation.invokeOnCancellation {
                    main.post {
                        if (done.compareAndSet(false, true)) {
                            runCatching {
                                remote?.send(
                                    Message.obtain(null, 5).apply {
                                        data = Bundle().apply { putString("id", id) }
                                    }
                                )
                            }
                            cleanup()
                        }
                    }
                }
                try {
                    inputFd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                    outputFd = ParcelFileDescriptor.dup(output.fd)
                    bound =
                        context.bindService(
                            Intent()
                                .setComponent(
                                    ComponentName(
                                        "cn.paperforge.office",
                                        "cn.paperforge.office.OfficeJobService",
                                    )
                                ),
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    if (!bound) finish(IllegalStateException("请先安装或更新「Office 手机引擎」，再在任务中心重试"))
                    if (!continuation.isActive) main.post { cleanup() }
                } catch (error: SecurityException) {
                    finish(IllegalStateException("请安装与PaperForge同一签名渠道的 Office 手机引擎", error))
                } catch (error: Exception) {
                    finish(error)
                }
            }
        }
}

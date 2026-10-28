package cn.paperforge.app.imaging

import ai.onnxruntime.*
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Explicitly installed model data; no network permission or automatic download. */
object RetouchModels {
    const val MIGAN_SHA = "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b"
    const val SR_SHA = "85f36ff88cc504a24af5e0602148bc56a8aa09a58eca8c0da2756f3e8186035e"
    const val MIGAN_URL =
        "https://huggingface.co/andraniksargsyan/migan/resolve/406830d0fa60666da0071c342ad2fbc8f30c5c64/migan_pipeline_v2.onnx"
    private val lock = Any()

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun model(context: Context) = File(context.filesDir, "retouch-models/$MIGAN_SHA.onnx")

    fun installed(context: Context) = model(context).let { it.isFile && it.length() == 28079181L }

    fun install(context: Context, uri: Uri) =
        synchronized(lock) {
            val file = model(context).apply { parentFile!!.mkdirs() }
            val incoming = File(file.parentFile, "incoming.onnx")
            try {
                context.contentResolver.openInputStream(uri)?.use { source ->
                    incoming.outputStream().use { target ->
                        val buffer = ByteArray(65536)
                        var bytes = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= 28079181L) { "请选择 MI-GAN pipeline v2 模型" }
                            target.write(buffer, 0, count)
                        }
                    }
                } ?: error("无法读取修复模型")
                require(sha(incoming) == MIGAN_SHA) { "模型与指定版本不一致，请使用模型说明中的文件" }
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    incoming.inputStream().use { it.copyTo(output) }
                    atomic.finishWrite(output)
                } catch (e: Throwable) {
                    atomic.failWrite(output)
                    throw e
                }
            } finally {
                incoming.delete()
            }
        }

    fun remove(context: Context) = synchronized(lock) { model(context).delete() }

    fun session(context: Context, neuralFill: Boolean): OrtSession =
        synchronized(lock) {
            val file =
                if (neuralFill)
                    model(context).also {
                        require(installed(context)) { "请先导入 MI-GAN 修复模型（约 28 MB）" }
                        require(sha(it) == MIGAN_SHA) { "修复模型校验失败，请重新导入" }
                    }
                else
                    File(context.cacheDir, "retouch-sr-$SR_SHA.onnx").also {
                        if (!it.isFile || it.length() != 240078L) {
                            context.assets.open("retouch/super-resolution-10.onnx").use { input ->
                                it.outputStream().use { out -> input.copyTo(out) }
                            }
                        }
                    }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                OrtEnvironment.getEnvironment().createSession(file.path, options)
            }
        }
}

package cn.paperforge.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.work.*
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.*

object UpdateFiles {
    fun directory(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

    fun part(context: Context, candidate: UpdateCandidate) =
        File(directory(context), candidate.sha256 + ".part")

    fun ready(context: Context, candidate: UpdateCandidate) =
        File(directory(context), candidate.sha256 + ".apk")

    private fun metadata(context: Context) = AtomicFile(File(directory(context), "downloads.json"))

    @Synchronized
    fun records(context: Context): List<JSONObject> =
        runCatching {
                val storage = metadata(context)
                if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists())
                    return@runCatching emptyList()
                val rows = JSONArray(storage.openRead().bufferedReader().use { it.readText() })
                List(rows.length()) { rows.getJSONObject(it) }
            }
            .getOrThrow()

    @Synchronized
    fun update(
        context: Context,
        candidate: UpdateCandidate,
        state: String,
        bytes: Long = 0,
        detail: String = "",
    ) {
        val rows =
            records(context).filterNot {
                it.getJSONObject("candidate").getString("sha256") == candidate.sha256
            }
        val value =
            JSONObject()
                .put("candidate", candidate.json())
                .put("state", state)
                .put("bytes", bytes)
                .put("detail", detail)
                .put("updated", System.currentTimeMillis())
        val storage = metadata(context)
        val out = storage.startWrite()
        try {
            out.write(JSONArray(listOf(value) + rows).toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(out)
        } catch (e: Throwable) {
            storage.failWrite(out)
            throw e
        }
    }

    fun enqueue(
        context: Context,
        candidate: UpdateCandidate,
        restart: Boolean = false,
    ): java.util.UUID {
        require(NetworkPreferences(context).enabled) { "请先启用可选更新" }
        require(candidate.sha256.matches(Regex("[0-9a-f]{64}")))
        if (restart) part(context, candidate).delete()
        update(context, candidate, "WAITING", part(context, candidate).length(), "等待网络")
        val request =
            OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(workDataOf("candidate" to candidate.json().toString()))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag("paperforge-update")
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("update:${candidate.sha256}", ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun pause(context: Context, candidate: UpdateCandidate) {
        WorkManager.getInstance(context).cancelUniqueWork("update:${candidate.sha256}")
        update(context, candidate, "PAUSED", part(context, candidate).length(), "已暂停，保留断点")
    }
}

/** Full APK signature verification, not merely reading an unverified signing-certificate field. */
object UpdateApkVerifier {
    suspend fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Suppress("DEPRECATION")
    private fun owner(context: Context): Set<String> {
        val packageId =
            runCatching {
                    context.packageManager.getPackageInfo("cn.paperforge.app", 0)
                    "cn.paperforge.app"
                }
                .getOrDefault(context.packageName)
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(packageId, flags)
        val signatures =
            if (Build.VERSION.SDK_INT >= 28) {
                val signing = checkNotNull(info.signingInfo)
                if (signing.hasMultipleSigners()) signing.apkContentsSigners
                else signing.signingCertificateHistory
            } else info.signatures
        return signatures
            .orEmpty()
            .map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it.toInt() and 255)
                }
            }
            .toSet()
    }

    @Suppress("DEPRECATION")
    suspend fun verify(context: Context, file: File, candidate: UpdateCandidate): JSONObject {
        require(file.length() == candidate.bytes && sha(file) == candidate.sha256) {
            "下载文件的大小或 SHA256 不一致，请重新下载"
        }
        currentCoroutineContext().ensureActive()
        val signed =
            ApkVerifier.Builder(file)
                .setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
                .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT)
                .build()
                .verify()
        require(signed.isVerified) { "APK 签名校验未通过，不能安装此文件" }
        val certificates =
            signed.signerCertificates
                .map { cert ->
                    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {
                        "%02x".format(it.toInt() and 255)
                    }
                }
                .toSet()
        require(certificates.isNotEmpty() && certificates.all { it in owner(context) }) {
            "安装包签名与PaperForge不一致"
        }
        val info =
            context.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("无法读取 APK 元数据")
        val code =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(
            info.packageName == candidate.packageName &&
                (candidate.versionCode < 0 || code == candidate.versionCode)
        ) {
            "安装包身份或版本与发布清单不符"
        }
        val abis =
            ZipFile(file).use { zip ->
                zip.entries()
                    .asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .map { it.name.split('/')[1] }
                    .toSet()
            }
        require(GitHubUpdates.compatible(abis.toList(), Build.SUPPORTED_ABIS.toList())) {
            "安装包不适用于本机处理器"
        }
        val installed = GitHubUpdates.installed(context)[info.packageName]
        require(installed == null || code > installed.versionCode) { "此安装包没有高于当前已安装版本" }
        return JSONObject()
            .put("packageName", info.packageName)
            .put("versionCode", code)
            .put("versionName", info.versionName)
            .put("sha256", candidate.sha256)
            .put("certificatesSha256", JSONArray(certificates.toList()))
            .put("signatureVerified", true)
            .put("abis", JSONArray(abis.toList()))
    }
}

private object UpdateDownloadLeases {
    val locks = ConcurrentHashMap<String, Mutex>()
}

class UpdateDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val candidate =
                UpdateCandidate.decode(
                    JSONObject(
                        inputData.getString("candidate") ?: return@withContext Result.failure()
                    )
                )
            val part = UpdateFiles.part(applicationContext, candidate)
            val prefs = NetworkPreferences(applicationContext)
            val lease = UpdateDownloadLeases.locks.getOrPut(candidate.sha256) { Mutex() }
            lease.lock()
            try {
                try {
                    require(prefs.enabled) { "可选更新已关闭，已保留下载断点" }
                    val ready = UpdateFiles.ready(applicationContext, candidate)
                    if (ready.isFile) {
                        UpdateApkVerifier.verify(applicationContext, ready, candidate)
                        UpdateFiles.update(
                            applicationContext,
                            candidate,
                            "READY",
                            ready.length(),
                            "SHA256 与同源 APK 签名已核对",
                        )
                        return@withContext Result.success()
                    }
                    val offset = part.length()
                    require(offset <= candidate.bytes) { "下载断点大于文件长度，请重新下载" }
                    if (offset < candidate.bytes) {
                        val connection =
                            GitHubUpdates(prefs.token()).connect(candidate.assetUrl, true, offset)
                        try {
                            val append = offset > 0 && connection.responseCode == 206
                            if (connection.responseCode == 206) {
                                val range =
                                    Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                                        .matchEntire(
                                            connection.getHeaderField("Content-Range").orEmpty()
                                        )
                                require(
                                    range != null &&
                                        range.groupValues[1].toLong() == offset &&
                                        range.groupValues[3].toLong() == candidate.bytes
                                ) {
                                    "服务器返回的断点范围不一致"
                                }
                            }
                            var bytes = if (append) offset else 0L
                            var reported = bytes
                            connection.inputStream.use { input ->
                                FileOutputStream(part, append).use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        bytes += n
                                        require(bytes <= candidate.bytes) { "服务器文件长度超过发布清单" }
                                        output.write(buffer, 0, n)
                                        if (
                                            bytes - reported >= 262144 || bytes == candidate.bytes
                                        ) {
                                            require(prefs.enabled) { "可选更新已关闭" }
                                            UpdateFiles.update(
                                                applicationContext,
                                                candidate,
                                                "RUNNING",
                                                bytes,
                                                "正在下载",
                                            )
                                            setProgress(workDataOf("bytes" to bytes))
                                            reported = bytes
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                    UpdateFiles.update(
                        applicationContext,
                        candidate,
                        "VERIFYING",
                        part.length(),
                        "正在校验完整 APK 签名",
                    )
                    val verification = UpdateApkVerifier.verify(applicationContext, part, candidate)
                    ensureActive()
                    check(part.renameTo(ready)) { "无法保存已校验安装包" }
                    File(ready.path + ".json").writeText(verification.toString(2))
                    UpdateFiles.update(
                        applicationContext,
                        candidate,
                        "READY",
                        ready.length(),
                        "SHA256 与同源 APK 签名已核对",
                    )
                    Result.success(workDataOf("path" to ready.path))
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        UpdateFiles.update(
                            applicationContext,
                            candidate,
                            "PAUSED",
                            part.length(),
                            "已暂停，保留断点",
                        )
                    }
                    throw e
                } catch (e: java.io.IOException) {
                    UpdateFiles.update(
                        applicationContext,
                        candidate,
                        "WAITING",
                        part.length(),
                        "网络中断，等待重试",
                    )
                    Result.retry()
                } catch (e: Exception) {
                    UpdateFiles.update(
                        applicationContext,
                        candidate,
                        "FAILED",
                        part.length(),
                        e.message ?: "下载或校验失败",
                    )
                    Result.failure(workDataOf("error" to (e.message ?: "下载或校验失败")))
                }
            } finally {
                lease.unlock()
            }
        }
}

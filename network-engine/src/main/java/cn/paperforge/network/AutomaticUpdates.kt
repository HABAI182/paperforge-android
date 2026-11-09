package cn.paperforge.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Optional daily update checks; downloads use the existing verified, resumable pipeline. */
object AutomaticUpdates {
    const val WORK = "paperforge-automatic-updates"
    const val DOWNLOAD_TAG = "paperforge-download-automatic"
    const val TAG = "paperforge-update-automatic"

    fun sync(context: Context) {
        val prefs = NetworkPreferences(context)
        val manager = WorkManager.getInstance(context)
        if (!prefs.enabled || !prefs.automaticUpdates) {
            manager.cancelUniqueWork(WORK)
            manager.cancelAllWorkByTag(TAG)
            return
        }
        if (!prefs.automaticDownload) manager.cancelAllWorkByTag(DOWNLOAD_TAG)
        val request =
            PeriodicWorkRequestBuilder<AutomaticUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (prefs.automaticDownload) NetworkType.UNMETERED
                            else NetworkType.CONNECTED
                        )
                        .build()
                )
                .addTag(TAG)
                .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun status(context: Context, message: String) {
        NetworkPreferences(context)
            .store
            .edit()
            .putString("automaticUpdateStatus", message)
            .putLong("automaticUpdateAt", System.currentTimeMillis())
            .apply()
    }

    fun notify(context: Context, candidate: UpdateCandidate, ready: Boolean) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "paperforge-updates",
                "版本更新",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val open =
            PendingIntent.getActivity(
                context,
                42,
                Intent(context, UpdatesActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        manager.notify(
            2042,
            NotificationCompat.Builder(context, "paperforge-updates")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(
                    if (ready) "PaperForge ${candidate.versionName} 已准备好"
                    else "发现 PaperForge ${candidate.versionName}"
                )
                .setContentText(if (ready) "安装包已校验，点击后由系统确认安装" else "点击查看更新与下载")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class AutomaticUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = NetworkPreferences(applicationContext)
        if (!prefs.enabled || !prefs.automaticUpdates) return Result.success()
        return try {
            val checked =
                GitHubUpdates(
                        prefs.token(),
                        integrated = NetworkFiles.integrated(applicationContext),
                    )
                    .check(
                        prefs.repository,
                        prefs.channel,
                        GitHubUpdates.installed(applicationContext),
                    )
            val candidates = checked.candidates
            if (!prefs.enabled || !prefs.automaticUpdates) return Result.success()
            if (candidates.isEmpty()) {
                AutomaticUpdates.status(
                    applicationContext,
                    if (checked.warnings.isNotEmpty()) "检查完成，适配信息未能完整核对"
                    else if (checked.releases == 0) "当前渠道尚无发布版本" else "当前已是最新版本",
                )
            } else {
                AutomaticUpdates.status(
                    applicationContext,
                    "发现新版本 ${candidates.joinToString { it.versionName }}",
                )
                for (candidate in candidates) {
                    if (prefs.automaticDownload)
                        UpdateFiles.enqueue(applicationContext, candidate, automatic = true)
                    else AutomaticUpdates.notify(applicationContext, candidate, false)
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AutomaticUpdates.status(applicationContext, "自动检查未成功：${e.message ?: "未连接到服务器"}")
            if (e is java.io.IOException && runAttemptCount < 3) Result.retry()
            else Result.success()
        }
    }
}

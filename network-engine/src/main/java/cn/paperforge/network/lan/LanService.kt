package cn.paperforge.network.lan

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import cn.paperforge.network.R
import java.net.NetworkInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

object LanSession {
    val server = MutableStateFlow<LanHttpServer?>(null)
    val message = MutableStateFlow("")
    val copying = MutableStateFlow(0)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, port: Int, minutes: Int) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LanService::class.java)
                .putExtra("port", port)
                .putExtra("minutes", minutes),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LanService::class.java))
    }

    fun addresses(): List<String> =
        runCatching {
                NetworkInterface.getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filter {
                        it is java.net.Inet4Address &&
                            !it.isLoopbackAddress &&
                            !it.isLinkLocalAddress
                    }
                    .map { it.hostAddress!! }
                    .distinct()
            }
            .getOrDefault(emptyList())
}

/** Explicit, visible sharing session; no receiver starts it automatically or after boot. */
class LanService : Service() {
    private var idle: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("lan", "局域网传输", NotificationManager.IMPORTANCE_LOW)
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, LanActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, LanService::class.java).setAction("STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val note =
            Notification.Builder(this, "lan")
                .setSmallIcon(R.drawable.ic_brand_monochrome)
                .setContentTitle("PaperForge · 局域网传输")
                .setContentText("已开启；点此管理文件，关闭后保留队列与断点")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "关闭传输", stop).build())
                .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(42, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(42, note)
        if (LanSession.server.value == null) {
            try {
                val server = LanHttpServer(this, intent?.getIntExtra("port", 8787) ?: 8787)
                LanSession.server.value = server
                LanSession.message.value = ""
                val minutes = intent?.getIntExtra("minutes", 30) ?: 30
                idle =
                    LanSession.scope.launch {
                        while (isActive) {
                            delay(15000)
                            if (
                                minutes > 0 &&
                                    System.currentTimeMillis() - server.lastActivity >
                                        minutes * 60000L
                            ) {
                                LanSession.message.value = "长时间没有连接，传输已关闭；队列与断点保留"
                                stopSelf()
                                break
                            }
                        }
                    }
            } catch (e: Exception) {
                LanSession.message.value = e.message ?: "无法开启传输，请换一个端口"
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        LanSession.message.value = "系统已结束本次后台传输时段，断点保留；可再次手动开启"
        stopSelf()
    }

    override fun onDestroy() {
        idle?.cancel()
        LanSession.server.value?.close()
        LanSession.server.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

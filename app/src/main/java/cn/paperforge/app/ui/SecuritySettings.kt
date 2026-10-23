package cn.paperforge.app.ui

import android.app.KeyguardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.paperforge.app.MainActivity

@Composable
fun SecuritySettings() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("security", 0) }
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var secure by remember { mutableStateOf(preferences.getBoolean("secureScreen", false)) }
    var lock by remember { mutableStateOf(preferences.getBoolean("appLock", false)) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("屏蔽截图与最近任务预览", Modifier.weight(1f))
            Switch(
                secure,
                { value ->
                    secure = value
                    preferences.edit().putBoolean("secureScreen", value).apply()
                    (context as? MainActivity)?.securitySettingsChanged()
                },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text("设备凭据锁定应用", Modifier.weight(1f))
            Switch(
                lock,
                { value ->
                    lock = value
                    preferences.edit().putBoolean("appLock", value).apply()
                    (context as? MainActivity)?.securitySettingsChanged(forceLock = value)
                },
                enabled = keyguard.isDeviceSecure,
            )
        }
        Text(
            if (keyguard.isDeviceSecure) "启用后，首次打开或后台超过30秒需通过系统PIN/密码验证。此项不加密文件；导出和分享的副本由目标应用管理。"
            else "应用锁需要先在系统中设置设备PIN或密码。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

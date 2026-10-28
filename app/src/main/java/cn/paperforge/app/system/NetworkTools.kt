package cn.paperforge.app.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import cn.paperforge.app.UserSettings

object NetworkTools {
    fun updates(context: Context) {
        val installed =
            runCatching { context.packageManager.getPackageInfo("cn.paperforge.network", 0) }
                .isSuccess
        if (installed) {
            if (
                context.packageManager.checkSignatures(
                    context.packageName,
                    "cn.paperforge.network",
                ) != PackageManager.SIGNATURE_MATCH
            ) {
                Toast.makeText(context, "联网工具签名与纸匠不一致，请使用同一来源的安装包", Toast.LENGTH_LONG).show()
                return
            }
            context.startActivity(
                Intent()
                    .putExtra("paperforgeTheme", UserSettings.string("theme", "SYSTEM"))
                    .putExtra("paperforgeFontScale", UserSettings.number("fontScale", 1f))
                    .setComponent(
                        ComponentName(
                            "cn.paperforge.network",
                            "cn.paperforge.network.UpdatesActivity",
                        )
                    )
            )
        } else {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/HABAI182/paperforge-android/releases"),
                )
            )
            Toast.makeText(context, "安装可选联网工具后，可在手机内检查和下载更新", Toast.LENGTH_LONG).show()
        }
    }
}

package cn.paperforge.app.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings

object NetworkTools {
    fun updates(context: Context, oneClick: Boolean = false) =
        launch(context, "UpdatesActivity", oneClick = oneClick)

    fun lan(context: Context) = launch(context, "lan.LanActivity")

    fun intelligence(context: Context, source: java.io.File? = null) =
        launch(
            context,
            "intelligence.IntelligenceActivity",
            source?.let {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    it,
                )
            },
        )

    private fun launch(
        context: Context,
        screen: String,
        source: Uri? = null,
        oneClick: Boolean = false,
    ) {
        val targetPackage = PhoneEngines.packageFor(context, "cn.paperforge.network")
        val installed =
            runCatching { context.packageManager.getPackageInfo(targetPackage, 0) }.isSuccess
        if (installed) {
            if (
                context.packageManager.checkSignatures(context.packageName, targetPackage) !=
                    PackageManager.SIGNATURE_MATCH
            ) {
                Toast.makeText(context, "联网工具签名与PaperForge不一致，请使用同一来源的安装包", Toast.LENGTH_LONG)
                    .show()
                return
            }
            context.startActivity(
                Intent()
                    .apply {
                        if (source != null) {
                            data = source
                            clipData = android.content.ClipData.newRawUri("原文快照", source)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    .putExtra("paperforgeTheme", UserSettings[SettingKeys.Theme])
                    .putExtra("paperforgeFontScale", UserSettings[SettingKeys.FontScale])
                    .putExtra("paperforgeOneClickUpdate", oneClick)
                    .setComponent(ComponentName(targetPackage, "cn.paperforge.network.$screen"))
            )
        } else {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/HABAI182/paperforge-android/releases"),
                )
            )
            Toast.makeText(context, "安装可选联网工具后，可使用更新、局域网互传和智能工具", Toast.LENGTH_LONG).show()
        }
    }
}

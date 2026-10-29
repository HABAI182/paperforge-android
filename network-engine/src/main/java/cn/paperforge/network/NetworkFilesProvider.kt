package cn.paperforge.network

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.FileProvider

class NetworkFilesProvider : FileProvider()

object NetworkFiles {
    @Suppress("DEPRECATION")
    fun integrated(context: Context) =
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getBoolean("paperforge.full", false) == true

    fun authority(context: Context) =
        context.packageName + if (integrated(context)) ".network.files" else ".files"
}

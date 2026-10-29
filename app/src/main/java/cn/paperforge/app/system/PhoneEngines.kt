package cn.paperforge.app.system

import android.content.Context
import android.content.pm.PackageManager

object PhoneEngines {
    @Suppress("DEPRECATION")
    fun integrated(context: Context): Boolean =
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getBoolean("paperforge.full", false) == true

    fun packageFor(context: Context, standalone: String) =
        if (integrated(context)) context.packageName else standalone
}

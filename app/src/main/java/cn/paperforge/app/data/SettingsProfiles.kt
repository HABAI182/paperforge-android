package cn.paperforge.app.data

import android.content.Context
import cn.paperforge.app.UserSettings
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SettingsProfile(
    val id: String,
    val name: String,
    val values: JSONObject,
    val builtIn: Boolean = false,
    val description: String = "",
) {
    fun encode() = JSONObject().put("id", id).put("name", name).put("values", values)
}

/** Profiles carry declared setting values only, never device paths, permissions or secrets. */
class SettingsProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", 0)

    fun saved(): List<SettingsProfile> = decode(JSONArray(prefs.getString("items", "[]")))

    fun active() = prefs.getString("active", "自定义") ?: "自定义"

    fun export() =
        JSONObject()
            .put("format", 1)
            .put("profiles", JSONArray().apply { saved().forEach { put(it.encode()) } })

    private fun write(rows: List<SettingsProfile>) {
        check(
            prefs
                .edit()
                .putString(
                    "items",
                    JSONArray().apply { rows.forEach { put(it.encode()) } }.toString(),
                )
                .commit()
        ) {
            "无法保存配置档案"
        }
    }

    fun save(name: String, groups: Set<String>): SettingsProfile {
        require(name.isNotBlank() && groups.isNotEmpty()) { "请填写名称并选择分组" }
        val row =
            SettingsProfile(
                UUID.randomUUID().toString(),
                name.trim(),
                UserSettings.values(keys(groups)),
            )
        write(saved() + row)
        return row
    }

    fun copy(row: SettingsProfile) {
        write(
            saved() +
                row.copy(
                    id = UUID.randomUUID().toString(),
                    name = "${row.name} 副本",
                    builtIn = false,
                )
        )
    }

    fun remove(row: SettingsProfile) = write(saved().filter { it.id != row.id })

    fun apply(row: SettingsProfile): Int {
        val count =
            UserSettings.importJson(
                JSONObject().put("format", 1).put("preferences", row.values).toString()
            )
        prefs.edit().putString("active", row.name).apply()
        return count
    }

    fun defaults(groups: Set<String>) {
        UserSettings.importJson(
            JSONObject()
                .put("format", 1)
                .put("preferences", UserSettings.values(keys(groups), true))
                .toString()
        )
        prefs.edit().putString("active", "默认设置").apply()
    }

    fun importJson(text: String): Int {
        val root = JSONObject(text)
        require(root.optInt("format") == 1) { "不支持的配置版本" }
        val rows =
            if (root.has("profiles")) decode(root.getJSONArray("profiles"))
            else
                listOf(
                    SettingsProfile(
                        UUID.randomUUID().toString(),
                        "导入的设置",
                        UserSettings.normalizedValues(root.getJSONObject("preferences")),
                    )
                )
        val old = saved().toMutableList()
        var count = 0
        rows.forEach { row ->
            if (old.none { it.name == row.name && sameValues(it.values, row.values) }) {
                old +=
                    row.copy(
                        id =
                            if (old.any { it.id == row.id }) UUID.randomUUID().toString()
                            else row.id
                    )
                count++
            }
        }
        write(old)
        return count
    }

    companion object {
        fun sameValues(first: JSONObject, second: JSONObject) =
            first.length() == second.length() &&
                first.keys().asSequence().all { second.has(it) && first.get(it) == second.get(it) }

        val groups =
            linkedMapOf(
                "scan" to "扫描与相机",
                "export" to "PDF导出",
                "ocr" to "识别语言",
                "appearance" to "外观",
                "home" to "首页与工具",
            )
        private val groupKeys =
            mapOf(
                "scan" to
                    setOf(
                        "detector",
                        "outlineWeight",
                        "outlineSwitchFrames",
                        "dewarpStrength",
                        "autoShutter",
                        "reviewAfterCapture",
                        "lockCrop",
                        "showOutline",
                        "strictQuality",
                        "captureArea",
                        "captureConfidence",
                        "stableMs",
                        "cooldownMs",
                        "analysisMs",
                        "cameraJpeg",
                        "filter",
                        "enhanceStrength",
                        "brightness",
                        "contrast",
                        "turnRearm",
                        "turnThreshold",
                        "captureTimer",
                        "scanAspect",
                    ),
                "export" to setOf("searchable", "paper", "resolution", "customDpi", "quality"),
                "ocr" to setOf("ocrLanguage", "ocrMode", "ocrVoting"),
                "appearance" to
                    setOf(
                        "theme",
                        "accent",
                        "dynamicColor",
                        "fontScale",
                        "compactRows",
                        "edgeTurn",
                    ),
                "home" to setOf("recentCount", "homeTools"),
            )

        fun keys(groups: Set<String>) = groups.flatMap { groupKeys[it].orEmpty() }.toSet()

        private fun decode(array: JSONArray) =
            (0 until array.length()).map { i ->
                val j = array.getJSONObject(i)
                SettingsProfile(
                    j.getString("id"),
                    j.getString("name"),
                    UserSettings.normalizedValues(j.getJSONObject("values")),
                )
            }

        val modes: List<SettingsProfile>
            get() {
                fun mode(
                    id: String,
                    name: String,
                    description: String,
                    vararg values: Pair<String, Any>,
                ) =
                    SettingsProfile(
                        id,
                        name,
                        JSONObject(
                            mapOf(
                                "detector" to "HYBRID",
                                "filter" to "AUTO",
                                "scanAspect" to 0f,
                                "captureTimer" to 0f,
                                "autoShutter" to true,
                                "reviewAfterCapture" to false,
                                "turnRearm" to true,
                                "stableMs" to 600f,
                                "cooldownMs" to 1500f,
                            ) + values
                        ),
                        true,
                        description,
                    )
                return listOf(
                    mode("daily", "日常文档", "自动拍摄、自然增强，沿用你的画质设置"),
                    mode(
                        "book",
                        "书籍",
                        "保留彩色，手动拍摄；完成后可双页分割与曲面校正",
                        "filter" to "COLOR",
                        "autoShutter" to false,
                    ),
                    mode("receipt", "票据", "文字增强、自动拍摄，保留票据原有比例", "filter" to "TEXT"),
                    mode(
                        "id",
                        "证件",
                        "彩色、手动拍摄，输出采用身份证比例",
                        "filter" to "COLOR",
                        "autoShutter" to false,
                        "scanAspect" to 1.5851852f,
                    ),
                    mode(
                        "board",
                        "白板",
                        "白板增强与手动拍摄",
                        "filter" to "WHITEBOARD",
                        "autoShutter" to false,
                    ),
                )
            }
    }
}

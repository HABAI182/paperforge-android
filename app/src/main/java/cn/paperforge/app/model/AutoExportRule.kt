package cn.paperforge.app.model

import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.DocumentRow
import java.security.MessageDigest
import java.util.UUID
import org.json.*

data class AutoExportRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "自动导出规则",
    val enabled: Boolean = false,
    val folder: String = "",
    val tags: String = "",
    val recipe: WorkflowRecipe =
        WorkflowRecipe(
            name = "自动导出副本",
            steps =
                listOf(
                    RecipeStep(
                        operation = "rename",
                        options = JSONObject().put("template", "{title}_{date}"),
                    )
                ),
        ),
    val export: JSONObject = ScanExportOptions.current().values(),
    val delaySeconds: Int = 5,
    val activation: String = UUID.randomUUID().toString(),
) {
    fun matches(document: DocumentRow): Boolean {
        if (
            !document.listed || document.trashedAt != null || document.kind !in setOf("pdf", "scan")
        )
            return false
        val target = folder.trim().trim('/')
        if (
            target.isNotEmpty() &&
                document.folder != target &&
                !document.folder.startsWith("$target/")
        )
            return false
        fun tokens(value: String) =
            value.split(Regex("[,，;；\\s]+")).filter { it.isNotBlank() }.toSet()
        return tokens(document.tags).containsAll(tokens(tags))
    }

    fun issue(): String? =
        if (name.isBlank()) "请填写规则名称"
        else if (recipe.input != "PDF") "自动导出配方需以 PDF 为输入" else recipe.issue()

    fun fingerprint(source: String) =
        digest(
            "auto-export1|$activation|$source|${recipe.json().apply { remove("name")
 remove("id") }}|$export"
        )

    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("enabled", enabled)
            .put("folder", folder)
            .put("tags", tags)
            .put("recipe", recipe.json())
            .put("export", export)
            .put("delaySeconds", delaySeconds)
            .put("activation", activation)

    companion object {
        fun decode(j: JSONObject) =
            AutoExportRule(
                j.getString("id"),
                j.getString("name"),
                j.optBoolean("enabled"),
                j.optString("folder"),
                j.optString("tags"),
                WorkflowRecipe.decode(j.getJSONObject("recipe")),
                j.optJSONObject("export") ?: JSONObject(),
                j.optInt("delaySeconds", 5).coerceIn(0, 60),
                j.optString("activation", j.getString("id")),
            )

        fun digest(value: String) =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

object AutoExportRules {
    fun all(): List<AutoExportRule> =
        runCatching {
                val array = JSONArray(UserSettings.string("autoExportRules", "[]"))
                List(array.length()) {
                        runCatching { AutoExportRule.decode(array.getJSONObject(it)) }.getOrNull()
                    }
                    .filterNotNull()
            }
            .getOrDefault(emptyList())

    fun save(rule: AutoExportRule) {
        val previous = all()
        val selected =
            if (rule.enabled && previous.firstOrNull { it.id == rule.id }?.enabled == false)
                rule.copy(activation = UUID.randomUUID().toString())
            else rule
        UserSettings.set(
            "autoExportRules",
            JSONArray()
                .apply {
                    (previous.filterNot { it.id == rule.id } + selected).forEach { put(it.json()) }
                }
                .toString(),
        )
    }

    fun remove(id: String) =
        UserSettings.set(
            "autoExportRules",
            JSONArray()
                .apply { all().filterNot { it.id == id }.forEach { put(it.json()) } }
                .toString(),
        )
}

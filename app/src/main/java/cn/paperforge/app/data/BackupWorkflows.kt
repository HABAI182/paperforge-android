package cn.paperforge.app.data

import cn.paperforge.app.UserSettings
import cn.paperforge.app.model.AutoExportRule
import cn.paperforge.app.model.WorkflowRecipe
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Merge restored workflows without overwriting local entries or activating imported rules. */
internal object BackupWorkflows {
    private fun merged(local: String, incoming: String, rule: Boolean): String {
        val result = JSONArray(local)
        val source = JSONArray(incoming)
        for (i in 0 until source.length()) {
            val item = source.getJSONObject(i)
            val normalized =
                if (rule) AutoExportRule.decode(item).json() else WorkflowRecipe.decode(item).json()
            val id = normalized.getString("id")
            val existing = (0 until result.length()).map { result.getJSONObject(it) }
            val sameId = existing.firstOrNull { it.optString("id") == id }
            if (rule) normalized.put("enabled", false)
            val comparable =
                sameId?.let { JSONObject(it.toString()).apply { if (rule) put("enabled", false) } }
            if (comparable != null && comparable.toString() == normalized.toString()) continue
            if (sameId != null) {
                val content = JSONObject(normalized.toString()).apply { remove("id") }.toString()
                if (
                    existing.any {
                        JSONObject(it.toString())
                            .apply {
                                remove("id")
                                if (rule) put("enabled", false)
                            }
                            .toString() == content
                    }
                )
                    continue
                normalized.put("id", UUID.randomUUID().toString())
            }
            result.put(normalized)
        }
        return result.toString()
    }

    fun preferences(root: JSONObject): JSONObject {
        val copy = JSONObject(root.toString())
        val values = copy.getJSONObject("preferences")
        for ((key, rule) in listOf("workflowRecipes" to false, "autoExportRules" to true)) {
            if (values.has(key) || rule) {
                values.put(
                    key,
                    merged(UserSettings.string(key, "[]"), values.optString(key, "[]"), rule),
                )
            }
        }
        return copy
    }

    /** Profile application is explicit, but rules inside imported profiles still require review. */
    fun profiles(root: JSONObject): JSONObject {
        val copy = JSONObject(root.toString())
        val profiles = copy.optJSONArray("profiles") ?: return copy
        for (i in 0 until profiles.length()) {
            val values = profiles.getJSONObject(i).getJSONObject("values")
            if (values.has("autoExportRules")) {
                val rules = JSONArray(values.getString("autoExportRules"))
                for (j in 0 until rules.length()) rules.getJSONObject(j).put("enabled", false)
                values.put("autoExportRules", rules.toString())
            }
        }
        return copy
    }
}

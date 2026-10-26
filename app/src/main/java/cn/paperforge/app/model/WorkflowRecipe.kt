package cn.paperforge.app.model

import cn.paperforge.app.UserSettings
import java.util.UUID
import org.json.*

data class RecipeOperation(
    val id: String,
    val title: String,
    val input: String,
    val output: String,
) {
    fun accepts(type: String) = input == "ANY" || input == type
}

data class RecipeStep(
    val id: String = UUID.randomUUID().toString(),
    val operation: String,
    val options: JSONObject = JSONObject(),
) {
    fun json() = JSONObject().put("id", id).put("operation", operation).put("options", options)

    companion object {
        fun decode(j: JSONObject) =
            RecipeStep(
                j.getString("id"),
                j.getString("operation"),
                j.optJSONObject("options") ?: JSONObject(),
            )
    }
}

data class WorkflowRecipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新配方",
    val input: String = "PDF",
    val steps: List<RecipeStep> = emptyList(),
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("input", input)
            .put("steps", JSONArray().apply { steps.forEach { put(it.json()) } })

    fun issue(): String? {
        if (name.isBlank()) return "请为配方命名"
        if (steps.isEmpty()) return "请添加一个处理步骤"
        var type = input
        for ((index, step) in steps.withIndex()) {
            if (!step.id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) return "步骤标识无效"
            val operation =
                WorkflowRecipes.operations.firstOrNull { it.id == step.operation }
                    ?: return "步骤 ${index+1} 暂不支持"
            if (!operation.accepts(type))
                return "步骤 ${index+1}「${operation.title}」需要 ${operation.input}，上一阶段输出为 $type"
            if (step.operation == "save" && step.options.optString("destination").isBlank())
                return "步骤 ${index+1} 请选择保存目录"
            if (operation.output != "SAME") type = operation.output
        }
        if (steps.map { it.id }.distinct().size != steps.size) return "步骤标识重复，请复制为新配方后调整"
        return null
    }

    companion object {
        fun decode(j: JSONObject): WorkflowRecipe {
            val steps = j.getJSONArray("steps")
            return WorkflowRecipe(
                j.getString("id"),
                j.getString("name"),
                j.getString("input"),
                List(steps.length()) { RecipeStep.decode(steps.getJSONObject(it)) },
            )
        }
    }
}

object WorkflowRecipes {
    fun typeLabel(type: String) =
        when (type) {
            "TEXT" -> "文字"
            "OFFICE" -> "Office 文档"
            "IMAGE" -> "图片"
            "ZIP" -> "资源包 ZIP"
            "ANY" -> "任意格式"
            "SAME" -> "保持原格式"
            else -> type
        }

    val operations =
        listOf(
            RecipeOperation("office-pdf", "Office 排版为 PDF", "OFFICE", "PDF"),
            RecipeOperation("text-pdf", "文字排版为 PDF", "TEXT", "PDF"),
            RecipeOperation("image-pdf", "图片转 PDF", "IMAGE", "PDF"),
            RecipeOperation("searchable", "添加 OCR 文字层", "PDF", "PDF"),
            RecipeOperation("compress", "压缩嵌入图片", "PDF", "PDF"),
            RecipeOperation("pdf-txt", "提取文字 TXT", "PDF", "TEXT"),
            RecipeOperation("pdf-docx", "结构重建 Word", "PDF", "OFFICE"),
            RecipeOperation("pdf-png", "导出 PNG 页面包", "PDF", "ZIP"),
            RecipeOperation("rename", "按模板命名副本", "ANY", "SAME"),
            RecipeOperation("save", "保存到授权目录", "ANY", "SAME"),
        )

    fun sourceType(extension: String) =
        when (extension.lowercase()) {
            "pdf" -> "PDF"
            "png",
            "jpg",
            "jpeg",
            "webp" -> "IMAGE"
            "txt" -> "TEXT"
            "doc",
            "docx",
            "odt",
            "rtf",
            "xls",
            "xlsx",
            "ods",
            "ppt",
            "pptx",
            "odp" -> "OFFICE"
            else -> "FILE"
        }

    fun all(): List<WorkflowRecipe> =
        runCatching {
                val array = JSONArray(UserSettings.string("workflowRecipes", "[]"))
                List(array.length()) {
                        runCatching { WorkflowRecipe.decode(array.getJSONObject(it)) }.getOrNull()
                    }
                    .filterNotNull()
            }
            .getOrDefault(emptyList())

    fun save(recipe: WorkflowRecipe) {
        val items = all().filterNot { it.id == recipe.id } + recipe
        UserSettings.set(
            "workflowRecipes",
            JSONArray().apply { items.forEach { put(it.json()) } }.toString(),
        )
    }

    fun remove(id: String) =
        UserSettings.set(
            "workflowRecipes",
            JSONArray()
                .apply { all().filterNot { it.id == id }.forEach { put(it.json()) } }
                .toString(),
        )

    fun builtin() =
        listOf(
            WorkflowRecipe(
                name = "扫描 PDF 归档",
                steps =
                    listOf(
                        RecipeStep(operation = "searchable"),
                        RecipeStep(
                            operation = "compress",
                            options = JSONObject().put("quality", 84),
                        ),
                        RecipeStep(
                            operation = "rename",
                            options = JSONObject().put("template", "{title}_{date}"),
                        ),
                    ),
            ),
            WorkflowRecipe(
                name = "Office 转 PDF",
                input = "OFFICE",
                steps =
                    listOf(
                        RecipeStep(operation = "office-pdf"),
                        RecipeStep(
                            operation = "compress",
                            options = JSONObject().put("quality", 84),
                        ),
                    ),
            ),
            WorkflowRecipe(
                name = "图片识字 PDF",
                input = "IMAGE",
                steps =
                    listOf(
                        RecipeStep(operation = "image-pdf"),
                        RecipeStep(operation = "searchable"),
                    ),
            ),
            WorkflowRecipe(
                name = "文字排版 PDF",
                input = "TEXT",
                steps = listOf(RecipeStep(operation = "text-pdf")),
            ),
            WorkflowRecipe(name = "PDF 提取正文", steps = listOf(RecipeStep(operation = "pdf-txt"))),
        )
}

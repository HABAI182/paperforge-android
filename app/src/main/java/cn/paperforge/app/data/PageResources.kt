package cn.paperforge.app.data

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PageResource(val id: String, val role: String, val path: String)

/** Durable resources only. Regenerable thumbnails and OCR caches never enter this graph. */
object PageResources {
    fun withoutOcrRecord(page: PageRow): String {
        val json = metadata(page)
        json.remove("ocrRecord")
        val resources = json.optJSONArray("resources")
        if (resources != null)
            json.put(
                "resources",
                JSONArray().apply {
                    for (i in 0 until resources.length()) if (
                        resources.getJSONObject(i).optString("role") != "ocr-record"
                    )
                        put(resources.get(i))
                },
            )
        return json.toString()
    }

    fun withOcrRecord(page: PageRow, file: File): String {
        val json = JSONObject(withoutOcrRecord(page)).put("format", 1)
        val resources =
            json.optJSONArray("resources") ?: JSONArray().also { json.put("resources", it) }
        val id = UUID.randomUUID().toString()
        resources.put(JSONObject().put("id", id).put("role", "ocr-record").put("path", file.path))
        return json.put("ocrRecord", id).toString()
    }

    fun metadata(page: PageRow) = JSONObject(page.processingMetadata)

    fun entries(page: PageRow): List<PageResource> {
        val array = metadata(page).optJSONArray("resources") ?: return emptyList()
        return (0 until array.length()).map {
            array.getJSONObject(it).let { r ->
                PageResource(r.getString("id"), r.getString("role"), r.getString("path"))
            }
        }
    }

    fun paths(page: PageRow) = (listOf(page.original) + entries(page).map { it.path }).toSet()

    fun remap(page: PageRow, map: (String) -> String): PageRow {
        val json = metadata(page)
        json.optJSONArray("resources")?.let { array ->
            for (i in 0 until array.length()) {
                val resource = array.getJSONObject(i)
                resource.put("path", map(resource.getString("path")))
            }
        }
        return page.copy(original = map(page.original), processingMetadata = json.toString())
    }

    fun withDewarp(
        page: PageRow,
        input: File,
        grid: File,
        output: File,
        strength: Float,
        modelSha256: String,
    ): String {
        val json = metadata(page).put("format", 1)
        val resources =
            json.optJSONArray("resources") ?: JSONArray().also { json.put("resources", it) }
        fun resource(role: String, path: String): String {
            val id = UUID.randomUUID().toString()
            resources.put(JSONObject().put("id", id).put("role", role).put("path", path))
            return id
        }
        val source = resource("source-original", page.original)
        val rendered = resource("dewarp-input", input.path)
        val field = resource("dewarp-grid", grid.path)
        val result = resource("dewarp-output", output.path)
        val steps = json.optJSONArray("steps") ?: JSONArray().also { json.put("steps", it) }
        steps.put(
            JSONObject()
                .put("kind", "dewarp")
                .put("algorithm", "mnn-displacement-240-v2")
                .put("modelSha256", modelSha256)
                .put("strength", strength)
                .put("created", System.currentTimeMillis())
                .put("original", source)
                .put("input", rendered)
                .put("grid", field)
                .put("output", result)
                .put(
                    "sourceGeometry",
                    JSONObject()
                        .put("corners", page.corners)
                        .put("rotation", page.rotation)
                        .put("deskewDegrees", page.deskewDegrees)
                        .put("outputAspect", page.outputAspect),
                )
        )
        return json.toString()
    }
}

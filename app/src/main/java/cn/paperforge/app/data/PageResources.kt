package cn.paperforge.app.data

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PageResource(val id: String, val role: String, val path: String)

/** Durable resources only. Regenerable thumbnails and OCR caches never enter this graph. */
object PageResources {
    /** Old finished workbook pixels were labelled ORIGINAL. Relabel without altering pixels. */
    fun normalizedAppearance(page: PageRow): PageRow {
        val json = metadata(page)
        val flat = json.optJSONObject("flatDocument") ?: return page
        if (flat.optInt("appearanceModel") >= 2) return page
        flat.put("appearanceModel", 2).put("bakedFilter", "WORKBOOK").put("bakedStrength", 1f)
        return page.copy(
            filter = if (page.filter == "ORIGINAL") "WORKBOOK" else page.filter,
            enhanceStrength = if (page.filter == "ORIGINAL") 1f else page.enhanceStrength,
            processingMetadata = json.toString(),
        )
    }

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

    /** Recover a retained camera crop for reprocessing and original comparison. */
    fun sourceAppearance(page: PageRow): PageRow {
        val json = metadata(page)
        if (
            !json.has("flatDocument") ||
                cn.paperforge.app.model.Quad.decode(page.corners) !=
                    cn.paperforge.app.model.Quad.Full ||
                page.rotation != 0 ||
                page.deskewDegrees != 0f
        )
            return page
        val steps = json.optJSONArray("steps") ?: return page
        val resources = entries(page).associateBy { it.id }
        // A page cropped again keeps its earlier steps, so the newest step that started from a camera photo
        // holds the crop now in use. A step that started from an earlier finished page is not a source.
        val outputs =
            (0 until steps.length())
                .mapNotNull { resources[steps.getJSONObject(it).optString("output")]?.path }
                .toSet()
        for (i in steps.length() - 1 downTo 0) {
            val step = steps.getJSONObject(i)
            val geometry = step.optJSONObject("sourceGeometry") ?: continue
            val file = resources[step.optString("original")]?.path ?: continue
            if (file in outputs || !File(file).isFile) continue
            return page.copy(
                original = file,
                corners = geometry.getString("corners"),
                rotation = geometry.optInt("rotation"),
                deskewDegrees = geometry.optDouble("deskewDegrees", 0.0).toFloat(),
                outputAspect = geometry.optDouble("outputAspect", 0.0).toFloat(),
                filter = "ORIGINAL",
                brightness = 0f,
                contrast = 1f,
                processingMetadata =
                    JSONObject(json.toString()).apply { remove("flatDocument") }.toString(),
            )
        }
        return page
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
        algorithm: String = "mnn-displacement-240-v2",
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
                .put("algorithm", algorithm)
                .apply {
                    if (modelSha256.matches(Regex("[0-9a-f]{64}"))) put("modelSha256", modelSha256)
                    else put("implementationVersion", modelSha256)
                }
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

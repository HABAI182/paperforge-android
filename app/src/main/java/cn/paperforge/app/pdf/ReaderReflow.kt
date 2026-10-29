package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.OcrEngine
import cn.paperforge.app.ocr.OcrOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class ReflowItem(
    val kind: String,
    val region: OcrRegion,
    val text: String = "",
    val image: File? = null,
    val table: PhoneTables.Table? = null,
    val source: String = "native",
) {
    fun destination(page: Int): ReaderDestination {
        val width = maxOf(region.width + .04f, .2f)
        val height = maxOf(region.height + .04f, .12f)
        val cx = (region.left + region.right) / 2
        val cy = (region.top + region.bottom) / 2
        return ReaderDestination(
            page,
            "FitR",
            (cx - width / 2).coerceAtLeast(0f),
            (cy - height / 2).coerceAtLeast(0f),
            (cx + width / 2).coerceAtMost(1f),
            (cy + height / 2).coerceAtMost(1f),
        )
    }
}

data class ReflowPage(
    val page: Int,
    val items: List<ReflowItem>,
    val sourceSha256: String,
    val usedOcr: Boolean,
)

/** On-demand single-page reading cache. It never replaces the PDF or its native text layer. */
object ReaderReflow {
    const val VERSION = "paragraph-figures-ruled-v1"

    suspend fun load(
        context: Context,
        file: File,
        page: Int,
        engine: OcrEngine,
        columns: Int = 0,
        recognize: Boolean = false,
    ): ReflowPage =
        withContext(Dispatchers.IO) {
            val hash = PdfAssembly.sha256(file)
            val signature =
                "$VERSION:${NativeTextCoverage.VERSION}:${PdfGlyphGeometry.VERSION}:$hash:$page:$columns:${engine.signature(UserSettings[SettingKeys.OcrLanguage] != "LATIN", OcrOptions.current())}"
            val key =
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it.toInt() and 255)
                }
            val folder = File(context.cacheDir, "reader-reflow/$key").apply { mkdirs() }
            val manifest = File(folder, "page.json")
            if (!recognize && manifest.exists()) {
                val cached = decode(manifest)
                if (cached.items.all { it.image?.exists() != false }) return@withContext cached
            }
            var blocks = PageTextSource.load(context, file, page, engine, recognize)
            ensureActive()
            val bitmap =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        pdf.openPage(page).use { p ->
                            val scale = 1600f / maxOf(p.width, p.height)
                            Bitmap.createBitmap(
                                    (p.width * scale).toInt().coerceAtLeast(1),
                                    (p.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888,
                                )
                                .also {
                                    it.eraseColor(Color.WHITE)
                                    p.render(
                                        it,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                    )
                                }
                        }
                    }
                }
            try {
                fun crop(region: OcrRegion, name: String): File {
                    val x = (region.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val y = (region.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val w =
                        ((region.right * bitmap.width).toInt() - x).coerceIn(1, bitmap.width - x)
                    val h =
                        ((region.bottom * bitmap.height).toInt() - y).coerceIn(1, bitmap.height - y)
                    val image = Bitmap.createBitmap(bitmap, x, y, w, h)
                    return try {
                        File(folder, name).also { target ->
                            target.outputStream().use {
                                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                    } finally {
                        if (image !== bitmap) image.recycle()
                    }
                }
                val tokens =
                    if (blocks.any { it.source == "native" })
                        PDDocument.load(file).use { PhoneTables.nativeTokens(it, page) }
                    else
                        blocks.flatMap { b ->
                            b.units.map { u ->
                                PhoneTables.Token(
                                    b.text.substring(u.start, u.end),
                                    RectF(u.left, u.top, u.right, u.bottom),
                                )
                            }
                        }
                // Only real ruling lines promote a layout into a table. Free columns stay prose.
                val tables = PhoneTables.detect(bitmap, page + 1, tokens, "lines_strict")
                val tableItems =
                    tables.mapIndexed { index, table ->
                        val region =
                            OcrRegion(
                                table.cells.minOf { it.box.left },
                                table.cells.minOf { it.box.top },
                                table.cells.maxOf { it.box.right },
                                table.cells.maxOf { it.box.bottom },
                            )
                        ReflowItem(
                            "table",
                            region,
                            image = crop(region, "table-$index.png"),
                            table = table,
                        )
                    }
                fun centerInside(block: ReaderTextBlock, region: OcrRegion) =
                    region.contains(
                        PointN((block.left + block.right) / 2, (block.top + block.bottom) / 2)
                    )
                val prose =
                    blocks.filter { block -> tableItems.none { centerInside(block, it.region) } }
                val figures =
                    ReaderImages.regions(file, page)
                        .map { points ->
                            OcrRegion(
                                points.minOf { it.x }.coerceIn(0f, 1f),
                                points.minOf { it.y }.coerceIn(0f, 1f),
                                points.maxOf { it.x }.coerceIn(0f, 1f),
                                points.maxOf { it.y }.coerceIn(0f, 1f),
                            )
                        }
                        .filter { it.width * it.height in 0.002f..0.85f && it.valid() }
                        .filter { r ->
                            tableItems.none { t ->
                                t.region.contains(
                                    PointN((r.left + r.right) / 2, (r.top + r.bottom) / 2)
                                )
                            }
                        }
                        .distinct()
                val pictureItems =
                    figures.mapIndexed { index, r ->
                        ReflowItem("image", r, image = crop(r, "figure-$index.png"))
                    }
                val items = paragraphs(prose, columns) + tableItems + pictureItems
                val ordered =
                    items.sortedWith(
                        compareBy<ReflowItem> {
                                when (columns) {
                                    1 ->
                                        if (it.region.width > .65f) -1
                                        else if (it.region.left < .5f) 0 else 1
                                    2 ->
                                        if (it.region.width > .65f) -1
                                        else if (it.region.left >= .5f) 0 else 1
                                    else -> 0
                                }
                            }
                            .thenBy { it.region.top }
                            .thenBy { it.region.left }
                    )
                val reference =
                    ReflowItem(
                        "reference",
                        OcrRegion(0f, 0f, 1f, 1f),
                        image = crop(OcrRegion(0f, 0f, 1f, 1f), "reference.png"),
                    )
                val result =
                    ReflowPage(page, ordered + reference, hash, blocks.any { it.source == "ocr" })
                ensureActive()
                val atomic = android.util.AtomicFile(manifest)
                val stream = atomic.startWrite()
                try {
                    stream.write(encode(result).toString().toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
                result
            } finally {
                bitmap.recycle()
            }
        }

    internal fun paragraphs(blocks: List<ReaderTextBlock>, columns: Int): List<ReflowItem> {
        val groups = mutableListOf<MutableList<ReaderTextBlock>>()
        ReaderDocument.order(blocks, columns).forEach { next ->
            val group = groups.lastOrNull()
            val previous = group?.lastOrNull()
            val h = previous?.let { (it.bottom - it.top).coerceAtLeast(.003f) } ?: .01f
            val sameColumn =
                previous != null &&
                    kotlin.math.abs(previous.left - next.left) < .035f &&
                    kotlin.math.abs(previous.right - next.right) < .25f
            if (
                previous != null &&
                    sameColumn &&
                    next.top >= previous.top + h * .4f &&
                    next.top - previous.bottom < h * 1.85f &&
                    (next.bottom - next.top) / h in .75f..1.3f
            )
                checkNotNull(group).add(next)
            else groups.add(mutableListOf(next))
        }
        return groups.map { group ->
            val text = buildString {
                group.forEach { line ->
                    if (
                        isNotEmpty() &&
                            last().code < 128 &&
                            line.text.firstOrNull()?.let { it.code < 128 } == true &&
                            !last().isWhitespace()
                    )
                        append(' ')
                    append(line.text)
                }
            }
            ReflowItem(
                "paragraph",
                OcrRegion(
                    group.minOf { it.left },
                    group.minOf { it.top },
                    group.maxOf { it.right },
                    group.maxOf { it.bottom },
                ),
                text,
                source = group.first().source,
            )
        }
    }

    fun encode(page: ReflowPage) =
        JSONObject()
            .put("version", VERSION)
            .put("page", page.page)
            .put("sourceSha256", page.sourceSha256)
            .put("ocr", page.usedOcr)
            .put(
                "items",
                JSONArray().apply {
                    page.items.forEach { item ->
                        put(
                            JSONObject()
                                .put("kind", item.kind)
                                .put(
                                    "region",
                                    JSONArray(
                                        listOf(
                                            item.region.left,
                                            item.region.top,
                                            item.region.right,
                                            item.region.bottom,
                                        )
                                    ),
                                )
                                .put("text", item.text)
                                .put("source", item.source)
                                .put("image", item.image?.name)
                                .put("table", item.table?.let(TableData::json))
                        )
                    }
                },
            )

    private fun decode(file: File): ReflowPage {
        val root = JSONObject(file.readText())
        return ReflowPage(
            root.getInt("page"),
            root.getJSONArray("items").let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    val r = o.getJSONArray("region")
                    ReflowItem(
                        o.getString("kind"),
                        OcrRegion(
                            r.getDouble(0).toFloat(),
                            r.getDouble(1).toFloat(),
                            r.getDouble(2).toFloat(),
                            r.getDouble(3).toFloat(),
                        ),
                        o.optString("text"),
                        o.optString("image")
                            .takeIf { it.isNotBlank() }
                            ?.let { File(file.parentFile, it) },
                        o.optJSONObject("table")?.let(TableData::table),
                        o.optString("source", "native"),
                    )
                }
            },
            root.getString("sourceSha256"),
            root.getBoolean("ocr"),
        )
    }
}

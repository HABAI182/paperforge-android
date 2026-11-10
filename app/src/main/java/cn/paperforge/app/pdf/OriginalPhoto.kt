package cn.paperforge.app.pdf

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.PageResources
import cn.paperforge.app.model.Quad
import java.io.File

/**
 * A page that is still exactly the camera's photo: Original filter, the whole frame, no rotation, deskew, tone or
 * aspect edits, and no processed appearance. Exporting such a page re-encoded it at the export preset's size
 * (2572 px for STANDARD), which halved a 12-megapixel photo's pixels and added a second JPEG generation; it now
 * goes out as the camera saved it. Photos whose orientation lives only in EXIF are left to the rendering path,
 * which applies it, because a PDF viewer would not.
 */
object OriginalPhoto {
    fun untouched(page: PageRow): Boolean {
        if (page.filter != "ORIGINAL" || Quad.decode(page.corners) != Quad.Full) return false
        if (page.rotation != 0 || page.deskewDegrees != 0f || page.brightness != 0f || page.contrast != 1f) return false
        if (page.outputAspect != 0f) return false
        if (PageResources.normalizedAppearance(page) != page) return false
        if (PageResources.metadata(page).has("flatDocument")) return false
        val file = File(page.original)
        return file.isFile && uprightJpeg(file)
    }

    /** The original photo's pixel size, or null when it cannot be read. */
    fun size(page: PageRow): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(page.original, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /** The longest side of the original photo, used to render an Original page at its own resolution. */
    fun nativeEdge(page: PageRow): Int = size(page)?.let { maxOf(it.first, it.second) } ?: 0

    private fun uprightJpeg(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outMimeType != "image/jpeg") return false
        val orientation =
            runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        return orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED
    }
}

package cn.paperforge.app.ocr

import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

interface OcrEngine { suspend fun recognize(page: PageRow,chinese: Boolean=true): String }
class BundledOcrEngine: OcrEngine {
    override suspend fun recognize(page: PageRow,chinese: Boolean): String {
        return layout(page,chinese).text
    }
    suspend fun layout(page: PageRow,chinese: Boolean=true): OcrLayout {
        // OCR needs the crop/rotation, but a display threshold can erase thin print or pencil.
        val bitmap=ScanProcessor.render(page.copy(filter="ORIGINAL",brightness=0f,contrast=1f),3200)
        try {
            var result=recognizeBitmap(bitmap,chinese)
            if(result.text.count { !it.isWhitespace() }<24) {
                val clean=ScanProcessor.transform(bitmap,Quad.Full,"CLEAN")
                try { val alternative=recognizeBitmap(clean,chinese); if(alternative.text.length>result.text.length) result=alternative } finally { clean.recycle() }
            }
            if(result.text.isBlank() && chinese) result=recognizeBitmap(bitmap,false)
            if(result.text.isBlank()) for(angle in listOf(90,270)) {
                val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,android.graphics.Matrix().apply { postRotate(angle.toFloat()) },true)
                try {
                    val alternative=recognizeBitmap(rotated,chinese)
                    if(alternative.text.isNotBlank()) {
                        result=OcrLayout(bitmap.width,bitmap.height,alternative.lines.map { line -> line.copy(corners=line.corners.map { p -> cn.paperforge.app.pdf.PdfCoordinates.unrotate(p,angle) }) }); break
                    }
                } finally { rotated.recycle() }
            }
            return result
        } finally { bitmap.recycle() }
    }
    suspend fun recognizeBitmap(source: Bitmap,chinese: Boolean=true): OcrLayout {
        // Own the input until the native task completes, including coroutine cancellation.
        val bitmap=source.copy(Bitmap.Config.ARGB_8888,false)
        val client=if(chinese) TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val task=try { client.process(InputImage.fromBitmap(bitmap,0)) }
        catch(e: Exception) { client.close(); bitmap.recycle(); throw e }
        try {
            val text=task.await()
            return OcrLayout(bitmap.width,bitmap.height,text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val corners=line.cornerPoints?.toList() ?: line.boundingBox?.let { r -> listOf(android.graphics.Point(r.left,r.top),android.graphics.Point(r.right,r.top),android.graphics.Point(r.right,r.bottom),android.graphics.Point(r.left,r.bottom)) } ?: return@mapNotNull null
                if(corners.size!=4 || line.text.isBlank()) null else OcrLine(line.text,corners.map { PointN((it.x.toFloat()/bitmap.width).coerceIn(0f,1f),(it.y.toFloat()/bitmap.height).coerceIn(0f,1f)) })
            })
        }
        finally {
            // Await cancellation does not cancel ML Kit's native work. Keep its input alive.
            if(task.isComplete) { client.close(); bitmap.recycle() }
            else task.addOnCompleteListener { client.close(); bitmap.recycle() }
        }
    }
}

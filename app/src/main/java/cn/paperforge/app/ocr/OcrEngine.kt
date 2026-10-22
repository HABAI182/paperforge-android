package cn.paperforge.app.ocr

import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

interface OcrEngine { suspend fun recognize(page: PageRow,chinese: Boolean=true): String }
class BundledOcrEngine: OcrEngine {
    override suspend fun recognize(page: PageRow,chinese: Boolean): String {
        val bitmap=ScanProcessor.render(page,2400)
        val client=if(chinese) TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val task=try { client.process(InputImage.fromBitmap(bitmap,0)) }
        catch(e: Exception) { client.close(); bitmap.recycle(); throw e }
        try { return task.await().text }
        finally {
            // Await cancellation does not cancel ML Kit's native work. Keep its input alive.
            if(task.isComplete) { client.close(); bitmap.recycle() }
            else task.addOnCompleteListener { client.close(); bitmap.recycle() }
        }
    }
}

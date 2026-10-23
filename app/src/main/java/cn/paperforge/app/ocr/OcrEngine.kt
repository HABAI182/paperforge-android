package cn.paperforge.app.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlin.math.*

interface OcrEngine {
    val id: String
    suspend fun layout(page: PageRow,chinese: Boolean=true): OcrLayout
    suspend fun recognizeBitmap(source: Bitmap,chinese: Boolean=true): OcrLayout
    suspend fun recognize(page: PageRow,chinese: Boolean=true): String=layout(page,chinese).text
}

/** Bundled models, no download or GMS UI. Shared recognizers serialize native work. */
class BundledOcrEngine: OcrEngine {
    override val id=ID
    override suspend fun layout(page: PageRow,chinese: Boolean): OcrLayout {
        val bitmap=ScanProcessor.render(page.copy(filter="ORIGINAL",brightness=0f,contrast=1f),3200)
        return try {
            val result=recognizeBitmap(bitmap,chinese)
            result.copy(sourceRevision=page.revision,deskewDegrees=if(page.deskewDegrees==0f) result.deskewDegrees else 0f)
        } finally { bitmap.recycle() }
    }
    override suspend fun recognizeBitmap(source: Bitmap,chinese: Boolean): OcrLayout {
        var best=pass(source,chinese,"original")
        // 0/missing confidence is unknown; these observed scores are not calibrated probabilities.
        val confidence=best.meanConfidence ?: .65f
        val medianAngle=median(best.lines.mapNotNull { it.angle })
        if(best.text.count { it.isLetterOrDigit() }<24 || abs(medianAngle)>45) {
            val inferred=((-medianAngle/90).roundToInt()*90%360+360)%360
            val angles=if(inferred!=0 && best.lines.size>=2) listOf(inferred) else listOf(180,90,270)
            for(angle in angles) {
                currentCoroutineContext().ensureActive()
                val rotated=Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply { postRotate(angle.toFloat()) },true)
                try {
                    val candidate=pass(rotated,chinese,"rotate-$angle")
                    if(score(candidate)>score(best)*1.04+.5) best=unrotate(candidate,source.width,source.height,angle)
                } finally { if(rotated!==source) rotated.recycle() }
            }
        }
        val rotation=best.orientationCorrection
        val upright=if(rotation==0) source else Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply { postRotate(rotation.toFloat()) },true)
        try {
            var normalized=if(rotation==0) best else best.rotated(rotation)
            val heights=normalized.lines.map { l -> hypot((l.corners[3].x-l.corners[0].x)*upright.width,(l.corners[3].y-l.corners[0].y)*upright.height) }
            val smallType=median(heights)<ScanConfig.int("ocrMinCharacterHeight",48)
            if((normalized.meanConfidence ?: .65f)<ScanConfig.float("ocrLowConfidence",.82f) || smallType) {
                val clean=ScanProcessor.transform(upright,Quad.Full,"CLEAN")
                try { normalized=merge(normalized,pass(clean,chinese,"clean")) } finally { clean.recycle() }
                if(upright.width.toLong()*upright.height<6_000_000) {
                    val factor=min(2.0,sqrt(12_000_000.0/(upright.width*upright.height)))
                    val enlarged=Bitmap.createScaledBitmap(upright,(upright.width*factor).toInt(),(upright.height*factor).toInt(),true)
                    try { normalized=merge(normalized,pass(enlarged,chinese,"upscale")) } finally { if(enlarged!==upright) enlarged.recycle() }
                }
                val lineAlternative=recognizeLineTiles(upright,normalized,chinese)
                if(lineAlternative!=null) normalized=merge(normalized,lineAlternative)
            }
            val skew=median(normalized.lines.mapNotNull { it.angle })
            val spread=median(normalized.lines.mapNotNull { it.angle }.map { abs(it-skew) })
            if(abs(skew) in .6f..8f && spread<1.5f && normalized.lines.size>=3) {
                val geometry=DeskewGeometry(upright.width,upright.height,-skew)
                val straight=cn.paperforge.app.imaging.PageOrientation.rotate(upright,-skew)
                try {
                    val candidate=pass(straight,chinese,"deskew")
                    if(candidate.text.length>=normalized.text.length*.95 && (candidate.meanConfidence ?: 0f)>=(normalized.meanConfidence ?: 0f)-.02f)
                        normalized=geometry.map(candidate,false).copy(deskewDegrees=-skew)
                } finally { straight.recycle() }
            }
            val warnings=buildList { if(normalized.lines.any { it.confidence==null }) add("模型未提供部分置信度");if(normalized.lines.any { (it.confidence ?: 1f)<.65f }) add("部分文字需要人工核对") }
            val result=OcrReadingOrder.arrange(OcrPageVoting.apply(normalized.copy(width=upright.width,height=upright.height,warnings=warnings)))
            return if(rotation==0) result else unrotate(result,source.width,source.height,rotation)
        } finally { if(upright!==source) upright.recycle() }
    }
    private suspend fun pass(source: Bitmap,chinese: Boolean,variant: String): OcrLayout=mutex.withLock {
        currentCoroutineContext().ensureActive()
        val bitmap=source.copy(Bitmap.Config.ARGB_8888,false)
        val task=try { (if(chinese) chineseClient else latinClient).process(InputImage.fromBitmap(bitmap,0)) }
        catch(e: Exception) { bitmap.recycle();throw e }
        try {
            val text=task.await()
            fun points(corners: Array<android.graphics.Point>?,box: android.graphics.Rect?): List<PointN> {
                val p=corners?.toList() ?: box?.let { listOf(android.graphics.Point(it.left,it.top),android.graphics.Point(it.right,it.top),android.graphics.Point(it.right,it.bottom),android.graphics.Point(it.left,it.bottom)) } ?: return emptyList()
                return p.map { PointN(it.x.toFloat()/bitmap.width,it.y.toFloat()/bitmap.height) }
            }
            fun confidence(value: Float)=value.takeIf { it.isFinite() && it>0f && it<=1f }
            val lines=text.textBlocks.flatMapIndexed { blockIndex,block -> block.lines.mapNotNull { line ->
                val corners=points(line.cornerPoints,line.boundingBox)
                if(corners.size!=4 || line.text.isBlank()) null else OcrLine(line.text,corners,confidence(line.confidence),line.angle,blockIndex,blockIndex,line.elements.mapNotNull { e ->
                    val p=points(e.cornerPoints,e.boundingBox)
                    if(p.size!=4) null else OcrElement(e.text,p,confidence(e.confidence),e.angle)
                },variant)
            } }
            OcrLayout(bitmap.width,bitmap.height,lines,ID,"Chinese16.0.1/Latin16.0.1")
        } finally {
            // Cancellation cannot cancel ML Kit. Retain input and serialization until native completion.
            if(!task.isComplete) withContext(NonCancellable) { runCatching { task.await() } }
            bitmap.recycle()
        }
    }
    /** Batch uncertain lines at model-friendly character height; no dictionary/ground-truth replacement. */
    private suspend fun recognizeLineTiles(source: Bitmap,layout: OcrLayout,chinese: Boolean): OcrLayout? {
        data class Tile(val source: android.graphics.Rect,val destination: android.graphics.Rect)
        val selected=layout.lines.filter { (it.confidence ?: 1f)<.70f }.take(24)
        if(selected.isEmpty()) return null
        val tiles=mutableListOf<Tile>();var y=16
        selected.forEach { line ->
            val left=floor(line.corners.minOf { it.x }*source.width).toInt();val top=floor(line.corners.minOf { it.y }*source.height).toInt()
            val right=ceil(line.corners.maxOf { it.x }*source.width).toInt();val bottom=ceil(line.corners.maxOf { it.y }*source.height).toInt()
            val pad=max(3,(bottom-top)/6)
            val rect=android.graphics.Rect((left-pad).coerceIn(0,source.width-1),(top-pad).coerceIn(0,source.height-1),(right+pad).coerceIn(1,source.width),(bottom+pad).coerceIn(1,source.height))
            if(rect.width()>0 && rect.height()>0) {
                val factor=min(80.0/rect.height(),1900.0/rect.width()).coerceAtMost(3.0)
                val destination=android.graphics.Rect(24,y,24+(rect.width()*factor).roundToInt(),y+(rect.height()*factor).roundToInt())
                tiles.add(Tile(rect,destination));y=destination.bottom+32
            }
        }
        if(tiles.isEmpty()) return null
        val bitmap=Bitmap.createBitmap(tiles.maxOf { it.destination.right }+24,y,Bitmap.Config.ARGB_8888)
        try {
            val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
            val paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            tiles.forEach { canvas.drawBitmap(source,it.source,it.destination,paint) }
            val result=pass(bitmap,chinese,"line-scale")
            fun mapped(points: List<PointN>,tile: Tile)=points.map { p -> PointN((tile.source.left+(p.x*bitmap.width-tile.destination.left)*tile.source.width()/tile.destination.width())/source.width,(tile.source.top+(p.y*bitmap.height-tile.destination.top)*tile.source.height()/tile.destination.height())/source.height) }
            return result.copy(width=source.width,height=source.height,lines=result.lines.mapNotNull { l ->
                val center=l.corners.map { it.y }.average()*bitmap.height
                val tile=tiles.firstOrNull { center>=it.destination.top && center<=it.destination.bottom } ?: return@mapNotNull null
                l.copy(corners=mapped(l.corners,tile),elements=l.elements.map { e -> e.copy(corners=mapped(e.corners,tile)) })
            })
        } finally { bitmap.recycle() }
    }
    private fun score(layout: OcrLayout): Double {
        val chars=layout.text.count { it.isLetterOrDigit() };val confidence=layout.meanConfidence ?: .65f
        val angle=abs(median(layout.lines.mapNotNull { it.angle }))
        val horizontal=.1+.9*exp(-angle/20.0)
        return ln(1.0+chars)*(confidence*confidence)*100*horizontal
    }
    private fun merge(base: OcrLayout,alternative: OcrLayout): OcrLayout {
        if(base.lines.isEmpty()) return alternative.copy(width=base.width,height=base.height)
        fun bounds(p: List<PointN>)=floatArrayOf(p.minOf { it.x },p.minOf { it.y },p.maxOf { it.x },p.maxOf { it.y })
        fun overlap(a: OcrLine,b: OcrLine): Float {
            val x=bounds(a.corners);val y=bounds(b.corners)
            val intersection=max(0f,min(x[2],y[2])-max(x[0],y[0]))*max(0f,min(x[3],y[3])-max(x[1],y[1]))
            return intersection/max(.000001f,(x[2]-x[0])*(x[3]-x[1])+(y[2]-y[0])*(y[3]-y[1])-intersection)
        }
        return base.copy(lines=base.lines.map { line ->
            val candidate=alternative.lines.maxByOrNull { overlap(line,it) }
            if(candidate==null || overlap(line,candidate)<=.55f) line else {
                val hypotheses=(line.hypotheses.ifEmpty { listOf(OcrHypothesis(line.text,line.confidence,line.variant)) }+OcrHypothesis(candidate.text,candidate.confidence,candidate.variant)).distinctBy { it.variant }
                val selected=if(candidate.confidence!=null && candidate.confidence>(line.confidence ?: 0f)+.025f) candidate.copy(block=line.block,paragraph=line.paragraph) else line
                val original=hypotheses.first()
                val strongest=hypotheses.maxBy { it.confidence ?: 0f }
                val tieText=if((strongest.confidence ?: 0f)>(original.confidence ?: 0f)+.025f) strongest.text else original.text
                val ballot=hypotheses.filter { it.text.length==selected.text.length }
                val voted=if(ballot.size>=3) selected.text.indices.map { i ->
                    val counts=ballot.groupingBy { it.text[i] }.eachCount();val top=counts.maxBy { it.value }
                    if(top.value>ballot.size/2) top.key else if(tieText.length==selected.text.length) tieText[i] else selected.text[i]
                }.joinToString("") else selected.text
                if(voted!=selected.text) selected.copy(text=voted,elements=emptyList(),variant="variant-vote",hypotheses=hypotheses)
                else selected.copy(hypotheses=hypotheses)
            }
        })
    }
    private fun unrotate(layout: OcrLayout,width: Int,height: Int,angle: Int)=layout.rotated((360-angle)%360).copy(width=width,height=height,orientationCorrection=angle)
    private fun median(values: List<Float>)=if(values.isEmpty()) 0f else values.sorted()[values.size/2]
    companion object {
        const val ID="mlkit-bundled-v030"
        private val mutex=Mutex()
        private val chineseClient by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
        private val latinClient by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    }
}

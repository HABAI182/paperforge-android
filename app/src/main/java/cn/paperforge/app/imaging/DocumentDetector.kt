package cn.paperforge.app.imaging

import android.graphics.Bitmap
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class DetectionResult(
    val quad: Quad?, val confidence: Float, val method: String,
    val diagnostics: Map<String, Float> = emptyMap(), val elapsedMs: Float = 0f
)

/** Independent PaperForge implementation. Technique references (not copied source):
 * OSS-DocumentScanner@ff05fc52b601d1cccb4076f3f6c3318f74e72f0c (MIT),
 * open-android-doc-scanner@3ba5c50a64f9be8eaeec47d263b360488e58835b (Apache-2.0).
 * Each accepted algorithm is mirrored in scripts/bench/detector_v2.py.
 */
object DocumentDetector {
    private data class Candidate(val quad: Quad, val method: String)
    private data class Score(val value: Float, val evidence: Map<String, Float>)

    fun detect(bitmap: Bitmap, preview: Boolean = false): DetectionResult {
        val mat = Mat()
        return try { Utils.bitmapToMat(bitmap, mat); detect(mat, preview) } finally { mat.release() }
    }

    fun detect(source: Mat, preview: Boolean = false): DetectionResult {
        val started = System.nanoTime()
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also { owned.add(it) }
        val candidates = mutableListOf<Candidate>()
        try {
            val small = mat(); val rgb = mat(); val lab = mat(); val gray = mat(); val hsv = mat()
            val edge = mat(); val support = mat(); val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).also { owned.add(it) }
            val maximum = if (preview) ScanConfig.int("previewDetectorMaxEdge", 720) else ScanConfig.int("detectorMaxEdge", 1000)
            val scale = min(1.0, maximum.toDouble() / max(source.cols(), source.rows()))
            Imgproc.resize(source, small, Size(), scale, scale, Imgproc.INTER_AREA)
            when (small.channels()) {
                1 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_GRAY2RGB)
                4 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
                else -> small.copyTo(rgb)
            }
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val w = rgb.cols(); val h = rgb.rows()
            val channels = mutableListOf<Mat>()
            Core.split(lab, channels); owned.addAll(channels)
            val saturation = mat(); Core.extractChannel(hsv, saturation, 1)
            val selected = mutableListOf(channels[0])
            for (channel in listOf(channels[1], channels[2], saturation)) {
                val bytes = ByteArray(w * h); channel.get(0, 0, bytes)
                val histogram = histogram(bytes)
                if (quantile(histogram, .97f) - quantile(histogram, .03f) > 8 && (!preview || selected.size < 3)) selected.add(channel)
            }
            Mat.zeros(h, w, CvType.CV_8UC1).also { it.copyTo(edge); it.release() }

            fun collect(mask: Mat, method: String) {
                val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat()
                try {
                    Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.sortedByDescending { Imgproc.contourArea(it) }.take(ScanConfig.int("candidateLimit", 40)).forEach { contour ->
                        if (Imgproc.contourArea(contour) < w * h * ScanConfig.float("minimumArea", .07f)) return@forEach
                        val indices = MatOfInt(); val curve = MatOfPoint2f(); val approx = MatOfPoint2f()
                        try {
                            Imgproc.convexHull(contour, indices)
                            val points = contour.toArray(); val hull = indices.toArray().map { points[it] }.toTypedArray()
                            curve.fromArray(*hull)
                            val perimeter = Imgproc.arcLength(curve, true)
                            for (epsilon in listOf(.008, .015, .025, .04, .06)) {
                                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                                if (approx.total() == 4L) {
                                    normalized(approx.toArray(), w, h)?.let { candidates.add(Candidate(it, method)) }
                                    break
                                }
                            }
                            val rectangle = Imgproc.minAreaRect(curve)
                            if (abs(Imgproc.contourArea(curve)) / max(1.0, rectangle.size.area()) > .8) {
                                val corners = Array(4) { Point() }; rectangle.points(corners)
                                normalized(corners, w, h)?.let { candidates.add(Candidate(it, "$method-rect")) }
                            }
                        } finally { indices.release(); curve.release(); approx.release() }
                    }
                } finally { contours.forEach { it.release() }; hierarchy.release() }
            }

            val blur = mat(); val canny = mat(); val mask = mat(); val closed = mat()
            selected.forEachIndexed { index, channel ->
                Imgproc.GaussianBlur(channel, blur, Size(5.0, 5.0), 0.0)
                val gx = mat(); val gy = mat(); val magnitude = mat()
                Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0); Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1)
                Core.magnitude(gx, gy, magnitude)
                val gradient = FloatArray(w * h); magnitude.get(0, 0, gradient)
                val gradientHistogram = IntArray(1024)
                gradient.forEach { if(it>4) gradientHistogram[min(1023,it.toInt())]++ }
                val median = if(gradientHistogram.sum()==0) 24f else quantile(gradientHistogram,.5f).toFloat()
                Imgproc.Canny(blur, canny, max(8f, median * .6f).toDouble(), max(24f, min(180f, median * 1.5f)).toDouble())
                Core.bitwise_or(edge, canny, edge)
                Imgproc.morphologyEx(canny, closed, Imgproc.MORPH_CLOSE, kernel); collect(closed, "channel-$index")
                if (index == 0) {
                    Imgproc.Canny(blur, canny, 8.0, 24.0); Core.bitwise_or(edge, canny, edge)
                    Imgproc.morphologyEx(canny, closed, Imgproc.MORPH_CLOSE, kernel); collect(closed, "weak-luma")
                    val bytes = ByteArray(w * h); blur.get(0, 0, bytes)
                    val global = histogram(bytes); val border = IntArray(256)
                    for (y in 0 until min(20, h)) for (x in 0 until w) { border[bytes[y*w+x].toInt() and 255]++; border[bytes[(h-1-y)*w+x].toInt() and 255]++ }
                    val paperLevel = quantile(global, .92f); val background = quantile(border, .5f)
                    if (paperLevel - background > 3) {
                        Imgproc.threshold(blur, mask, (paperLevel + background) / 2.0, 255.0, Imgproc.THRESH_BINARY)
                        Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel); collect(closed, "paper-luma")
                    }
                }
                Imgproc.threshold(blur, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel); collect(closed, "threshold-$index")
                Core.bitwise_not(mask, mask); Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel); collect(closed, "inverse-$index")
                gx.release(); gy.release(); magnitude.release()
            }
            val labBytes = ByteArray(w * h * 3); lab.get(0, 0, labBytes)
            val grayBytes = ByteArray(w * h); gray.get(0, 0, grayBytes)
            val borderHist = Array(3) { IntArray(256) }; val band = max(2, min(w, h) / 25)
            for (y in 0 until h step 2) for (x in 0 until w step 2) if (x < band || x >= w - band || y < band || y >= h - band) {
                for (c in 0..2) borderHist[c][labBytes[(y*w+x)*3+c].toInt() and 255]++
            }
            val background = FloatArray(3) { quantile(borderHist[it], .5f).toFloat() }
            val distance = ByteArray(w * h)
            for (i in distance.indices) {
                val dl = ((labBytes[i*3].toInt() and 255) - background[0]) * .5f
                val da = (labBytes[i*3+1].toInt() and 255) - background[1]
                val db = (labBytes[i*3+2].toInt() and 255) - background[2]
                distance[i] = min(255, (sqrt(dl*dl+da*da+db*db)*3).toInt()).toByte()
            }
            val distances = mat(); distances.create(h, w, CvType.CV_8UC1); distances.put(0, 0, distance)
            for (threshold in listOf(-1, 12, 24, 45)) {
                Imgproc.threshold(distances, mask, max(0, threshold).toDouble(), 255.0, Imgproc.THRESH_BINARY + if (threshold < 0) Imgproc.THRESH_OTSU else 0)
                Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel); collect(closed, "border-distance-$threshold")
            }
            Imgproc.dilate(edge, support, kernel)
            val supportBytes = ByteArray(w*h); support.get(0, 0, supportBytes)
            val seen = hashSetOf<String>()
            fun key(q: Quad) = q.points.joinToString(",") { "${(it.x*(w-1)/4).roundToInt()}:${(it.y*(h-1)/4).roundToInt()}" }
            fun evaluate(candidate: Candidate): Score? {
                if (!seen.add(key(candidate.quad))) return null
                return score(candidate.quad, w, h, grayBytes, labBytes, supportBytes)
            }
            var best: Candidate? = null; var bestScore: Score? = null
            candidates.forEach { candidate -> evaluate(candidate)?.let { if (it.value > (bestScore?.value ?: -1f)) { best=candidate; bestScore=it } } }
            if (!preview && (bestScore?.value ?: 0f) < ScanConfig.float("houghFallbackScore",.88f)) {
                lineCandidates(edge, w, h).forEach { candidate -> evaluate(candidate)?.let { measured ->
                    val penalized = measured.copy(value = measured.value - .015f)
                    if (penalized.value > (bestScore?.value ?: -1f)) { best=candidate; bestScore=penalized }
                } }
            }
            val chosen = best; val measured = bestScore
            val elapsed = (System.nanoTime()-started)/1e6f
            if (chosen == null || measured == null) return DetectionResult(null,0f,"none",elapsedMs=elapsed)
            val confidence = ((measured.value-.35f)/.6f).coerceIn(0f,1f)
            if (measured.value < ScanConfig.float("detectorScoreMinimum",.71f) || (measured.evidence["edge_support"] ?: 0f) < ScanConfig.float("detectorEdgeMinimum",.25f)) return DetectionResult(null,confidence,chosen.method,measured.evidence,elapsed)
            val refined = if (!preview && max(source.cols(),source.rows()) > maximum * 1.2) refine(source,chosen.quad) else chosen.quad
            return DetectionResult(refined,confidence,chosen.method + if(refined != chosen.quad) "+refined" else "",measured.evidence,elapsed)
        } finally { owned.forEach { it.release() } }
    }

    private fun normalized(points: Array<Point>, w: Int, h: Int): Quad? {
        if (points.any { it.x < -1 || it.x > w || it.y < -1 || it.y > h }) return null
        val cx=points.map { it.x }.average(); val cy=points.map { it.y }.average()
        val sorted=points.sortedBy { atan2(it.y-cy,it.x-cx) }
        val start=sorted.indices.minBy { sorted[it].x+sorted[it].y }
        return Quad(List(4) { n -> val p=sorted[(n+start)%4]; PointN((p.x/(w-1)).toFloat().coerceIn(0f,1f),(p.y/(h-1)).toFloat().coerceIn(0f,1f)) }).takeIf { it.valid() }
    }

    private fun score(q: Quad,w: Int,h: Int,gray: ByteArray,lab: ByteArray,support: ByteArray): Score? {
        val p=q.points.map { Point(it.x*(w-1).toDouble(),it.y*(h-1).toDouble()) }
        val area=q.area
        if (area < ScanConfig.float("minimumArea",.07f) || area > .9999f) return null
        val lengths=List(4) { i -> hypot(p[(i+1)%4].x-p[i].x,p[(i+1)%4].y-p[i].y) }
        if(lengths.min()<min(w,h)*.06) return null
        var cannyHits=0;var bandHits=0;val contrasts=mutableListOf<Float>();var cosine=0.0
        for (i in 0..3) {
            val a=p[i];val b=p[(i+1)%4];val dx=b.x-a.x;val dy=b.y-a.y
            val nx=-dy/lengths[i];val ny=dx/lengths[i]
            for (j in 0 until 48) {
                val t=.04+.92*j/47;val x=a.x+dx*t;val y=a.y+dy*t
                if(support[y.roundToInt().coerceIn(0,h-1)*w+x.roundToInt().coerceIn(0,w-1)].toInt()!=0) cannyHits++
                val ix=x+nx*4;val iy=y+ny*4;val ox=x-nx*4;val oy=y-ny*4
                if(ix>=0 && ix<w && iy>=0 && iy<h && ox>=0 && ox<w && oy>=0 && oy<h) {
                    val inside=(iy.toInt()*w+ix.toInt())*3;val outside=(oy.toInt()*w+ox.toInt())*3
                    var delta=0f
                    for(c in 0..2) { val d=(lab[inside+c].toInt() and 255)-(lab[outside+c].toInt() and 255);delta+=d*d }
                    delta=sqrt(delta);contrasts.add(delta);if(delta>4) bandHits++
                }
            }
            val prior=p[(i+3)%4];val px=prior.x-a.x;val py=prior.y-a.y
            cosine+=abs((px*dx+py*dy)/(hypot(px,py)*lengths[i]))
        }
        val hist=IntArray(256);var count=0
        val step=max(4,min(w,h)/90)
        val minX=(p.minOf { it.x }).toInt().coerceAtLeast(0);val maxX=p.maxOf { it.x }.toInt().coerceAtMost(w-1)
        val minY=(p.minOf { it.y }).toInt().coerceAtLeast(0);val maxY=p.maxOf { it.y }.toInt().coerceAtMost(h-1)
        for(y in minY..maxY step step) for(x in minX..maxX step step) {
            if((0..3).all { i -> val a=p[i];val b=p[(i+1)%4];(b.x-a.x)*(y-a.y)-(b.y-a.y)*(x-a.x)>=0 }) {
                hist[gray[y*w+x].toInt() and 255]++;count++
            }
        }
        if(count==0) return null
        val paper=quantile(hist,.85f);val paperFraction=(max(0,paper-24)..255).sumOf { hist[it] }.toFloat()/count
        val edge=max(cannyHits,bandHits)/192f
        contrasts.sort();val contrast=if(contrasts.isEmpty()) 0f else min(1f,contrasts[contrasts.size/2]/22f)
        val aspect=max(lengths[0],lengths[2])/max(lengths[1],lengths[3])
        val aspectScore=listOf(.707,1.414,.773,1.294,.63,1.585,.3,3.3).maxOf { exp(-abs(ln(aspect/it))*.7) }.toFloat()
        val shape=(1-cosine/4).toFloat().coerceAtLeast(0f)
        var value=.34f*edge+.15f*contrast+.23f*paperFraction+.12f*shape+.04f*aspectScore+.12f*min(1f,area/.4f)
        if(edge<.3f) value*=.6f
        return Score(value,mapOf("edge_support" to edge,"edge_contrast" to contrast,"paper_fraction" to paperFraction,"shape" to shape,"area" to area))
    }

    private fun lineCandidates(edge: Mat,w: Int,h: Int): List<Candidate> {
        val lines=Mat();val result=mutableListOf<Candidate>()
        try {
            Imgproc.HoughLinesP(edge,lines,1.0,Math.PI/180,70,min(w,h)*.22,min(w,h)*.06)
            val segments=(0 until lines.rows()).map { lines.get(it,0) }.sortedByDescending { hypot(it[2]-it[0],it[3]-it[1]) }.take(24)
            val horizontal=segments.filter { abs(it[2]-it[0])>abs(it[3]-it[1])*1.3 }.take(8)
            val vertical=segments.filter { abs(it[3]-it[1])>abs(it[2]-it[0])*1.3 }.take(8)
            fun pairs(items: List<DoubleArray>,verticalAxis: Boolean): List<Pair<DoubleArray,DoubleArray>> {
                val pairs=mutableListOf<Pair<DoubleArray,DoubleArray>>();val offset=if(verticalAxis) 0 else 1
                for(i in items.indices) for(j in i+1 until items.size) if(abs((items[i][offset]+items[i][offset+2]-items[j][offset]-items[j][offset+2])/2)>(if(verticalAxis) w*.15 else h*.25)) pairs.add(items[i] to items[j])
                return pairs.take(8)
            }
            for(hs in pairs(horizontal,false)) for(vs in pairs(vertical,true)) {
                val corners=listOf(hs.first to vs.first,hs.first to vs.second,hs.second to vs.first,hs.second to vs.second).map { (a,b) -> intersection(a,b) }
                if(corners.all { it!=null }) normalized(corners.filterNotNull().toTypedArray(),w,h)?.let { result.add(Candidate(it,"line-fit")) }
            }
        } finally { lines.release() }
        return result
    }

    private fun intersection(a: DoubleArray,b: DoubleArray): Point? {
        val x1=a[0];val y1=a[1];val x2=a[2];val y2=a[3];val x3=b[0];val y3=b[1];val x4=b[2];val y4=b[3]
        val det=(x1-x2)*(y3-y4)-(y1-y2)*(x3-x4)
        if(abs(det)<1e-8) return null
        return Point(((x1*y2-y1*x2)*(x3-x4)-(x1-x2)*(x3*y4-y3*x4))/det,((x1*y2-y1*x2)*(y3-y4)-(y1-y2)*(x3*y4-y3*x4))/det)
    }

    /** Four narrow bands at decoded still resolution; no full-size gradient matrices. */
    private fun refine(source: Mat,quad: Quad): Quad {
        val w=source.cols();val h=source.rows();val channels=source.channels();val bytes=ByteArray(w*h*channels);source.get(0,0,bytes)
        fun gradient(x: Double,y: Double,nx: Double,ny: Double): Double {
            val ax=(x+nx*2).roundToInt();val ay=(y+ny*2).roundToInt();val bx=(x-nx*2).roundToInt();val by=(y-ny*2).roundToInt()
            if(ax !in 0 until w || bx !in 0 until w || ay !in 0 until h || by !in 0 until h) return -1.0
            var total=0.0
            for(c in 0 until min(channels,3)) { val d=(bytes[(ay*w+ax)*channels+c].toInt() and 255)-(bytes[(by*w+bx)*channels+c].toInt() and 255);total+=d*d }
            return sqrt(total)
        }
        val lines=mutableListOf<DoubleArray>();val band=max(3,(max(w,h)*ScanConfig.float("refineBandFraction",.008f)).toInt())
        for(i in 0..3) {
            val a=quad.points[i];val b=quad.points[(i+1)%4];val dx=(b.x-a.x)*(w-1);val dy=(b.y-a.y)*(h-1);val length=hypot(dx,dy)
            val nx=-dy/length;val ny=dx/length;val points=mutableListOf<Point>()
            for(j in 0 until 56) {
                val t=.04+.92*j/55;val x=a.x*(w-1)+dx*t;val y=a.y*(h-1)+dy*t
                var best=0.0;var position=0
                for(offset in -band..band step max(1,band/12)) {
                    val strength=gradient(x+nx*offset,y+ny*offset,nx.toDouble(),ny.toDouble())*exp(-offset.toDouble().pow(2)/(band*band*.64))
                    if(strength>best) { best=strength;position=offset }
                }
                if(best>5) points.add(Point(x+nx*position,y+ny*position))
            }
            if(points.size<12) return quad
            val curve=MatOfPoint2f(*points.toTypedArray());val fit=Mat()
            try { Imgproc.fitLine(curve,fit,Imgproc.DIST_HUBER,0.0,.01,.01);val values=FloatArray(4);fit.get(0,0,values);lines.add(doubleArrayOf(values[2].toDouble(),values[3].toDouble(),(values[2]+values[0]*w).toDouble(),(values[3]+values[1]*w).toDouble())) }
            finally { curve.release();fit.release() }
        }
        val corners=(0..3).map { intersection(lines[(it+3)%4],lines[it]) ?: return quad }
        val result=Quad(corners.map { PointN((it.x/(w-1)).toFloat(),(it.y/(h-1)).toFloat()) })
        if(!result.valid() || result.area/quad.area !in .90f..1.10f || result.points.zip(quad.points).any { (a,b) -> hypot(a.x-b.x,a.y-b.y)>.025f }) return quad
        return result
    }

    private fun histogram(bytes: ByteArray)=IntArray(256).also { h -> bytes.forEach { h[it.toInt() and 255]++ } }
    private fun quantile(hist: IntArray,fraction: Float): Int {
        val target=max(1,(hist.sum()*fraction).toInt());var count=0
        for(i in hist.indices) { count+=hist[i];if(count>=target) return i }
        return 255
    }
}

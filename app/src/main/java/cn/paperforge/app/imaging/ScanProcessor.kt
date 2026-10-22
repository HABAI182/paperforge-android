package cn.paperforge.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

object ScanProcessor {
    val filters=listOf("ORIGINAL","AUTO","GRAY","BW","COLOR")
    fun decode(file: File, maxEdge: Int=2400): Bitmap {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "无法读取这张图片" }
        var sample=1
        while(max(bounds.outWidth,bounds.outHeight)/sample>maxEdge*1.4) sample*=2
        val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("图片解码失败")
        val exif=ExifInterface(file)
        val m=Matrix()
        when(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)) {
            2 -> m.setScale(-1f,1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f,-1f)
            5 -> { m.setRotate(90f); m.postScale(-1f,1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(270f); m.postScale(-1f,1f) }
            8 -> m.setRotate(270f)
        }
        return if(m.isIdentity) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,m,true).also { if(it!==bitmap) bitmap.recycle() }
    }

    fun detect(bitmap: Bitmap): Quad? {
        val rgba=Mat()
        try { Utils.bitmapToMat(bitmap,rgba); return detectMat(rgba) } finally { rgba.release() }
    }
    fun detectMat(source: Mat): Quad? {
        val small=Mat(); val gray=Mat(); val blur=Mat(); val edges=Mat(); val hierarchy=Mat(); val contours=mutableListOf<MatOfPoint>()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(5.0,5.0))
        try {
            val scale=720.0/max(source.cols(),source.rows())
            Imgproc.resize(source,small,Size(),scale,scale)
            if(small.channels()==1) small.copyTo(gray) else Imgproc.cvtColor(small,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray,blur,Size(5.0,5.0),0.0)
            Imgproc.Canny(blur,edges,45.0,135.0)
            Imgproc.morphologyEx(edges,edges,Imgproc.MORPH_CLOSE,kernel)
            Imgproc.findContours(edges,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
            return contours.sortedByDescending { Imgproc.contourArea(it) }.take(12).mapNotNull { contour ->
                val c=MatOfPoint2f(*contour.toArray()); val approx=MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(c,approx,.025*Imgproc.arcLength(c,true),true)
                    val p=approx.toArray()
                    if(p.size!=4) null else Quad.ordered(p.map { PointN((it.x/small.cols()).toFloat().coerceIn(0f,1f),(it.y/small.rows()).toFloat().coerceIn(0f,1f)) }).takeIf { it.valid() && it.area in .12f.. .98f }
                } finally { c.release(); approx.release() }
            }.maxByOrNull { it.area }
        } finally { listOf(small,gray,blur,edges,hierarchy,kernel).forEach { it.release() }; contours.forEach { it.release() } }
    }

    fun render(page: PageRow,maxEdge: Int=2400): Bitmap {
        val original=decode(File(page.original),maxEdge)
        try { return transform(original,Quad.decode(page.corners),page.filter,page.rotation,page.brightness,page.contrast) }
        finally { original.recycle() }
    }

    fun transform(bitmap: Bitmap,quad: Quad,filter: String,rotation: Int=0,brightness: Float=0f,contrast: Float=1f): Bitmap {
        require(quad.valid()) { "裁切四角不能交叉或重合" }
        val w=bitmap.width.toFloat(); val h=bitmap.height.toFloat()
        val p=quad.points.map { Point((it.x*(w-1)).toDouble(),(it.y*(h-1)).toDouble()) }
        fun dist(a: Point,b: Point)=hypot(a.x-b.x,a.y-b.y)
        val outW=max(dist(p[0],p[1]),dist(p[3],p[2])).roundToInt().coerceAtLeast(32)
        val outH=max(dist(p[0],p[3]),dist(p[1],p[2])).roundToInt().coerceAtLeast(32)
        val source=Mat(); val warped=Mat(); val rgb=Mat(); val gray=Mat(); val dst=Mat()
        val srcPoints=MatOfPoint2f(*p.toTypedArray())
        val dstPoints=MatOfPoint2f(Point(0.0,0.0),Point((outW-1).toDouble(),0.0),Point((outW-1).toDouble(),(outH-1).toDouble()),Point(0.0,(outH-1).toDouble()))
        val matrix=Imgproc.getPerspectiveTransform(srcPoints,dstPoints)
        try {
            Utils.bitmapToMat(bitmap,source)
            Imgproc.warpPerspective(source,warped,matrix,Size(outW.toDouble(),outH.toDouble()),Imgproc.INTER_CUBIC,Core.BORDER_REPLICATE)
            Imgproc.cvtColor(warped,rgb,Imgproc.COLOR_RGBA2RGB)
            when(filter) {
                "GRAY","BW","AUTO" -> {
                    Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY)
                    when(filter) {
                        "BW" -> Imgproc.adaptiveThreshold(gray,dst,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY,41,13.0)
                        "AUTO" -> {
                            // Local contrast keeps faint pencil lines; unlike thresholding it preserves shades.
                            val clahe=Imgproc.createCLAHE(2.0,Size(8.0,8.0))
                            clahe.apply(gray,dst); clahe.collectGarbage()
                        }
                        else -> gray.copyTo(dst)
                    }
                }
                "COLOR" -> rgb.convertTo(dst,-1,1.12,6.0)
                else -> rgb.copyTo(dst)
            }
            if(brightness!=0f || contrast!=1f) dst.convertTo(dst,-1,contrast.toDouble(),brightness*100.0)
            when(((rotation%360)+360)%360) {
                90 -> Core.rotate(dst,dst,Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(dst,dst,Core.ROTATE_180)
                270 -> Core.rotate(dst,dst,Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            return Bitmap.createBitmap(dst.cols(),dst.rows(),Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(dst,it) }
        } finally { listOf(source,warped,rgb,gray,dst,srcPoints,dstPoints,matrix).forEach { it.release() } }
    }
}

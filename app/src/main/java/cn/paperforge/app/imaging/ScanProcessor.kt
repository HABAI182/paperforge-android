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
    val filters=listOf("ORIGINAL","AUTO","CLEAN","GRAY","BW","COLOR","WHITEBOARD")
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
        val small=Mat(); val gray=Mat(); val blur=Mat(); val edges=Mat(); val binary=Mat(); val hierarchy=Mat()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(5.0,5.0))
        try {
            val scale=min(1.0,900.0/max(source.cols(),source.rows()))
            Imgproc.resize(source,small,Size(),scale,scale)
            if(small.channels()==1) small.copyTo(gray) else Imgproc.cvtColor(small,gray,if(small.channels()==4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray,blur,Size(5.0,5.0),0.0)
            val candidates=mutableListOf<Pair<Quad,Float>>()
            fun collect(mask: Mat) {
                val contours=mutableListOf<MatOfPoint>()
                try {
                    Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.filter { Imgproc.contourArea(it)>small.total()*.07 }.sortedByDescending { Imgproc.contourArea(it) }.take(30).forEach { contour ->
                        val c=MatOfPoint2f(*contour.toArray()); val approx=MatOfPoint2f()
                        try {
                            for(epsilon in listOf(.012,.022,.035,.05)) {
                                Imgproc.approxPolyDP(c,approx,epsilon*Imgproc.arcLength(c,true),true)
                                val p=approx.toArray(); if(p.size!=4) continue
                                val q=Quad.ordered(p.map { PointN((it.x/(small.cols()-1)).toFloat().coerceIn(0f,1f),(it.y/(small.rows()-1)).toFloat().coerceIn(0f,1f)) })
                                if(!q.valid() || q.area !in .08f.. .995f) continue
                                // Reject a full image border introduced by thresholding and thin text boxes.
                                val touches=q.points.count { it.x<.008f || it.x>.992f || it.y<.008f || it.y>.992f }
                                if(touches>=3) continue
                                val sides=q.points.indices.map { i -> val a=q.points[i]; val b=q.points[(i+1)%4]; hypot((a.x-b.x)*small.cols(),(a.y-b.y)*small.rows()) }
                                if(sides.min()<min(small.cols(),small.rows())*.12) continue
                                val rectangularity=(Imgproc.contourArea(contour)/(q.area*small.total())).toFloat().coerceIn(0f,1f)
                                candidates.add(q to q.area*(.7f+.3f*rectangularity)); break
                            }
                        } finally { c.release(); approx.release() }
                    }
                } finally { contours.forEach { it.release() } }
            }
            // Edges find coloured paper; Otsu masks recover bright sheets with weak or broken edges.
            Imgproc.Canny(blur,edges,28.0,92.0); Imgproc.morphologyEx(edges,edges,Imgproc.MORPH_CLOSE,kernel); collect(edges)
            Imgproc.threshold(blur,binary,0.0,255.0,Imgproc.THRESH_BINARY+Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_CLOSE,kernel); collect(binary)
            Core.bitwise_not(binary,binary); collect(binary)
            return candidates.maxByOrNull { it.second }?.first
        } finally { listOf(small,gray,blur,edges,binary,hierarchy,kernel).forEach { it.release() } }
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
                "GRAY","BW","AUTO","CLEAN","WHITEBOARD" -> {
                    Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY)
                    when(filter) {
                        "BW" -> Imgproc.adaptiveThreshold(gray,dst,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY,41,13.0)
                        "AUTO","CLEAN","WHITEBOARD" -> {
                            val background=Mat(); val smooth=Mat(); val norm=Mat(); val sharp=Mat()
                            val backgroundSize=(min(gray.cols(),gray.rows())/25).coerceIn(21,81).let { if(it%2==0) it+1 else it }
                            val k=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(backgroundSize.toDouble(),backgroundSize.toDouble()))
                            try {
                                // Suppress JPEG/bicubic bright ringing before the max filter; otherwise
                                // that overshoot becomes a false light source and creates dark halos.
                                Imgproc.medianBlur(gray,smooth,5)
                                Imgproc.dilate(smooth,background,k); Imgproc.GaussianBlur(background,background,Size(51.0,51.0),0.0)
                                Core.divide(gray,background,norm,255.0)
                                Imgproc.GaussianBlur(norm,smooth,Size(3.0,3.0),0.0); Core.addWeighted(norm,1.2,smooth,-.2,0.0,sharp)
                                if(filter=="WHITEBOARD") Imgproc.adaptiveThreshold(sharp,dst,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY,51,10.0)
                                else if(filter=="CLEAN") sharp.convertTo(dst,-1,1.08,-8.0)
                                else sharp.copyTo(dst)
                            } finally { listOf(background,smooth,norm,sharp,k).forEach { it.release() } }
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

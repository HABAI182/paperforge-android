package cn.paperforge.app.imaging

import cn.paperforge.app.model.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

object CameraQualityAnalyzer {
    data class Result(val sample: CaptureQualityPolicy.Sample?,val signature: List<Float>)
    fun analyze(source: Mat,quad: Quad?): Result {
        val gray=Mat();val signature=Mat();val paper=Mat();val laplacian=Mat();val mean=MatOfDouble();val deviation=MatOfDouble()
        try {
            when(source.channels()) { 1 -> source.copyTo(gray);4 -> Imgproc.cvtColor(source,gray,Imgproc.COLOR_RGBA2GRAY);else -> Imgproc.cvtColor(source,gray,Imgproc.COLOR_RGB2GRAY) }
            Imgproc.resize(gray,signature,Size(4.0,4.0),0.0,0.0,Imgproc.INTER_AREA)
            val scene=ByteArray(16);signature.get(0,0,scene)
            val values=scene.map { (it.toInt() and 255)/255f }
            if(quad==null) return Result(null,values)
            val points=MatOfPoint2f(*quad.points.map { Point(it.x*(gray.cols()-1).toDouble(),it.y*(gray.rows()-1).toDouble()) }.toTypedArray())
            val corners=MatOfPoint2f(Point(0.0,0.0),Point(449.0,0.0),Point(449.0,599.0),Point(0.0,599.0))
            val transform=Imgproc.getPerspectiveTransform(points,corners)
            try { Imgproc.warpPerspective(gray,paper,transform,Size(450.0,600.0),Imgproc.INTER_LINEAR,Core.BORDER_REPLICATE) }
            finally { points.release();corners.release();transform.release() }
            Imgproc.Laplacian(paper,laplacian,CvType.CV_32F)
            Core.meanStdDev(laplacian,mean,deviation);val sharpness=deviation.toArray()[0].pow(2)
            var detailed=0
            for(y in 0..2) for(x in 0..2) {
                val tile=paper.submat(y*200,(y+1)*200,x*150,(x+1)*150);val detail=laplacian.submat(y*200,(y+1)*200,x*150,(x+1)*150)
                try {
                    Core.meanStdDev(tile,mean,deviation);val contrast=deviation.toArray()[0]
                    Core.meanStdDev(detail,mean,deviation)
                    if(contrast>12 && deviation.toArray()[0].pow(2)>20) detailed++
                } finally { tile.release();detail.release() }
            }
            val bytes=ByteArray(paper.rows()*paper.cols());paper.get(0,0,bytes)
            val glare=bytes.count { (it.toInt() and 255)>=253 }.toDouble()/bytes.size
            return Result(CaptureQualityPolicy.Sample(sharpness,Core.mean(paper).`val`[0]/255,detailed,glare),values)
        } finally { gray.release();signature.release();paper.release();laplacian.release();mean.release();deviation.release() }
    }
}

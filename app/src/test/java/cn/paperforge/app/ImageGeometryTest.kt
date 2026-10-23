package cn.paperforge.app

import cn.paperforge.app.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ImageGeometryTest {
    @Test fun powerOfTwoSamplingKeepsRequestedCropPixels() {
        assertEquals(1, ImageGeometry.decodePlan(3024, 4032, 3950f, 3508).sample)
        assertEquals(1, ImageGeometry.decodePlan(3456, 4608, 4200f, 3508).sample)
        assertEquals(2, ImageGeometry.decodePlan(3024, 4032, 3950f, 1754).sample)
        assertEquals(1, ImageGeometry.decodePlan(900, 1800, 1600f, 3508).sample)
        assertEquals(2, ImageGeometry.decodePlan(8192, 6144, 8000f, 3508).sample)
        assertTrue(ImageGeometry.decodePlan(8192, 6144, 6500f, 3508).memoryLimited)
    }
    @Test fun allExifOrientationsInvertInNormalizedSpace() {
        for (mode in 1..8) for (point in listOf(PointN(.12f, .75f), PointN(0f, 1f), PointN(.5f, .5f))) {
            val restored = ImageGeometry.exif(ImageGeometry.exif(point, mode), mode, inverse = true)
            assertEquals(point.x, restored.x, 1e-6f)
            assertEquals(point.y, restored.y, 1e-6f)
        }
    }
    @Test fun projectiveAspectRecoversKnownPaperWithoutForcingReceipts() {
        val yaw = Math.toRadians(26.0); val pitch = Math.toRadians(17.0)
        val points = listOf(-105.0 to -148.5, 105.0 to -148.5, 105.0 to 148.5, -105.0 to 148.5).map { (x, y) ->
            val xx = x * cos(yaw); val zz = -x * sin(yaw)
            val yy = y * cos(pitch) - zz * sin(pitch); val z = 850 + y * sin(pitch) + zz * cos(pitch)
            PointN(((640 + 900 * xx / z) / 1279).toFloat(), ((480 + 900 * yy / z) / 959).toFloat())
        }
        assertEquals(210f / 297, ImageGeometry.aspect(Quad(points), 1280, 960), .025f)
        val receipt = Quad(listOf(PointN(.4f,.1f),PointN(.6f,.1f),PointN(.6f,.9f),PointN(.4f,.9f)))
        assertEquals((.2f * 1599) / (.8f * 1199), ImageGeometry.aspect(receipt, 1600, 1200), .001f)
    }
}

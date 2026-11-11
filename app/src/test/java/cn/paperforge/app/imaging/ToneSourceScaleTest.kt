package cn.paperforge.app.imaging

import org.junit.Assert.*
import org.junit.Test

class ToneSourceScaleTest {
    @Test fun readsExistingStructureAndNewExplicitMetadata() {
        assertEquals(1.60022f, ToneSourceScale.recorded(diagnostic = 1.60022)!!, .00001f)
        assertEquals(1.25f, ToneSourceScale.recorded(1.25, 1.60022)!!, .00001f)
    }

    @Test fun previewResolutionChangesPhotoPixelUnitsRatherThanResettingToOne() {
        assertEquals(1.6f, ToneSourceScale.atSize(1.6f, 1600, 2400, 1600, 2400)!!, .00001f)
        assertEquals(1.0666667f, ToneSourceScale.atSize(1.6f, 1600, 2400, 1067, 1600)!!, .00001f)
        assertEquals(.8f, ToneSourceScale.atSize(1.6f, 1600, 2400, 800, 1200)!!, .00001f)
    }

    @Test fun enlargedReplayKeepsTheRecordedInputPixelScale() {
        assertEquals(2.4f, ToneSourceScale.atSize(1.6f, 1000, 1500, 1500, 2250)!!, .00001f)
    }

    @Test fun missingOrInvalidLegacyScaleKeepsTheUnscopedRendererPath() {
        assertNull(ToneSourceScale.recorded())
        assertNull(ToneSourceScale.recorded(-1.0))
        assertNull(ToneSourceScale.recorded(1e300))
        assertNull(ToneSourceScale.atSize(null, 1600, 2400, 800, 1200))
        assertNull(ToneSourceScale.atSize(Float.NaN, 1600, 2400, 800, 1200))
        assertNull(ToneSourceScale.atSize(1f, 0, 0, 800, 1200))
    }
}

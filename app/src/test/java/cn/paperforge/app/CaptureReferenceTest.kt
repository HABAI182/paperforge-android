package cn.paperforge.app

import cn.paperforge.app.model.*
import org.junit.Assert.*
import org.junit.Test

class CaptureReferenceTest {
    @Test
    fun canonicalFrameIsOptInAndKeepsReferenceValidityChecks() {
        val scene = listOf(.1f, .2f, .8f, .7f)
        val legacy = CaptureReference(Quad.Full, 1000, 1200, 600, 800, "viewport", 1L, .9f, scene, scene)
        assertFalse(legacy.canonicalFrame)
        val canonical = legacy.copy(canonicalFrame = true)
        assertTrue(canonical.canonicalFrame)
        assertEquals("matched", canonical.reason(1500, 2000, scene, scene))
        assertEquals("stale", canonical.copy(requestedAt = 1500).reason(1500, 2000, scene, scene))
        assertEquals("motion", canonical.copy(motion = .1f).reason(1500, 2000, scene, scene))
        assertEquals("viewport-changed", canonical.reason(2000, 1500, scene, scene))
    }

    @Test
    fun staleMovedOrChangedFramesUseThePhotoWhileExposureAloneCanReuse() {
        val scene = listOf(.1f, .2f, .8f, .7f)
        val r =
            CaptureReference(
                Quad.Full,
                1000,
                1200,
                600,
                800,
                "viewport",
                123456L,
                .8f,
                scene,
                scene,
            )
        assertEquals(
            "matched",
            r.reason(1500, 2000, scene.map { it + .1f }, scene.map { it + .1f }),
        )
        assertEquals("stale", r.copy(requestedAt = 1600).reason(1500, 2000, scene, scene))
        assertEquals("motion", r.copy(motion = .1f).reason(1500, 2000, scene, scene))
        assertEquals("viewport-changed", r.reason(2000, 1500, scene, scene))
        assertEquals("scene-changed", r.reason(1500, 2000, scene.reversed(), scene))
        assertEquals("paper-changed", r.reason(1500, 2000, scene, scene.reversed()))
        val gate = AutoCaptureGate(stableMs = 300, cooldownMs = 500)
        gate.markCaptured(Quad.Full, 1200, scene)
        assertFalse(gate.update(Quad.Full, 2200, true, scene))
        gate.reset(clearCaptured = true)
        assertFalse(gate.update(Quad.Full, 2300, true, scene))
        assertTrue(gate.update(Quad.Full, 2700, true, scene))
        gate.markCaptured(null, 3000, null)
        assertFalse(gate.update(Quad.Full, 3500, true, scene))
        assertFalse(gate.update(Quad.Full, 4500, true, scene))
    }
}

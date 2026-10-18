package app.floatdeck.spatial

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialMathTest {
    @Test fun `foreground moves more than midground and background in all four directions`() {
        for (direction in listOf(-1f, 1f)) {
            val m = SpatialMath.motion(direction, 1f, 8f, 0.5f)
            val near = SpatialMath.displacement(0.94f, m)
            val middle = SpatialMath.displacement(0.49f, m)
            val far = SpatialMath.displacement(0.098f, m)
            assertTrue(kotlin.math.abs(near) > kotlin.math.abs(middle))
            assertTrue(kotlin.math.abs(middle) > kotlin.math.abs(far))
            assertTrue(near * direction > 0f)
        }
    }
    @Test fun `neutral pose and zero strength are static`() {
        assertEquals(0f, SpatialMath.motion(0f, 3f, 15f, 1f))
        assertEquals(0f, SpatialMath.motion(1f, 3f, 15f, 0f))
    }
    @Test fun `bounded motion stays within crop margin`() {
        val m = kotlin.math.abs(SpatialMath.motion(100f, 3f, 90f, 10f))
        assertTrue(m <= 0.045f)
        for (aspect in listOf(0.5f, 1f, 2f)) {
            val c = SpatialMath.cover(aspect, 0.5f, 0f)
            for (v in c) assertTrue(v * (0.5f + m) <= 0.5f)
        }
    }
    @Test fun `small natural tilt responds without changing amplitude cap`() {
        val low = SpatialMath.response(3f / SpatialPose.NORMALIZATION_DEGREES, 0.5f, 8f)
        val high = SpatialMath.response(3f / SpatialPose.NORMALIZATION_DEGREES, 2f, 8f)
        assertTrue(low > 0.2f && high > low)
        assertTrue(SpatialMath.response(10f / SpatialPose.NORMALIZATION_DEGREES, 1f, 8f) > 0.99f)
        assertEquals(1f, SpatialMath.response(1f, 3f, 8f), 0.00001f)
        for (sensitivity in listOf(0.25f, 0.5f, 1f, 2f, 3f))
            assertEquals(1f, SpatialMath.response(15f / SpatialPose.NORMALIZATION_DEGREES, sensitivity, 15f), 0.00001f)
        for (v in -100..100) assertEquals(-SpatialMath.response(v / 100f, 1f, 8f), SpatialMath.response(-v / 100f, 1f, 8f), 0.00001f)
    }
    @Test fun `target ranges cover three to six percent and limits reserve a source texel`() {
        assertEquals(0.03f, SpatialMath.amplitude(1f, "stable"))
        assertEquals(0.06f, SpatialMath.amplitude(1f, "enhanced"))
        for (aspect in listOf(0.35f, 0.56f, 1f, 2f)) for (crop in listOf(0f, 0.04f, 0.3f)) {
            val cover = SpatialMath.cover(aspect, 0.56f, crop)
            val a = SpatialMath.safeAmplitude(0.06f, cover, 720, 1280, 720, 1280)
            assertTrue(cover[0] * (0.5f + a * 1.3f) <= 0.5f - 1f / 720 + 0.00001f)
            assertTrue(cover[1] * (0.5f + a * 1.3f * 720 / 1280) <= 0.5f - 1f / 1280 + 0.00001f)
        }
    }
    @Test fun `filter is frame-rate independent faster setting settles sooner`() {
        fun settle(fps: Int): Float {
            var v = 0f
            repeat(fps) { v += (1f - v) * SpatialMath.smoothing(1f / fps, 0.65f) }
            return v
        }
        assertEquals(settle(30), settle(120), 0.00001f)
        assertTrue(SpatialMath.smoothing(0.016f, 1f) > SpatialMath.smoothing(0.016f, 0f))
    }
    @Test fun `depth normalization preserves constant control and bounds noise amplification`() {
        assertEquals(0.5f, SpatialMath.normalizedDepth(0.5f, 0.5f, 0.5f))
        assertEquals(0f, SpatialMath.normalizedDepth(0.2f, 0.2f, 0.9f))
        assertEquals(1f, SpatialMath.normalizedDepth(0.9f, 0.2f, 0.9f))
        assertTrue(SpatialMath.normalizedDepth(0.51f, 0.5f, 0.51f) < 0.1f)
    }
    @Test fun `aspect cover preserves source aspect without stretching`() {
        val c = SpatialMath.cover(2f, 0.5f, 0f)
        assertEquals(0.25f, c[0] / c[1], 0.00001f)
    }
}

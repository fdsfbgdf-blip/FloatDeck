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
        assertTrue(m < 0.042f)
        for (aspect in listOf(0.5f, 1f, 2f)) {
            val c = SpatialMath.cover(aspect, 0.5f, 0f)
            for (v in c) assertTrue(v * (0.5f + m) <= 0.5f)
        }
    }
    @Test fun `aspect cover preserves source aspect without stretching`() {
        val c = SpatialMath.cover(2f, 0.5f, 0f)
        assertEquals(0.25f, c[0] / c[1], 0.00001f)
    }
}

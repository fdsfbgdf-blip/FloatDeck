package app.floatdeck.spatial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClockMathTest {
    @Test fun `opaque black background does not occlude clock`() {
        assertEquals(0, ClockMath.alpha(0, 0, 0, 255))
        assertEquals(255, ClockMath.alpha(255, 255, 255, 255))
    }
    @Test fun `soft contour preserves transparency without black halo`() {
        assertEquals(128, ClockMath.alpha(128, 128, 128, 255))
        assertEquals(0, ClockMath.alpha(255, 255, 255, 0))
    }
    @Test fun `depth threshold separates original demo foreground from hills`() {
        assertEquals(255, ClockMath.depthMask(240, 180))
        assertEquals(0, ClockMath.depthMask(125, 180))
        assertEquals(0, ClockMath.depthMask(25, 180))
    }
}

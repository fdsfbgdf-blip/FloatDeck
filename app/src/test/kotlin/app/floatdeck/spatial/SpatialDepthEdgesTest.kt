package app.floatdeck.spatial

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialDepthEdgesTest {
    private fun gray(v: Int) = -0x1000000 or (v shl 16) or (v shl 8) or v
    @Test fun `antialiased hard edge does not create a false intermediate surface`() {
        val row = intArrayOf(16,16,16,70,190,240,240,240).map(::gray).toIntArray()
        val fixed = SpatialDepthEdges.repair(row, row.size, 1)
        assertEquals(gray(16), fixed[3])
        assertEquals(gray(240), fixed[4])
        assertEquals(gray(70), row[3], "Original source must not be modified")
    }
    @Test fun `continuous depth slopes and constant controls retain their values`() {
        val slope = IntArray(128) { gray(it * 2) }
        assertArrayEquals(slope, SpatialDepthEdges.repair(slope, 128, 1))
        val constant = IntArray(16) { gray(128) }
        assertArrayEquals(constant, SpatialDepthEdges.repair(constant, 4, 4))
    }
    @Test fun `thin near extrema are retained and no depth is invented outside input range`() {
        val thin = IntArray(25) { gray(if (it == 12) 240 else 16) }
        val fixed = SpatialDepthEdges.repair(thin, 5, 5)
        assertEquals(gray(240), fixed[12])
        assertTrue(fixed.all { it == gray(16) || it == gray(240) })
    }
}

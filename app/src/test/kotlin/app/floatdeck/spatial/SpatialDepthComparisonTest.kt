package app.floatdeck.spatial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SpatialDepthComparisonTest {
    private fun gray(value: Int) = -0x1000000 or (value shl 16) or (value shl 8) or value

    @Test
    fun `comparison reports changed pixel ratio and depth statistics`() {
        val original = intArrayOf(16, 16, 70, 190, 240).map(::gray).toIntArray()
        val repaired = intArrayOf(16, 16, 16, 240, 240).map(::gray).toIntArray()

        val result = SpatialDepthComparisonAnalyzer.analyze(original, repaired)

        assertEquals(5, result.totalPixels)
        assertEquals(2, result.changedPixels)
        assertEquals(40f, result.changedPercent, 0.001f)
        assertEquals(20.8f, result.meanAbsoluteDifference, 0.001f)
        assertEquals(54, result.maxAbsoluteDifference)
        assertEquals(16, result.original.min)
        assertEquals(240, result.original.max)
        assertEquals(4, result.original.levels)
        assertEquals(2, result.repaired.levels)
    }

    @Test
    fun `comparison rejects empty or mismatched images`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpatialDepthComparisonAnalyzer.analyze(intArrayOf(), intArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpatialDepthComparisonAnalyzer.analyze(intArrayOf(gray(0)), intArrayOf(gray(0), gray(1)))
        }
    }
}

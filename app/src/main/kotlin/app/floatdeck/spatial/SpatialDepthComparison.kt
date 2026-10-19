package app.floatdeck.spatial

/** Pixel-level comparison of the source depth map and the APP's repaired working copy. */
data class SpatialDepthStats(
    val min: Int,
    val max: Int,
    val levels: Int,
)

data class SpatialDepthComparison(
    val totalPixels: Int,
    val changedPixels: Int,
    val changedPercent: Float,
    val meanAbsoluteDifference: Float,
    val maxAbsoluteDifference: Int,
    val original: SpatialDepthStats,
    val repaired: SpatialDepthStats,
)

object SpatialDepthComparisonAnalyzer {
    /** Depth is stored in the red channel; RGB values are otherwise irrelevant. */
    fun analyze(original: IntArray, repaired: IntArray): SpatialDepthComparison {
        require(original.isNotEmpty()) { "Depth image must not be empty" }
        require(original.size == repaired.size) { "Original and repaired depth sizes differ" }

        val originalLevels = BooleanArray(256)
        val repairedLevels = BooleanArray(256)
        var originalMin = 255
        var originalMax = 0
        var repairedMin = 255
        var repairedMax = 0
        var changed = 0
        var differenceSum = 0L
        var maxDifference = 0

        for (i in original.indices) {
            val before = (original[i] ushr 16) and 255
            val after = (repaired[i] ushr 16) and 255
            originalMin = minOf(originalMin, before)
            originalMax = maxOf(originalMax, before)
            repairedMin = minOf(repairedMin, after)
            repairedMax = maxOf(repairedMax, after)
            originalLevels[before] = true
            repairedLevels[after] = true
            val difference = kotlin.math.abs(before - after)
            if (difference != 0) changed++
            differenceSum += difference.toLong()
            maxDifference = maxOf(maxDifference, difference)
        }

        return SpatialDepthComparison(
            totalPixels = original.size,
            changedPixels = changed,
            changedPercent = changed * 100f / original.size,
            meanAbsoluteDifference = differenceSum.toFloat() / original.size,
            maxAbsoluteDifference = maxDifference,
            original = SpatialDepthStats(originalMin, originalMax, originalLevels.count { it }),
            repaired = SpatialDepthStats(repairedMin, repairedMax, repairedLevels.count { it }),
        )
    }
}

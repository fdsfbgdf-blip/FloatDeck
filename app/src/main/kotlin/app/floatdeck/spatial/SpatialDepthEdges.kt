package app.floatdeck.spatial

/** Repair only high-contrast depth ramps on upload; source photo and archive remain untouched. */
object SpatialDepthEdges {
    fun repair(pixels: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        val source = IntArray(pixels.size) { (pixels[it] ushr 16) and 255 }
        val range = source.maxOrNull()!! - source.minOrNull()!!
        val threshold = maxOf(0.15f * 255f, range.toFloat()) * 0.18f
        val output = pixels.copyOf()
        for (y in 0 until height) for (x in 0 until width) {
            val center = source[y * width + x]
            var low = center; var high = center
            for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                val value = source[(y + dy).coerceIn(0, height-1) * width + (x + dx).coerceIn(0, width-1)]
                low = minOf(low, value); high = maxOf(high, value)
            }
            if (high - low > threshold) {
                // Source snapshot, never in-place propagation: preserve thin extrema.
                val value = if (center - low < high - center) low else high
                output[y * width + x] = -0x1000000 or (value shl 16) or (value shl 8) or value
            }
        }
        return output
    }
}

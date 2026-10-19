package app.floatdeck.spatial

import java.nio.ByteBuffer

/** Validate and count the actual binary fragment output, never infer failure from depth edges. */
object SpatialFallbackMask {
    fun count(pixels: ByteBuffer, total: Int): Int {
        require(total > 0 && total.toLong() * 4 <= pixels.capacity()) { "GPU 掩码尺寸无效" }
        var failed = 0
        for (i in 0 until total) {
            val offset = i * 4
            val red = pixels.get(offset).toInt() and 255
            check((red == 0 || red == 255) && pixels.get(offset + 1).toInt() == 0 &&
                pixels.get(offset + 2).toInt() == 0 && (pixels.get(offset + 3).toInt() and 255) == 255) {
                "GPU 返回非二值掩码，不能统计（像素 $i）"
            }
            if (red == 255) failed++
        }
        return failed
    }
}

package app.floatdeck.spatial

import kotlin.math.roundToInt

object ClockMath {
    /** Grayscale masks use luminance, never PNG's usually opaque alpha channel. */
    fun alpha(red: Int, green: Int, blue: Int, sourceAlpha: Int): Int =
        (((red * 0.2126 + green * 0.7152 + blue * 0.0722) * sourceAlpha) / 255.0).roundToInt().coerceIn(0, 255)

    fun depthMask(depth: Int, threshold: Int): Int = if (depth >= threshold.coerceIn(0, 255)) 255 else 0
}

package app.floatdeck.spatial


/** Shared pure geometry. Depth is inverse distance: black far, white near. */
object SpatialMath {
    // V1 package angle now describes the full response angle, not a second amplitude multiplier.
    fun response(tilt: Float, sensitivity: Float, angle: Float): Float {
        val v = (tilt * SpatialPose.NORMALIZATION_DEGREES / angle.coerceIn(6f, 15f)).coerceIn(-1f, 1f)
        val m = kotlin.math.abs(v)
        // Sensitivity changes initial gain, but every setting reaches the same cap.
        // Unlike multiplication before clipping, low sensitivity cannot shrink the cap.
        val curved = m / (m + (1f-m) / sensitivity.coerceIn(0.25f, 3f))
        return kotlin.math.sign(v) * kotlin.math.tanh(curved * 1.2f) / kotlin.math.tanh(1.2f)
    }
    fun amplitude(strength: Float, mode: String): Float = strength.coerceIn(0f, 1f) * when (mode) {
        "stable" -> 0.06f
        "enhanced" -> 0.12f
        else -> 0.09f
    }
    fun motion(tilt: Float, sensitivity: Float, angle: Float, strength: Float): Float =
        response(tilt, sensitivity, angle) * amplitude(strength, "standard")

    fun smoothing(dt: Float, speed: Float): Float =
        1f - kotlin.math.exp(-dt.coerceIn(0f, 0.1f) / (0.22f - 0.19f * speed.coerceIn(0f, 1f)))

    const val CAMERA_SHARE = 0.30f
    // All offsets expressed in visible-screen units. Reserve a source texel at each edge.
    fun safeAmplitude(requested: Float, cover: FloatArray, width: Int, height: Int, imageWidth: Int, imageHeight: Int): Float {
        val x = ((0.5f - 1f / imageWidth.coerceAtLeast(2)) / cover[0] - 0.5f).coerceAtLeast(0f)
        val y = ((0.5f - 1f / imageHeight.coerceAtLeast(2)) / cover[1] - 0.5f).coerceAtLeast(0f) * height / width.coerceAtLeast(1)
        return minOf(requested, x / (1f + CAMERA_SHARE), y / (1f + CAMERA_SHARE))
    }
    fun normalizedDepth(depth: Float, min: Float, max: Float): Float =
        if (max - min < 1f / 255f) 0.5f else ((depth - min) / maxOf(0.15f, max - min)).coerceIn(0f, 1f)

    fun cover(imageAspect: Float, screenAspect: Float, crop: Float): FloatArray {
        val margin = 1f / (1.10f + crop.coerceIn(0f, 0.3f))
        return if (imageAspect > screenAspect) floatArrayOf(screenAspect / imageAspect * margin, margin)
        else floatArrayOf(margin, imageAspect / screenAspect * margin)
    }

    fun displacement(depth: Float, motion: Float): Float = depth.coerceIn(0f, 1f) * motion
}

/** Future depth providers return a registered grayscale image; no AI model is shipped. */
interface DepthProvider {
    suspend fun createDepth(source: java.io.File, destination: java.io.File)
}

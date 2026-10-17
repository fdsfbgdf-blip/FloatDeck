package app.floatdeck.spatial

import kotlin.math.sin

/** Shared pure geometry. Depth is inverse distance: black far, white near. */
object SpatialMath {
    fun motion(tilt: Float, sensitivity: Float, angle: Float, strength: Float): Float =
        (tilt * sensitivity).coerceIn(-1f, 1f) * sin(Math.toRadians(angle.coerceIn(1f, 15f).toDouble())).toFloat() * strength.coerceIn(0f, 1f) * 0.16f

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

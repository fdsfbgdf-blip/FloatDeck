package app.floatdeck.spatial

import kotlin.math.atan2
import kotlin.math.sqrt

/** Relative screen-frame attitude, unlike gravity-only projection this observes upright yaw. */
class SpatialPose {
    data class Input(val x: Float = 0f, val y: Float = 0f, val yawDegrees: Float = 0f, val pitchDegrees: Float = 0f)
    private var baseline: FloatArray? = null
    private var rotation = -1
    @Volatile private var resetRequested = true
    @Volatile var input = Input()
        private set

    fun recenter() { resetRequested = true; input = Input() }

    fun update(matrix: FloatArray, displayRotation: Int): Input {
        require(matrix.size == 9 && matrix.all { it.isFinite() })
        if (resetRequested || rotation != displayRotation || baseline == null) {
            baseline = matrix.copyOf()
            rotation = displayRotation
            resetRequested = false
        }
        val b = baseline!!
        // Third column of transpose(reference) * current is the current viewing normal.
        val nx = b[0] * matrix[2] + b[3] * matrix[5] + b[6] * matrix[8]
        val ny = b[1] * matrix[2] + b[4] * matrix[5] + b[7] * matrix[8]
        val nz = b[2] * matrix[2] + b[5] * matrix[5] + b[8] * matrix[8]
        val yaw = Math.toDegrees(atan2(nx.toDouble(), nz.toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(-ny.toDouble(), sqrt((nx * nx + nz * nz).toDouble()))).toFloat()
        input = Input((yaw / 20f).coerceIn(-1f, 1f), (pitch / 20f).coerceIn(-1f, 1f), yaw, pitch)
        return input
    }
}

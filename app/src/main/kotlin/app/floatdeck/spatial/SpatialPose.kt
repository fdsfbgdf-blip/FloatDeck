package app.floatdeck.spatial

import kotlin.math.atan2
import kotlin.math.sqrt

/** Relative screen-frame attitude, unlike gravity-only projection this observes upright yaw. */
class SpatialPose {
    companion object { const val NORMALIZATION_DEGREES = 15f }
    data class Input(val x: Float = 0f, val y: Float = 0f, val yawDegrees: Float = 0f, val pitchDegrees: Float = 0f, val bankDegrees: Float = 0f)
    private var baseline: FloatArray? = null
    private var rotation = -1
    private val relative = FloatArray(9)
    @Volatile private var resetRequested = true
    @Volatile var input = Input()
        private set

    fun recenter() { resetRequested = true; input = Input() }

    fun update(matrix: FloatArray, displayRotation: Int): Input {
        require(matrix.size == 9 && matrix.all { it.isFinite() })
        if (resetRequested || baseline == null) {
            baseline = matrix.copyOf()
            rotation = displayRotation
            resetRequested = false
        }
        if (rotation != displayRotation) {
            // Conjugate the neutral frame with the same screen-basis remap as SensorManager.
            val delta = -((displayRotation - rotation + 4) % 4) * Math.PI / 2
            val c = kotlin.math.cos(delta).toFloat(); val sn = kotlin.math.sin(delta).toFloat()
            val old = baseline!!
            baseline = FloatArray(9) { i -> when(i%3) {
                0 -> old[i/3*3]*c + old[i/3*3+1]*sn
                1 -> -old[i/3*3]*sn + old[i/3*3+1]*c
                else -> old[i]
            } }
            rotation = displayRotation
        }
        val b = baseline!!
        for (row in 0..2) for (col in 0..2) relative[row*3+col] =
            b[row]*matrix[col] + b[3+row]*matrix[3+col] + b[6+row]*matrix[6+col]
        // Third column of transpose(reference) * current is the current viewing normal.
        val nx = b[0] * matrix[2] + b[3] * matrix[5] + b[6] * matrix[8]
        val ny = b[1] * matrix[2] + b[4] * matrix[5] + b[7] * matrix[8]
        val nz = b[2] * matrix[2] + b[5] * matrix[5] + b[8] * matrix[8]
        val yaw = Math.toDegrees(atan2(nx.toDouble(), nz.toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(-ny.toDouble(), sqrt((nx * nx + nz * nz).toDouble()))).toFloat()
        val rightX = b[0] * matrix[0] + b[3] * matrix[3] + b[6] * matrix[6]
        val rightY = b[1] * matrix[0] + b[4] * matrix[3] + b[7] * matrix[6]
        val bank = Math.toDegrees(atan2(rightY.toDouble(), rightX.toDouble())).toFloat()
        // Antisymmetric part of B^T R is 2*w*q.xyz: sign-invariant and continuous.
        // Euler angles above are diagnostics only, never used for motion control.
        val rx=(relative[7]-relative[5])*0.5f
        val ry=(relative[2]-relative[6])*0.5f
        val rz=(relative[3]-relative[1])*0.5f
        val vx=ry-rz; val vy=rx
        val length=kotlin.math.hypot(vx,vy)
        val magnitude=(Math.toDegrees(kotlin.math.asin(length.coerceAtMost(1f)).toDouble()).toFloat()/NORMALIZATION_DEGREES).coerceAtMost(1f)
        input = if(length>1e-8f) Input(vx/length*magnitude,vy/length*magnitude,yaw,pitch,bank)
                else Input(0f,0f,yaw,pitch,bank)
        return input
    }
}

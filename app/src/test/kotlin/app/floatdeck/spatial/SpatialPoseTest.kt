package app.floatdeck.spatial

import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialPoseTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> (0..2).sumOf { k -> (a[(i / 3) * 3 + k] * b[k * 3 + i % 3]).toDouble() }.toFloat() }
    private fun yaw(degrees: Float): FloatArray {
        val r = Math.toRadians(degrees.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }
    private fun pitch(degrees: Float): FloatArray {
        val r = Math.toRadians(degrees.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }
    @Test fun `upright yaw changes spatial view even though gravity-only legacy outputs stay zero`() {
        for (angle in listOf(-20f, -10f, 10f, 20f)) {
            val pose = SpatialPose(); pose.update(upright, 0)
            val matrix = multiply(upright, yaw(angle))
            assertEquals(0f, matrix[6], 0.0001f)
            assertEquals(0f, matrix[8], 0.0001f)
            val result = pose.update(matrix, 0)
            assertEquals(angle, result.yawDegrees, 0.001f)
            assertEquals((angle / SpatialPose.NORMALIZATION_DEGREES).coerceIn(-1f, 1f), result.x, 0.001f)
        }
    }
    @Test fun `relative pitch responds in both directions independent of neutral orientation`() {
        for (base in listOf(identity, upright)) for (angle in listOf(-15f, 15f)) {
            val pose = SpatialPose(); pose.update(base, 0)
            assertEquals(angle, pose.update(multiply(base, pitch(angle)), 0).pitchDegrees, 0.001f)
        }
    }
    @Test fun `recenter and screen rotation changes discard stale baseline`() {
        val pose = SpatialPose(); pose.update(identity, 0)
        assertTrue(pose.update(yaw(15f), 0).x > 0.5f)
        pose.recenter()
        assertEquals(0f, pose.update(yaw(15f), 0).x, 0.001f)
        assertEquals(0f, pose.update(yaw(-15f), 1).x, 0.001f)
    }
    @Test fun `side edge bank gesture still drives horizontal parallax`() {
        for (angle in listOf(-15f, 15f)) {
            val r = Math.toRadians(angle.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
            val roll = floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
            val pose = SpatialPose(); pose.update(upright, 0)
            val result = pose.update(multiply(upright, roll), 0)
            assertEquals(angle, result.bankDegrees, 0.001f)
            assertEquals((-angle / SpatialPose.NORMALIZATION_DEGREES).coerceIn(-1f, 1f), result.x, 0.001f)
        }
    }
    @Test fun `large pose is bounded and zero motion stays neutral`() {
        val pose = SpatialPose(); pose.update(identity, 0)
        assertEquals(0f, pose.update(identity, 0).x, 0.001f)
        assertEquals(1f, pose.update(yaw(60f), 0).x, 0.001f)
    }
}

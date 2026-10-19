package app.floatdeck.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialSensorRegistrationTest {
    @Test fun `rejected rotation listener falls back rather than reporting false success`() {
        val context = mockk<Context>()
        val manager = mockk<SensorManager>(relaxed = true)
        val rotation = mockk<Sensor>(relaxed = true)
        val game = mockk<Sensor>(relaxed = true)
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns manager
        every { manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) } returns rotation
        every { manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) } returns game
        every { manager.registerListener(any(), rotation, 10_000, any<android.os.Handler>()) } returns false
        every { manager.registerListener(any(), game, 10_000, any<android.os.Handler>()) } returns true
        val handler = SensorHandler(context, { mockk<android.os.Handler>() }, { 0L })
        handler.register()
        assertTrue(handler.registered)
        assertTrue(handler.sensorName.contains("游戏旋转向量"))
        handler.unregister()
        assertFalse(handler.registered)
        verify { manager.unregisterListener(handler) }
    }
}

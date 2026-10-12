package app.floatdeck.gl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FloatDeckRenderer.backgroundGeometry] — the pure function
 * that resolves the user background zoom into final draw dimensions.
 */
class BackgroundGeometryTest {
    @Test
    fun `zoom of 1 keeps the draw dimensions`() {
        val geo = FloatDeckRenderer.backgroundGeometry(drawWidth = 1000f, drawHeight = 2000f, zoom = 1f)
        assertEquals(1000f, geo[0], 0.001f)
        assertEquals(2000f, geo[1], 0.001f)
    }

    @Test
    fun `zoom scales both dimensions`() {
        val geo = FloatDeckRenderer.backgroundGeometry(drawWidth = 1000f, drawHeight = 2000f, zoom = 1.5f)
        assertEquals(1500f, geo[0], 0.001f)
        assertEquals(3000f, geo[1], 0.001f)
    }

    @Test
    fun `zoom is clamped to 1 and MAX_BACKGROUND_ZOOM`() {
        val tooSmall = FloatDeckRenderer.backgroundGeometry(drawWidth = 1000f, drawHeight = 1000f, zoom = 0.2f)
        assertEquals(1000f, tooSmall[0], 0.001f)

        val tooLarge = FloatDeckRenderer.backgroundGeometry(drawWidth = 1000f, drawHeight = 1000f, zoom = 5f)
        assertEquals(FloatDeckRenderer.MAX_BACKGROUND_ZOOM * 1000f, tooLarge[0], 0.001f)
        assertEquals(FloatDeckRenderer.MAX_BACKGROUND_ZOOM * 1000f, tooLarge[1], 0.001f)
    }
}

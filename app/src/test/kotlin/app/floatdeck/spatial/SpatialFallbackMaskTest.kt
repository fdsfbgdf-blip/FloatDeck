package app.floatdeck.spatial

import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialFallbackMaskTest {
    @Test fun `actual binary mask counts hit and failure without changing buffer position`() {
        val pixels=ByteBuffer.wrap(byteArrayOf(0,0,0,-1,-1,0,0,-1,0,0,0,-1,-1,0,0,-1))
        pixels.position(3)
        assertEquals(2,SpatialFallbackMask.count(pixels,4))
        assertEquals(3,pixels.position())
    }
    @Test fun `invalid or truncated color output must not be called a valid zero percent`() {
        assertThrows(IllegalArgumentException::class.java) { SpatialFallbackMask.count(ByteBuffer.allocate(4),2) }
        assertThrows(IllegalStateException::class.java) { SpatialFallbackMask.count(ByteBuffer.wrap(byteArrayOf(0,0,0,0)),1) }
        assertThrows(IllegalStateException::class.java) { SpatialFallbackMask.count(ByteBuffer.wrap(byteArrayOf(-1,0,-1,-1)),1) }
    }
}

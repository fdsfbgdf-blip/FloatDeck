package app.floatdeck.spatial

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs

/** ZIP -> Android decode -> actual production textures/shader -> ES3 pixel readback. */
class SpatialGpuDiagnosticTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun import(name: String): SpatialLibrary.Entry {
        val archive = File(context.cacheDir, "gpu-${UUID.randomUUID()}.zip")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("$name.spatial.zip").use { input -> archive.outputStream().use { input.copyTo(it) } }
            return SpatialPackageStore.library(context).importArchive(archive).entry
        } finally { archive.delete() }
    }

    private fun withGl(test: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        assertTrue(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, IntArray(1), 0))
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 720, EGL14.EGL_HEIGHT, 1280, EGL14.EGL_NONE), 0)
        val glContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, glContext))
        try { test() } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, glContext); EGL14.eglTerminate(display)
        }
    }
    private fun samples(renderer: SpatialRenderer, axis: Int): List<Int> {
        renderer.onDrawFrame(null)
        assertTrue(renderer.diagnostics.message, renderer.diagnostics.ready)
        val cover = SpatialMath.cover(720f / 1280, 720f / 1280, renderer.diagnostics.crop)
        return listOf(240 to 260, 480 to 640, 240 to 1020).map { (sx, sy) ->
            val x = (((sx / 720f - 0.5f) / cover[0] + 0.5f) * 720).toInt()
            val y = 1279 - (((sy / 1280f - 0.5f) / cover[1] + 0.5f) * 1280).toInt()
            val pixel = ByteBuffer.allocateDirect(4)
            GL.glReadPixels(x, y, 1, 1, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixel)
            assertEquals(0, GL.glGetError())
            pixel.get(axis).toInt() and 255
        }
    }
    @Test fun threeDepthPackageProducesRelativeHorizontalAndVerticalMotionAndConstantControlDoesNot() {
        val three = import("diagnostic_3d")
        val constant = import("diagnostic_constant")
        val prefs = context.getSharedPreferences("gpu-diagnostic-${UUID.randomUUID()}", 0)
        withGl {
            val renderer = SpatialRenderer(context, prefs)
            renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 720, 1280)
            try {
                for (entry in listOf(three, constant)) {
                    SpatialPackageStore.publish(entry, prefs, false)
                    for (axis in 0..1) {
                        renderer.forcedView = SpatialPose.Input()
                        val baseline = samples(renderer, axis)
                        assertEquals(720, renderer.diagnostics.imageWidth)
                        assertEquals(1280, renderer.diagnostics.depthHeight)
                        assertTrue(renderer.diagnostics.depthTexture > 0)
                        if (entry.id == three.id) {
                            assertEquals(16, renderer.diagnostics.depthMin)
                            assertEquals(240, renderer.diagnostics.depthMax)
                        } else assertEquals(0, renderer.diagnostics.depthMax - renderer.diagnostics.depthMin)
                        for (sign in listOf(-1f, 1f)) {
                            renderer.forcedView = if (axis == 0) SpatialPose.Input(sign, 0f) else SpatialPose.Input(0f, sign)
                            // Nonzero sensor input must have no effect while forced.
                            renderer.tiltX = -sign; renderer.tiltY = -sign
                            val moved = samples(renderer, axis)
                            val changes = moved.zip(baseline).map { (a, b) -> a - b }
                            if (entry.id == three.id) {
                                assertTrue("$axis/$sign actual samples $changes", abs(changes[0]) + 1 < abs(changes[1]) && abs(changes[1]) + 1 < abs(changes[2]))
                                assertTrue(changes.all { it * sign <= 0 } && changes.drop(1).all { it * sign < 0 })
                            } else {
                                assertTrue("Constant depth has spurious relative displacement: $changes", changes.maxOrNull()!! - changes.minOrNull()!! <= 1)
                                assertTrue("Constant depth must still move the complete image: $changes", changes.all { abs(it) >= 3 })
                            }
                            assertEquals(sign, if (axis == 0) renderer.diagnostics.viewX else renderer.diagnostics.viewY, 0.0001f)
                            android.util.Log.i("SpatialGpuTest", "axis=$axis sign=$sign constant=${entry.id == constant.id} samples=$changes")
                        }
                    }
                }
            } finally { renderer.release() }
        }
    }
    @Test fun enhancedMotionPreservesCircularContourAndFarPlaneMoves() {
        val prefs = context.getSharedPreferences("gpu-contour-${UUID.randomUUID()}", 0)
        val image = android.graphics.Bitmap.createBitmap(720, 1280, android.graphics.Bitmap.Config.ARGB_8888)
        val depth = android.graphics.Bitmap.createBitmap(720, 1280, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN); depth.eraseColor(android.graphics.Color.rgb(16, 16, 16))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.RED }
        android.graphics.Canvas(image).drawCircle(360f, 640f, 120f, paint)
        paint.color = android.graphics.Color.rgb(240, 240, 240)
        android.graphics.Canvas(depth).drawCircle(360f, 640f, 120f, paint)
        val files = listOf(image, depth).mapIndexed { index, bitmap ->
            File(context.cacheDir, "contour-${UUID.randomUUID()}-$index.png").also { file ->
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        prefs.edit().putString("image", files[0].path).putString("depth", files[1].path)
            .putFloat("strength", 1f).putFloat("crop", 0.08f).putString("parallax_mode", "enhanced").commit()
        try { withGl {
            val renderer = SpatialRenderer(context, prefs)
            renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 720, 1280)
            fun contour(): IntArray {
                renderer.onDrawFrame(null)
                assertTrue(renderer.diagnostics.message, renderer.diagnostics.ready)
                val pixels = ByteBuffer.allocateDirect(720 * 1280 * 4)
                GL.glReadPixels(0, 0, 720, 1280, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
                assertEquals(0, GL.glGetError())
                var count = 0; var x0 = 720; var y0 = 1280; var x1 = 0; var y1 = 0
                for (y in 0 until 1280) for (x in 0 until 720) {
                    val i = (y * 720 + x) * 4
                    if ((pixels.get(i).toInt() and 255) > 180 && (pixels.get(i + 1).toInt() and 255) < 80) {
                        count++; x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
                    }
                }
                return intArrayOf(count, x1-x0, y1-y0, (x1+x0)/2, (y1+y0)/2)
            }
            try {
                renderer.forcedView = SpatialPose.Input()
                val base = contour()
                for (axis in 0..1) for (sign in listOf(-1f, 1f)) {
                    renderer.forcedView = if (axis == 0) SpatialPose.Input(sign, 0f) else SpatialPose.Input(0f, sign)
                    val moved = contour()
                    assertTrue("Circle area stretched: ${moved.toList()}", abs(moved[0] - base[0]) < base[0] * 0.02)
                    assertTrue("Circle width stretched", abs(moved[1] - base[1]) <= 3)
                    assertTrue("Circle height stretched", abs(moved[2] - base[2]) <= 3)
                    assertTrue("Full movement must be visible", abs(moved[3 + axis] - base[3 + axis]) > 35)
                    val d = renderer.diagnostics
                    assertTrue("Far plane must receive camera motion", abs(if (axis == 0) d.cameraX else d.cameraY) > 0f)
                    assertEquals(43.2f, abs(if (axis == 0) d.estimatedNearFarPixelsX else d.estimatedNearFarPixelsY), 0.5f)
                }
            } finally { renderer.release() }
        } } finally { files.forEach { it.delete() } }
    }

    @Test fun samePathRecoversAfterTransientMissingDepthAndContextRecreationReloadsTextures() {
        val entry = import("diagnostic_3d")
        val prefs = context.getSharedPreferences("gpu-retry-${UUID.randomUUID()}", 0)
        val missing = File(context.cacheDir, "late-depth-${UUID.randomUUID()}.png")
        withGl {
            val renderer = SpatialRenderer(context, prefs)
            renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 720, 1280)
            try {
                SpatialPackageStore.publish(entry, prefs, false)
                prefs.edit().putString("depth", missing.path).commit()
                renderer.onDrawFrame(null)
                assertFalse(renderer.diagnostics.ready)
                File(entry.directory, "depth.png").copyTo(missing)
                Thread.sleep(1100)
                renderer.forcedView = SpatialPose.Input(1f, 0f)
                samples(renderer, 0)
                assertTrue(renderer.diagnostics.ready)
                assertEquals(16, renderer.diagnostics.depthMin)
                renderer.release()
                renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 720, 1280)
                samples(renderer, 0)
                assertTrue(renderer.diagnostics.ready)
                assertEquals(240, renderer.diagnostics.depthMax)
            } finally { renderer.release(); missing.delete() }
        }
    }
}

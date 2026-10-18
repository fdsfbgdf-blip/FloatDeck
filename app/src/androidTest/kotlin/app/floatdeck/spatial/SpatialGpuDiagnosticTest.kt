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
                var fallback = 0
                var count = 0; var x0 = 720; var y0 = 1280; var x1 = 0; var y1 = 0
                for (y in 0 until 1280) for (x in 0 until 720) {
                    val i = (y * 720 + x) * 4
                    val r = pixels.get(i).toInt() and 255
                    val g = pixels.get(i+1).toInt() and 255
                    val blue = pixels.get(i+2).toInt() and 255
                    if (r > 250 && g < 5 && blue > 250) fallback++
                    if (r > 180 && g < 80 && blue < 80) {
                        count++; x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
                    }
                }
                return intArrayOf(count, x1-x0, y1-y0, (x1+x0)/2, (y1+y0)/2, fallback)
            }
            try {
                val rows = mutableListOf("mode,crop,axis,sign,strength,requested_fraction,safe_fraction,expected_near_px,readback_near_px,fallback_pixels")
                val input = 0.6f
                for ((mode, old) in listOf("stable" to 0.03f, "standard" to 0.045f, "enhanced" to 0.06f)) {
                    for (crop in listOf(0.04f, 0.3f)) {
                        prefs.edit().putString("parallax_mode", mode).putFloat("crop", crop).commit()
                        renderer.forcedView = SpatialPose.Input()
                        val base = contour()
                        // 0% must be entirely static even with maximum forced input.
                        prefs.edit().putFloat("strength", 0f).commit()
                        renderer.forcedView = SpatialPose.Input(1f, 1f)
                        assertArrayEquals(base, contour())
                        assertEquals(0f, renderer.diagnostics.motionX, 0f)
                        assertEquals(0f, renderer.diagnostics.cameraX, 0f)
                        for (axis in 0..1) for (sign in listOf(-1f, 1f)) {
                            var oldReadback = 0
                            for (strength in listOf(0.5f, 1f)) {
                                prefs.edit().putFloat("strength", strength).commit()
                                renderer.forcedView = if (axis == 0) SpatialPose.Input(sign*input, 0f) else SpatialPose.Input(0f, sign*input)
                                val moved = contour()
                                assertTrue("Circle area stretched: ${moved.toList()}", abs(moved[0] - base[0]) < base[0] * 0.02)
                                assertTrue("Circle width stretched: $mode/$crop/$strength ${moved.toList()}", abs(moved[1] - base[1]) <= 3)
                                assertTrue("Circle height stretched: $mode/$crop/$strength ${moved.toList()}", abs(moved[2] - base[2]) <= 3)
                                val d = renderer.diagnostics
                                val cover = SpatialMath.cover(720f/1280, 720f/1280, crop)
                                val requested = old * (if (strength == 0.5f) 1f else 2f)
                                val allowed = SpatialMath.safeAmplitude(requested, cover, 720, 1280, 720, 1280)
                                assertEquals(requested * 100, d.targetPercent, 0.0001f)
                                assertEquals(allowed * 100, d.safePercent, 0.0001f)
                                val expectedNear = input * allowed * 720 * (1f+SpatialMath.CAMERA_SHARE)
                                val measuredNear = abs(moved[3+axis] - base[3+axis])
                                assertEquals("Readback must follow real uniform, not just UI", expectedNear, measuredNear.toFloat(), 2f)
                                assertEquals(input * allowed * 720, abs(if (axis == 0) d.estimatedNearFarPixelsX else d.estimatedNearFarPixelsY), 0.1f)
                                if (strength == 0.5f) oldReadback = measuredNear
                                else if (crop == 0.3f) assertEquals("Unclipped new 100 must double old 100/new 50", 2f*oldReadback, measuredNear.toFloat(), 2f)
                                if (crop == 0.04f && requested > allowed+0.0001f) assertTrue(d.limitReason.contains("裁切余量不足"))
                                var fallback = -1
                                if (strength == 1f && sign > 0) {
                                    renderer.showFallback = true
                                    fallback = contour()[5]
                                    renderer.showFallback = false
                                }
                                rows.add("$mode,$crop,$axis,$sign,$strength,$requested,$allowed,$expectedNear,$measuredNear,$fallback")
                            }
                        }
                    }
                }
                // Diagnostic boost shares the same doubled enhanced request and safety cap.
                prefs.edit().putFloat("strength", 0.1f).putString("parallax_mode", "stable").commit()
                renderer.diagnosticBoost = true; renderer.onDrawFrame(null)
                assertEquals(12f, renderer.diagnostics.targetPercent, 0.0001f)
                File(context.cacheDir, "strength-comparison.csv").writeText(rows.joinToString("\n") + "\n")
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

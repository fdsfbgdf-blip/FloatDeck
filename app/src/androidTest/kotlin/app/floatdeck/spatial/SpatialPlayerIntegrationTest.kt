package app.floatdeck.spatial

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.floatdeck.R
import app.floatdeck.service.FloatDeckWallpaperService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Runs Android's real decoder, private storage, preferences, activity lifecycle and EGL preview. */
class SpatialPlayerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context

    @Before fun setup() {
        val base = instrumentation.targetContext
        val testRoot = File(base.cacheDir, "integration-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }
        }
    }

    private fun sample(name: String, target: Context = context): File {
        val file = File(target.cacheDir, "$name-${UUID.randomUUID()}.zip")
        instrumentation.context.assets.open("$name.spatial.zip").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun rewrite(source: File, name: String, replacement: ByteArray): File {
        val out = File(context.cacheDir, "modified-${UUID.randomUUID()}.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            ZipInputStream(source.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name)); zip.write(if (entry.name == name) replacement else bytes); zip.closeEntry()
                }
            }
        }
        return out
    }

    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } } finally { bitmap.recycle() }
    }

    @Test fun realZipImportsAllAssetsAndPresetAndRetainsOriginalBytes() {
        val archive = sample("sunrise")
        val entry = SpatialPackageStore.import(context, Uri.fromFile(archive)).entry
        assertEquals(0.45f, entry.strength, 0.0001f)
        assertEquals(8f, entry.manifest.angle, 0.0001f)
        for (name in listOf("image.png", "depth.png", "mask.png")) {
            val bitmap = BitmapFactory.decodeFile(File(entry.directory, name).path)
            assertNotNull(bitmap); assertEquals(1080, bitmap.width); assertEquals(1920, bitmap.height); bitmap.recycle()
        }
        assertTrue(File(entry.directory, "source/wallpaper.png").isFile)
        assertTrue(SpatialPackageStore.import(context, Uri.fromFile(archive)).duplicate)
    }

    @Test fun inverseDepthNormalizesToSameEngineDepth() {
        val one = SpatialPackageStore.import(context, Uri.fromFile(sample("sunrise"))).entry
        val two = SpatialPackageStore.import(context, Uri.fromFile(sample("dusk"))).entry
        val a = BitmapFactory.decodeFile(File(one.directory, "depth.png").path)
        val b = BitmapFactory.decodeFile(File(two.directory, "depth.png").path)
        try {
            for ((x, y) in listOf(100 to 100, 700 to 1400, 350 to 900)) assertEquals(a.getPixel(x, y), b.getPixel(x, y))
        } finally { a.recycle(); b.recycle() }
    }

    @Test fun badImageTypeAndMisregisteredDimensionsAndColoredDepthRollBack() {
        val archive = sample("sunrise")
        val library = SpatialPackageStore.library(context)
        val original = library.importArchive(archive).entry
        library.select(original.id)
        for (bytes in listOf("not a png".toByteArray(), png(100, 100, Color.WHITE), png(1080, 1920, Color.RED))) {
            val bad = rewrite(archive, "depth.png", bytes)
            val failure = runCatching { SpatialPackageStore.import(context, Uri.fromFile(bad)) }
            assertTrue(failure.isFailure)
            assertEquals(original.id, SpatialPackageStore.library(context).selected()!!.id)
            assertEquals(1, SpatialPackageStore.library(context).entries().size)
        }
    }

    @Test fun optionalMaskAbsentRemovesStaleMaskAndPreviewDoesNotPublishWallpaper() {
        val entry = SpatialPackageStore.import(context, Uri.fromFile(sample("sunrise"))).entry
        val preview = context.getSharedPreferences("test-preview-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val live = context.getSharedPreferences("test-live-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        live.edit().putString("image", "unchanged").commit()
        SpatialPackageStore.publish(entry, preview, false)
        assertEquals("unchanged", live.getString("image", null))
        assertEquals(entry.id, preview.getString("package_id", null))
        SpatialPackageStore.publish(entry.copy(manifest = entry.manifest.copy(mask = null)), preview, false)
        assertFalse(preview.contains("mask"))
        SpatialPackageStore.publish(entry, live, true)
        assertTrue(live.getBoolean("enabled", false))
        assertTrue(File(live.getString("depth", "")!!).isFile)
        assertEquals(entry.id, live.getString("package_id", null))
    }

    @Test fun realImageDimensionLimitRejectsHugePngBeforeDecodingPixels() {
        val archive = sample("sunrise")
        val source = png(1, 1, Color.WHITE)
        // Rewrite PNG IHDR dimensions with valid CRC. Header probe must reject 10000x10000.
        val data = source.copyOf()
        java.nio.ByteBuffer.wrap(data).putInt(16, 10000).putInt(20, 10000)
        val crc = java.util.zip.CRC32().apply { update(data, 12, 17) }
        java.nio.ByteBuffer.wrap(data).putInt(29, crc.value.toInt())
        assertTrue(runCatching { SpatialPackageStore.import(context, Uri.fromFile(rewrite(archive, "wallpaper.png", data))) }.isFailure)
    }

    @Test fun activityRestoresSelectedPackageAfterRecreationAndRoutesSystemWallpaperIntent() {
        val target = instrumentation.targetContext
        val entry = SpatialPackageStore.import(target, Uri.fromFile(sample("sunrise", target))).entry
        SpatialPackageStore.library(target).select(entry.id)
        SpatialPackageStore.library(target).setStrength(entry.id, 0.37f)
        ActivityScenario.launch(SpatialActivity::class.java).use { scenario ->
            fun waitForLoaded() {
                val deadline = System.currentTimeMillis() + 15_000
                while (true) {
                    var loaded = false
                    scenario.onActivity { activity ->
                        loaded = activity.findViewById<TextView>(R.id.spatial_title).text.toString() == entry.manifest.name && activity.findViewById<Button>(R.id.spatial_apply).isEnabled
                    }
                    if (loaded) return
                    assertTrue("Activity did not restore selected package", System.currentTimeMillis() < deadline)
                    Thread.sleep(100)
                }
            }
            waitForLoaded()
            scenario.recreate()
            waitForLoaded()
            // Read actual GLSurfaceView pixels, rather than treating a loaded label
            // as evidence that the package reached the production GL renderer.
            Thread.sleep(1000)
            val copied = java.util.concurrent.CountDownLatch(1)
            var copyResult = -1
            var screenshot: Bitmap? = null
            scenario.onActivity { activity ->
                val surface = activity.findViewById<android.opengl.GLSurfaceView>(R.id.spatial_preview)
                screenshot = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(surface, screenshot!!, { result -> copyResult = result; copied.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            assertTrue(copied.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS, copyResult)
            screenshot!!.let { bitmap ->
                val colors = mutableSetOf<Int>()
                for (y in 1..4) for (x in 1..4) colors.add(bitmap.getPixel(bitmap.width * x / 5, bitmap.height * y / 5))
                assertTrue("Preview must contain the imported scene, not a blank GL surface", colors.size >= 3)
                File(target.cacheDir, "spatial-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            scenario.onActivity { activity ->
                val preview = SpatialPackageStore.previewPrefs(activity)
                assertEquals(entry.id, preview.getString("package_id", null))
                assertEquals(0.37f, preview.getFloat("strength", 0f), 0.0001f)
                assertNotNull(activity.findViewById<android.opengl.GLSurfaceView>(R.id.spatial_preview))
                val intent = SpatialWallpaperIntent.create(activity)
                assertEquals(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, intent.action)
                val component = intent.getParcelableExtra<android.content.ComponentName>(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT)
                assertEquals(FloatDeckWallpaperService::class.java.name, component!!.className)
                // Exercise the public apply button and its publish path; system confirmation is manual.
                activity.findViewById<Button>(R.id.spatial_apply).performClick()
            }
            val deadline = System.currentTimeMillis() + 15_000
            while (SpatialAssets.prefs(target).getString("package_id", null) != entry.id) {
                assertTrue(System.currentTimeMillis() < deadline); Thread.sleep(100)
            }
            assertTrue(SpatialAssets.prefs(target).getBoolean("enabled", false))
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
}

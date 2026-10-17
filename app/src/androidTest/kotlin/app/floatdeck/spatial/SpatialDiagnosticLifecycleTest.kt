package app.floatdeck.spatial

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.SeekBar
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.floatdeck.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SpatialDiagnosticLifecycleTest {
    @Test fun forcedViewBypassesSensorsSurvivesBackgroundAndRestoresGyroscopeAndNewPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun load(name: String): SpatialLibrary.Entry {
            val file = File(context.cacheDir, "lifecycle-${UUID.randomUUID()}.zip")
            try {
                instrumentation.context.assets.open("$name.spatial.zip").use { input -> file.outputStream().use { input.copyTo(it) } }
                return SpatialPackageStore.library(context).importArchive(file).entry
            } finally { file.delete() }
        }
        val entry = load("diagnostic_3d")
        val control = load("diagnostic_constant")
        val prefs = SpatialPackageStore.previewPrefs(context)
        SpatialPackageStore.publish(entry, prefs, false)
        ActivityScenario.launch(SpatialDiagnosticActivity::class.java).use { scenario ->
            fun waitFor(predicate: (SpatialRenderStatus) -> Boolean) {
                val deadline = System.currentTimeMillis() + 15_000
                while (true) {
                    var success = false
                    scenario.onActivity { success = predicate(it.renderStatus) }
                    if (success) return
                    assertTrue("Diagnostic state timed out", System.currentTimeMillis() < deadline)
                    Thread.sleep(100)
                }
            }
            fun drag(id: Int, fraction: Float) {
                scenario.onActivity { activity ->
                    val seek = activity.findViewById<SeekBar>(id)
                    seek.requestRectangleOnScreen(Rect(0, 0, seek.width, seek.height), true)
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(200) // Wait for scrolling/window transactions before screen-coordinate injection.
                var x = 0f; var y = 0f
                scenario.onActivity { activity ->
                    val seek = activity.findViewById<SeekBar>(id)
                    assertTrue("Diagnostic window must own input focus", activity.hasWindowFocus())
                    val visible = Rect()
                    assertTrue("Slider must be on screen", seek.getGlobalVisibleRect(visible))
                    assertTrue("Slider must be fully visible", visible.height() == seek.height)
                    val point = IntArray(2); seek.getLocationOnScreen(point)
                    x = point[0] + seek.paddingLeft + fraction * (seek.width - seek.paddingLeft - seek.paddingRight)
                    y = point[1] + seek.height / 2f
                }
                val time = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
                instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, y, 0))
                instrumentation.waitForIdleSync()
            }
            waitFor { it.ready && it.depthMin == 16 && it.depthMax == 240 }
            drag(R.id.spatial_diagnostic_horizontal, 0.95f)
            waitFor { it.forced && it.viewX > 0.7f }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor { it.ready && it.forced && it.viewX > 0.7f && it.strength == 1f }
            drag(R.id.spatial_diagnostic_vertical, 0.05f)
            waitFor { it.forced && it.viewY < -0.7f }
            SpatialPackageStore.publish(control.copy(strength = 0.42f), prefs, false)
            waitFor { it.ready && it.packageId == control.id && it.depthMin == 128 && it.depthMax == 128 && it.strength == 0.42f && it.angle == 15f }
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(R.id.spatial_diagnostic_restore)
                button.performClick()
            }
            waitFor { !it.forced && it.ready }
            // Preserve the original smoke-test fixture selection/settings after this test.
            val saved = SpatialPackageStore.library(context).selected()
            if (saved != null) SpatialPackageStore.publish(saved, prefs, false)
        }
    }
}

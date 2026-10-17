package app.floatdeck.spatial

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.opengl.GLSurfaceView
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.floatdeck.sensor.SensorHandler
import app.floatdeck.service.FloatDeckWallpaperService
import java.util.concurrent.Executors

/** Separate entry point leaves all original template settings intact. */
class SpatialActivity : ComponentActivity() {
    private lateinit var preview: GLSurfaceView
    private lateinit var renderer: SpatialRenderer
    private lateinit var sensor: SensorHandler
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var importing = "image"
    private var busy = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val key = importing
            task {
                SpatialAssets.import(this, uri, key)
                "导入完成：$key。白色近、黑色远；请确保原图与深度图对齐。"
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            @Suppress("DEPRECATION")
            sensor.screenRotation = windowManager.defaultDisplay.rotation
            renderer.tiltX = sensor.pitchY
            renderer.tiltY = sensor.rollX
            preview.requestRender()
            handler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensor = SensorHandler(this)
        renderer = SpatialRenderer(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 12) }
        // Native insets preserve controls on gesture navigation and cutout devices.
        layout.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(20, insets.systemWindowInsetTop + 12, 20, insets.systemWindowInsetBottom + 12)
            insets
        }
        setContentView(layout)
        layout.addView(TextView(this).apply { text = "FloatDeck Spatial Wallpaper V1.0"; textSize = 20f })
        preview = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(controls)
        layout.addView(scroll, LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels / 2))
        status = TextView(this).apply { text = "B 空间 3D：先导入原图，再导入同构图深度图。测试场景含远处太阳、中景山坡、近景树。" }
        controls.addView(status)
        fun button(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text = label; setOnClickListener { if (!busy) action() } })
        }
        button("A 普通动态壁纸 / 原版设置") {
            SpatialAssets.prefs(this).edit().putBoolean("enabled", false).apply()
            startActivity(Intent(this, app.floatdeck.settings.SettingsActivity::class.java))
        }
        button("生成原创测试原图与深度图") { task { SpatialAssets.demo(this); "测试素材已生成，倾斜手机检查三个深度层。" } }
        for ((key, label) in listOf("image" to "导入原图 JPG / PNG / WebP", "depth" to "导入灰度深度图（白近黑远）", "mask" to "导入主体遮罩（白色主体，黑色背景）")) {
            button(label) { importing = key; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
        }
        button("查看原图 / 深度图") {
            val row = LinearLayout(this)
            for (key in listOf("image", "depth")) {
                val path = SpatialAssets.prefs(this).getString(key, null)
                row.addView(ImageView(this).apply {
                    if (path != null) setImageURI(android.net.Uri.fromFile(java.io.File(path)))
                    adjustViewBounds = true
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            android.app.AlertDialog.Builder(this).setTitle("原图 / 深度图").setView(row).setPositiveButton("关闭", null).show()
        }
        slider(controls, "景深强度", "strength", 0f, 1f, 0.5f)
        slider(controls, "倾斜灵敏度", "sensitivity", 0.2f, 3f, 1f)
        slider(controls, "最大视角（度）", "angle", 1f, 15f, 8f)
        slider(controls, "额外裁切", "crop", 0f, 0.3f, 0.04f)
        button("B 应用空间 3D 动态壁纸") {
            val prefs = SpatialAssets.prefs(this)
            if (prefs.getString("image", null) == null || prefs.getString("depth", null) == null) {
                status.text = "请先导入原图和深度图，或生成测试素材。"
            } else {
                prefs.edit().putBoolean("enabled", true).apply()
                runCatching {
                    startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, FloatDeckWallpaperService::class.java)))
                }.onFailure { status.text = "系统未提供壁纸预览入口：${it.message}" }
            }
        }
        button("C 锁屏景深适配与预览") { startActivity(Intent(this, DepthClockActivity::class.java)) }
        button("D 组合效果：切换应用内空间视差 + 景深时钟预览") {
            if (SpatialAssets.prefs(this).getString("mask", null) == null) {
                status.text = "请先在 C 中生成主体遮罩，或导入遮罩。"
            } else {
                renderer.showClock = !renderer.showClock
                status.text = "组合预览使用自绘时钟，仅在应用内显示；不应用到系统锁屏。"
            }
        }
        controls.addView(TextView(this).apply { text = "D 组合效果：仅提供应用预览。未验证系统原生接口，实际锁屏组合入口禁用。动态壁纸的锁屏应用范围由系统选择器决定。图片按最长边 2048 限制纹理内存，不生成或修改人物内容。" })
    }

    private fun slider(parent: LinearLayout, label: String, key: String, min: Float, max: Float, default: Float) {
        val prefs = SpatialAssets.prefs(this)
        val title = TextView(this)
        parent.addView(title)
        val value = prefs.getFloat(key, default)
        title.text = "$label：${"%.2f".format(value)}"
        parent.addView(SeekBar(this).apply {
            this.max = 100
            progress = ((value - min) / (max - min) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + (max - min) * progress / 100f
                    prefs.edit().putFloat(key, v).apply()
                    title.text = "$label：${"%.2f".format(v)}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
    }

    private fun task(action: () -> String) {
        busy = true
        status.text = "正在处理图片…"
        worker.execute {
            val result = runCatching(action).getOrElse { "处理失败：${it.message}" }
            runOnUiThread { busy = false; status.text = result; preview.requestRender() }
        }
    }

    override fun onResume() {
        super.onResume()
        preview.onResume()
        sensor.register()
        handler.post(tick)
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        sensor.unregister()
        preview.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

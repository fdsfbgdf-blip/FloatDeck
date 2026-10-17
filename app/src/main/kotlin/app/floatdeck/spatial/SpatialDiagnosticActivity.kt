package app.floatdeck.spatial

import android.app.WallpaperManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.opengl.GLSurfaceView
import android.widget.*
import androidx.activity.ComponentActivity
import app.floatdeck.R
import app.floatdeck.sensor.SensorHandler

/** Temporary, page-local test controls. Forced view never changes wallpaper or saved presets. */
class SpatialDiagnosticActivity : ComponentActivity() {
    private lateinit var renderer: SpatialRenderer
    private lateinit var sensor: SensorHandler
    private lateinit var preview: GLSurfaceView
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())
    internal val renderStatus get() = renderer.diagnostics
    private var manual = false
    private var x = 0f
    private var y = 0f
    private var originalSize = "读取中"
    private var lastInfo = 0L
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val tick = object : Runnable {
        override fun run() {
            @Suppress("DEPRECATION")
            sensor.screenRotation = windowManager.defaultDisplay.rotation
            renderer.tiltX = sensor.spatialInput.x
            renderer.tiltY = sensor.spatialInput.y
            renderer.forcedView = if (manual) SpatialPose.Input(x, y) else null
            preview.requestRender()
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastInfo >= 200) { updateInfo(); lastInfo = now }
            handler.postDelayed(this, 33)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensor = SensorHandler(this)
        renderer = SpatialRenderer(this, SpatialPackageStore.previewPrefs(this))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(16, insets.systemWindowInsetTop + 8, 16, insets.systemWindowInsetBottom + 8)
            insets
        }
        setContentView(root)
        root.addView(TextView(this).apply { text = "3D 效果检测（临时诊断）"; textSize = 20f })
        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (manager.deviceConfigurationInfo.reqGlEsVersion < 0x30000) {
            root.addView(TextView(this).apply { text = "A：此设备未提供 OpenGL ES 3.0，无法建立空间渲染。" })
            return
        }
        preview = GLSurfaceView(this).apply {
            id = R.id.spatial_diagnostic_preview
            setEGLContextClientVersion(3); setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels * 45 / 100))
        info = TextView(this).apply { id = R.id.spatial_diagnostic_info; textSize = 12f; setTextIsSelectable(true) }
        controls.addView(info)
        fun slider(label: String, id: Int, set: (Float) -> Unit): SeekBar {
            val title = TextView(this).apply { text = "$label：0.00" }
            controls.addView(title)
            return SeekBar(this).apply {
                this.id = id; max = 200; progress = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) {
                            title.text = "$label：${f((progress - 100) / 100f)}"
                            return
                        }
                        manual = true
                        val value = (progress - 100) / 100f
                        set(value)
                        renderer.forcedView = SpatialPose.Input(x, y)
                        title.text = "$label：${f(value)}（已绕过传感器）"
                        preview.requestRender()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
                controls.addView(this)
            }
        }
        val horizontal = slider("强制水平视角", R.id.spatial_diagnostic_horizontal) { x = it }
        val vertical = slider("强制垂直视角", R.id.spatial_diagnostic_vertical) { y = it }
        controls.addView(Button(this).apply {
            id = R.id.spatial_diagnostic_restore; text = "恢复陀螺仪（以当前握姿重新居中）"
            setOnClickListener { manual = false; x = 0f; y = 0f; horizontal.progress = 100; vertical.progress = 100; renderer.forcedView = null; sensor.recenterSpatial() }
        })
        controls.addView(CheckBox(this).apply {
            text = "使用明显检测幅度（仅本页：强度 1 / 最大角 15°）"
            setOnCheckedChangeListener { _, checked -> renderer.diagnosticBoost = checked }
        })
        controls.addView(TextView(this).apply { text = "先导入 diagnostic_3d：来回拖滑块，上方远景应移动少，下方近景应移动多。再导入恒深度包：只能整体平移，不应有层间差异。\nA 滑块也不动：看 GL/深度/强度，截图反馈。B 滑块正常、转手机不动：看事件计数、事件年龄与姿态。C 两者正常、原包不明显：比较强度与估计像素差。结论须结合屏幕实测，GL=0 不代表真机视觉通过。\n本页不应用壁纸，不保存强制视角；返回主界面后可正常设为动态壁纸。" })
        worker.execute {
            val path = SpatialPackageStore.previewPrefs(this).getString("original", null)
            val size = runCatching {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, options)
                "${options.outWidth}×${options.outHeight}"
            }.getOrDefault("不可读取")
            runOnUiThread { originalSize = size }
        }
    }
    private fun f(value: Float) = "%.3f".format(java.util.Locale.US, value)
    private fun age(nanos: Long): String = if (nanos == 0L) "尚无事件" else "${(android.os.SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000} ms"
    private fun updateInfo() {
        val s = renderer.diagnostics
        val input = sensor.spatialInput
        val component = runCatching { WallpaperManager.getInstance(this).wallpaperInfo?.component?.flattenToShortString() }.getOrNull() ?: "静态壁纸/系统未返回组件"
        val sessions = SpatialWallpaperRuntime.sessions.values.sortedByDescending { it.render.lastFrameNanos }.take(3)
        info.text = buildString {
            appendLine("当前：${SpatialPackageStore.previewPrefs(this@SpatialDiagnosticActivity).getString("package_name", s.name)}；模式：空间 GPU / ${if (manual) "强制视角，完全绕过姿态" else "相对姿态"}")
            appendLine("源原图：$originalSize；实际上传原图：${s.imageWidth}×${s.imageHeight}；深度：${s.depthWidth}×${s.depthHeight}")
            appendLine("白近黑远；最小 ${s.depthMin} / 最大 ${s.depthMax} / 变化范围 ${s.depthMax - s.depthMin} / ${s.depthLevels} 个灰度级")
            appendLine("${s.message}；纹理 ${s.imageTexture}/${s.depthTexture}；帧数 ${s.frames}；最近帧 ${age(s.lastFrameNanos)}")
            appendLine("${s.glVersion}；${s.gpu}")
            appendLine("传感器：${sensor.sensorName}；注册=${sensor.registered}；事件=${sensor.eventCount}；年龄=${age(sensor.lastEventNanos)}；精度=${sensor.accuracy}")
            appendLine("原始值：[${sensor.rawValues}]；相对角：${f(input.yawDegrees)}° / ${f(input.pitchDegrees)}° / 侧倾${f(input.bankDegrees)}°；输入 ${f(input.x)}, ${f(input.y)}")
            appendLine("实际视角 ${f(s.viewX)}, ${f(s.viewY)}；uniform 位移 ${f(s.motionX)}, ${f(s.motionY)}")
            appendLine("强度 ${f(s.strength)}；灵敏度 ${f(s.sensitivity)}；最大角 ${f(s.angle)}°；裁切 ${f(s.crop)}；${if (s.boosted) "本页检测幅度" else "包/用户参数"}")
            appendLine("估计本预览近远像素差：水平 ${f(s.estimatedNearFarPixelsX)} / 垂直 ${f(s.estimatedNearFarPixelsY)}（不是实测像素）")
            if (s.depthMax - s.depthMin < 16) appendLine("C 提示：深度变化很小/恒定；预期无法形成明显层间视差。")
            if (s.strength == 0f) appendLine("C 提示：强度为 0，任何视角输入都会静止。")
            appendLine("系统当前组件：$component")
            if (sessions.isEmpty()) appendLine("壁纸服务：本进程暂无引擎记录；先在主界面设为动态壁纸，再回此页检查。")
            for (session in sessions) {
                appendLine("${if (session.preview) "系统预览引擎" else "已安装壁纸引擎"}[${session.id.take(6)}]：${if (session.spatial) "空间模式" else "原版模式"}，${session.render.name}；可见=${session.visible}，运行=${session.running}；传感器=${session.sensorRegistered} / 事件${session.sensorEvents}；渲染帧${session.render.frames} / 年龄${age(session.render.lastFrameNanos)}；强度${f(session.render.strength)} / 深度${session.render.depthMin}-${session.render.depthMax} / ${session.render.message}；${session.message}")
            }
            appendLine("打开本页时桌面通常不可见，壁纸引擎暂停属正常；后台恢复/亮屏后再检查记录。")
        }
    }
    override fun onResume() { super.onResume(); if (::preview.isInitialized) { preview.onResume(); sensor.register(); handler.post(tick) } }
    override fun onPause() { handler.removeCallbacks(tick); sensor.unregister(); if (::preview.isInitialized) preview.onPause(); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

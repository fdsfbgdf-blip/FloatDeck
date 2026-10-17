package app.floatdeck.spatial

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.floatdeck.R
import app.floatdeck.sensor.SensorHandler
import app.floatdeck.service.FloatDeckWallpaperService
import java.util.concurrent.Executors

object SpatialWallpaperIntent {
    fun create(context: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, FloatDeckWallpaperService::class.java))
}

/** Consumer player. Package selection affects preview until the user explicitly applies it. */
class SpatialActivity : ComponentActivity() {
    private lateinit var preview: GLSurfaceView
    private lateinit var renderer: SpatialRenderer
    private lateinit var sensor: SensorHandler
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var strength: SeekBar
    private lateinit var strengthLabel: TextView
    private val buttons = mutableListOf<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var selected: SpatialLibrary.Entry? = null
    private var busy = true
    private var generation = 0
    private var previewAvailable = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task("正在导入空间壁纸…") {
            val imported = SpatialPackageStore.import(this, uri)
            val entry = select(imported.entry.id)
            entry to if (imported.duplicate) "这套壁纸已经保存，已为你打开。" else "导入成功，倾斜手机即可预览。"
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            @Suppress("DEPRECATION")
            sensor.screenRotation = windowManager.defaultDisplay.rotation
            renderer.tiltX = sensor.spatialInput.x
            renderer.tiltY = sensor.spatialInput.y
            preview.requestRender()
            handler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 12) }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(20, insets.systemWindowInsetTop + 12, 20, insets.systemWindowInsetBottom + 12)
            insets
        }
        setContentView(layout)
        title = TextView(this).apply { id = R.id.spatial_title; text = "FloatDeck Spatial Wallpaper V1.1"; textSize = 20f }
        layout.addView(title)
        status = TextView(this).apply { id = R.id.spatial_status; text = "导入购买或下载的空间壁纸 ZIP，即可预览并设置。" }
        val configuration = (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).deviceConfigurationInfo
        previewAvailable = configuration.reqGlEsVersion >= 0x30000
        sensor = SensorHandler(this)
        if (previewAvailable) {
            renderer = SpatialRenderer(this, SpatialPackageStore.previewPrefs(this))
            preview = GLSurfaceView(this).apply {
                id = R.id.spatial_preview
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            val viewport = android.widget.FrameLayout(this)
            viewport.addView(preview)
            viewport.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val screen = resources.displayMetrics
                val aspect = screen.widthPixels.toFloat() / screen.heightPixels.coerceAtLeast(1)
                val h = minOf(view.height, (view.width / aspect).toInt()).coerceAtLeast(1)
                val w = minOf(view.width, (h * aspect).toInt()).coerceAtLeast(1)
                val previous = preview.layoutParams
                if (previous.width != w || previous.height != h) {
                    preview.layoutParams = android.widget.FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
                }
            }
            layout.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            layout.addView(TextView(this).apply { text = "此设备不支持 OpenGL ES 3.0，无法播放空间壁纸。" }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        layout.addView(status)
        fun button(label: String, viewId: Int = android.view.View.NO_ID, action: () -> Unit) {
            val button = Button(this).apply { text = label; id = viewId; setOnClickListener { if (!busy) action() } }
            buttons.add(button)
            layout.addView(button)
        }
        button("导入空间壁纸 ZIP", R.id.spatial_import) {
            picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        button("已保存的空间壁纸 · 切换", R.id.spatial_library) { showLibrary() }
        strengthLabel = TextView(this)
        layout.addView(strengthLabel)
        strength = SeekBar(this).apply {
            id = R.id.spatial_strength
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || busy || selected == null) return
                    val value = progress / 100f
                    selected = selected!!.copy(strength = value)
                    SpatialPackageStore.previewPrefs(this@SpatialActivity).edit().putFloat("strength", value).apply()
                    strengthLabel.text = "空间效果强度：$progress%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val entry = selected ?: return
                    task("正在保存强度…") {
                        SpatialPackageStore.library(this@SpatialActivity).setStrength(entry.id, entry.strength)
                        entry to "强度已保存；点击设为动态壁纸后用于系统壁纸。"
                    }
                }
            })
        }
        layout.addView(strength)
        button("设为动态壁纸", R.id.spatial_apply) { applyWallpaper() }
        button("3D 效果检测", R.id.spatial_diagnostic_open) { startActivity(Intent(this, SpatialDiagnosticActivity::class.java)) }
        button("更多：原版动态壁纸 / 高级工具 / 时钟预览") {
            android.app.AlertDialog.Builder(this).setItems(arrayOf("普通动态壁纸（原版）", "高级技术工具", "已有景深时钟预览")) { _, which ->
                when (which) {
                    0 -> {
                        SpatialAssets.prefs(this).edit().putBoolean("enabled", false).apply()
                        startActivity(Intent(this, app.floatdeck.settings.SettingsActivity::class.java))
                    }
                    1 -> startActivity(Intent(this, SpatialToolsActivity::class.java))
                    2 -> startActivity(Intent(this, DepthClockActivity::class.java))
                }
            }.show()
        }
        layout.addView(TextView(this).apply { text = "桌面或锁屏应用范围以系统选择器为准。仅选择预览不会替换当前壁纸。"; textSize = 12f })
        task("正在读取壁纸库…") {
            val entry = SpatialPackageStore.library(this).selected()
            if (entry != null) SpatialPackageStore.publish(entry, SpatialPackageStore.previewPrefs(this), false)
            entry to if (entry == null) "点击导入空间壁纸 ZIP 开始使用。" else "已恢复：${entry.manifest.name}。倾斜手机预览。"
        }
    }

    private fun select(id: String): SpatialLibrary.Entry {
        val entry = SpatialPackageStore.library(this).select(id)
        SpatialPackageStore.publish(entry, SpatialPackageStore.previewPrefs(this), false)
        return entry
    }

    private fun showLibrary() {
        var entries = emptyList<SpatialLibrary.Entry>()
        task("正在读取壁纸库…", after = {
            if (entries.isEmpty()) status.text = "壁纸库为空，请先导入 ZIP。"
            else {
                val labels = entries.map { (if (it.id == selected?.id) "✓ " else "") + it.manifest.name }.toTypedArray()
                android.app.AlertDialog.Builder(this).setTitle("已保存的空间壁纸").setItems(labels) { _, index ->
                    task("正在加载壁纸…") { val entry = select(entries[index].id); entry to "正在预览：${entry.manifest.name}" }
                }.setNegativeButton("关闭", null).setNeutralButton("删除未选中壁纸") { _, _ -> showDelete(entries) }.show()
            }
        }) {
            entries = SpatialPackageStore.library(this).entries()
            selected to "请选择要预览的壁纸。"
        }
    }

    private fun showDelete(entries: List<SpatialLibrary.Entry>) {
        val applied = SpatialAssets.prefs(this).getString("package_id", null)
        val available = entries.filter { it.id != selected?.id && it.id != applied }
        if (available.isEmpty()) { status.text = "没有可删除的壁纸。当前预览与已应用壁纸会被保留。"; return }
        android.app.AlertDialog.Builder(this).setTitle("删除已保存壁纸").setItems(available.map { it.manifest.name }.toTypedArray()) { _, index ->
            val entry = available[index]
            android.app.AlertDialog.Builder(this).setMessage("删除“${entry.manifest.name}”？").setPositiveButton("删除") { _, _ ->
                task("正在删除…") { SpatialPackageStore.library(this).delete(entry.id); selected to "壁纸已删除。" }
            }.setNegativeButton("取消", null).show()
        }.show()
    }

    private fun applyWallpaper() {
        val entry = selected
        if (entry == null || !previewAvailable) { status.text = "请先导入可播放的空间壁纸。"; return }
        val manager = WallpaperManager.getInstance(this)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) { status.text = "当前设备或管理策略不允许设置壁纸。"; return }
        task("正在准备系统壁纸预览…", after = {
            runCatching { startActivity(SpatialWallpaperIntent.create(this)) }.onFailure { status.text = "系统未提供动态壁纸选择器：${it.message}" }
        }) {
            SpatialPackageStore.library(this).setStrength(entry.id, entry.strength)
            SpatialPackageStore.publish(entry, SpatialAssets.prefs(this), true)
            entry to "请在系统预览中确认应用；桌面/锁屏范围由系统提供。"
        }
    }

    private fun task(message: String, after: (() -> Unit)? = null, action: () -> Pair<SpatialLibrary.Entry?, String>) {
        busy = true
        buttons.forEach { it.isEnabled = false }
        if (::strength.isInitialized) strength.isEnabled = false
        status.text = message
        val ticket = ++generation
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isFinishing || isDestroyed || ticket != generation) return@runOnUiThread
                busy = false
                buttons.forEach { it.isEnabled = true }
                strength.isEnabled = true
                result.onSuccess { (entry, text) ->
                    selected = entry
                    title.text = entry?.manifest?.name ?: "FloatDeck Spatial Wallpaper V1.1"
                    strength.progress = ((entry?.strength ?: 0.5f) * 100).toInt()
                    strengthLabel.text = "空间效果强度：${strength.progress}%"
                    status.text = text
                    if (previewAvailable) preview.requestRender()
                    after?.invoke()
                }.onFailure { status.text = "操作失败：${it.message ?: "文件无法处理"}。原有壁纸仍然保留。" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (previewAvailable) { preview.onResume(); sensor.register(); handler.post(tick) }
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        sensor.unregister()
        if (previewAvailable) preview.onPause()
        super.onPause()
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

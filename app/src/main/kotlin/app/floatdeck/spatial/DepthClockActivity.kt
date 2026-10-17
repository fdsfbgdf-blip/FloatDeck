package app.floatdeck.spatial

import android.graphics.*
import android.os.Bundle
import android.content.Intent
import android.widget.*
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** No clock is drawn by WallpaperService: this entire feature is an application preview. */
class DepthClockActivity : ComponentActivity() {
    private lateinit var canvasView: ClockView
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var exporting: File? = null
    private val saveOriginal = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = SpatialAssets.prefs(this).getString("original", null)
        if (uri != null && path != null) worker.execute {
            val result = runCatching { contentResolver.openOutputStream(uri).use { output -> requireNotNull(output); File(path).inputStream().use { it.copyTo(output) } } }
            runOnUiThread { status.text = if (result.isSuccess) "原始文件已无损导出。" else "导出失败：${result.exceptionOrNull()?.message}" }
        }
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val file = exporting
        if (uri != null && file != null) worker.execute {
            val result = runCatching { contentResolver.openOutputStream(uri).use { output -> requireNotNull(output); file.inputStream().use { it.copyTo(output) } } }
            runOnUiThread { status.text = if (result.isSuccess) "素材已导出；请在系统锁屏编辑器中手动选择并尝试景深选项。" else "导出失败：${result.exceptionOrNull()?.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(20, insets.systemWindowInsetTop + 12, 20, insets.systemWindowInsetBottom + 12)
            insets
        }
        setContentView(root)
        status = TextView(this).apply { text = "仅应用内自绘时钟预览，非系统时钟。公开 Android API 无法让主体覆盖系统锁屏时间。HyperOS / One UI 功能依版本而异，本应用没有已验证的原生景深接入，实际组合应用禁用。" }
        root.addView(status)
        canvasView = ClockView()
        root.addView(canvasView, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels / 3))
        fun slider(label: String, initial: Int, update: (Float) -> Unit) {
            controls.addView(TextView(this).apply { text = label })
            controls.addView(SeekBar(this).apply {
                max = 100; progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) { update(progress / 100f); canvasView.invalidate() }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
        }
        slider("时间水平位置", 50) { canvasView.clockX = it }
        slider("时间垂直位置", 25) { canvasView.clockY = it }
        slider("字号", 50) { canvasView.clockSize = 0.08f + it * 0.2f }
        slider("主体遮挡范围（从画面顶部向下）", 100) { canvasView.coverage = it }
        fun button(label: String, action: () -> Unit) { controls.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        button("时间颜色：白 / 黑 / 金") {
            canvasView.clockColor = when (canvasView.clockColor) { Color.WHITE -> Color.BLACK; Color.BLACK -> Color.rgb(255, 212, 100); else -> Color.WHITE }
            canvasView.invalidate()
        }
        button("从深度图阈值生成测试遮罩（非 AI 分割）") {
            worker.execute {
                val result = runCatching {
                    val prefs = SpatialAssets.prefs(this)
                    val depth = SpatialAssets.decode(File(requireNotNull(prefs.getString("depth", null)) { "请先导入深度图" }))
                    try {
                        val pixels = IntArray(depth.width * depth.height)
                        depth.getPixels(pixels, 0, depth.width, 0, 0, depth.width, depth.height)
                        for (i in pixels.indices) {
                            val value = ClockMath.depthMask(Color.red(pixels[i]), 180)
                            pixels[i] = Color.rgb(value, value, value)
                        }
                        depth.setPixels(pixels, 0, depth.width, 0, 0, depth.width, depth.height)
                        val file = File(filesDir, "mask-${java.util.UUID.randomUUID()}.png")
                        file.outputStream().use { depth.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        prefs.edit().putString("mask", file.path).apply()
                    } finally { depth.recycle() }
                }
                runOnUiThread { status.text = if (result.isSuccess) "阈值遮罩已生成；人物细发请导入精细遮罩。" else "生成失败：${result.exceptionOrNull()?.message}"; load() }
            }
        }
        button("导出完整原始文件（不缩放、不重新编码）") {
            val path = SpatialAssets.prefs(this).getString("original", null)
            if (path == null) status.text = "测试场景请使用下方 PNG 导出；导入个人图片后可导出原始文件。"
            else saveOriginal.launch("FloatDeck-original.${File(path).extension}")
        }
        for ((key, label) in listOf("image" to "导出预览原图 PNG（最长边 2048）", "mask" to "导出主体灰度遮罩", "foreground" to "导出透明主体 PNG")) {
            button(label) {
                if (key == "foreground") {
                    val bitmap = canvasView.foreground
                    if (bitmap == null) status.text = "请先导入或生成遮罩。"
                    else {
                        val file = File(cacheDir, "foreground.png")
                        worker.execute {
                            val result = runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            runOnUiThread { if (result.isSuccess) { exporting = file; save.launch("FloatDeck-foreground.png") } else status.text = "导出失败" }
                        }
                    }
                } else {
                    val file = SpatialAssets.prefs(this).getString(key, null)?.let(::File)
                    if (file == null) status.text = "尚未导入该素材。"
                    else { exporting = file; save.launch("FloatDeck-$key.png") }
                }
            }
        }
        button("系统适配说明") {
            android.app.AlertDialog.Builder(this).setMessage("导出原图，在系统设置 → 壁纸/锁屏编辑器中选择。若该系统版本提供“景深/主体遮挡”，由系统编辑器启用；没有该选项就不能通过本应用开启。透明主体与遮罩仅供支持导入的编辑器使用，并非通用厂商格式。系统可能不支持动态壁纸用于锁屏。应用不隐藏系统时钟、不申请悬浮窗、不替换安全锁屏。") .setPositiveButton("了解", null).show()
        }
        load()
    }

    private fun load() {
        worker.execute {
            val result = runCatching {
                val prefs = SpatialAssets.prefs(this)
                val source = SpatialAssets.decode(File(requireNotNull(prefs.getString("image", null)) { "请先导入原图" }))
                val maskFile = prefs.getString("mask", null)?.let(::File)
                val foreground = if (maskFile != null) {
                    val mask = SpatialAssets.decode(maskFile)
                    val scaled = Bitmap.createScaledBitmap(mask, source.width, source.height, true)
                    try {
                        val colors = IntArray(source.width * source.height)
                        val masks = IntArray(colors.size)
                        source.getPixels(colors, 0, source.width, 0, 0, source.width, source.height)
                        scaled.getPixels(masks, 0, source.width, 0, 0, source.width, source.height)
                        for (i in colors.indices) {
                            val m = masks[i]
                            val a = ClockMath.alpha(Color.red(m), Color.green(m), Color.blue(m), Color.alpha(m))
                            colors[i] = (colors[i] and 0xffffff) or (a shl 24)
                        }
                        Bitmap.createBitmap(colors, source.width, source.height, Bitmap.Config.ARGB_8888)
                    } finally { if (scaled !== mask) scaled.recycle(); mask.recycle() }
                } else null
                source to foreground
            }
            runOnUiThread {
                result.onSuccess { (image, foreground) -> canvasView.image = image; canvasView.foreground = foreground; canvasView.invalidate() }
                    .onFailure { status.text = "预览不可用：${it.message}" }
            }
        }
    }

    private inner class ClockView : View(this@DepthClockActivity) {
        var image: Bitmap? = null
        var foreground: Bitmap? = null
        var clockX = 0.5f
        var clockY = 0.25f
        var clockSize = 0.18f
        var clockColor = Color.WHITE
        var coverage = 1f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            val bitmap = image ?: return
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            val rect = RectF(left, top, left + w, top + h)
            paint.color = Color.WHITE
            canvas.drawBitmap(bitmap, null, rect, paint)
            canvas.save()
            canvas.clipRect(rect)
            paint.color = clockColor
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = w * clockSize
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText(time, left + w * clockX, top + h * clockY, paint)
            canvas.clipRect(left, top, left + w, top + h * coverage)
            paint.color = Color.WHITE
            foreground?.let { canvas.drawBitmap(it, null, rect, paint) }
            canvas.restore()
            postInvalidateDelayed(30_000L)
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

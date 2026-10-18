package app.floatdeck.spatial

import android.content.Context
import android.content.SharedPreferences
import android.widget.*

/** Player overrides are separate from the unchanged V1 ZIP and copied on apply. */
object SpatialMotionSettings {
    private fun saved(context: Context) = context.getSharedPreferences("spatial-motion-overrides", 0)
    fun publish(context: Context, id: String, edit: SharedPreferences.Editor, defaultSensitivity: Float) {
        val values = saved(context)
        edit.putFloat("sensitivity", values.getFloat("$id:sensitivity", defaultSensitivity).coerceIn(0.25f, 3f))
            .putFloat("response_speed", values.getFloat("$id:response_speed", 0.65f))
            .putString("parallax_mode", values.getString("$id:parallax_mode", "standard"))
    }
    fun set(context: Context, prefs: SharedPreferences, key: String, value: Float) {
        val id = prefs.getString("package_id", "") ?: ""
        saved(context).edit().putFloat("$id:$key", value).apply()
        prefs.edit().putFloat(key, value).apply()
    }
    fun setMode(context: Context, prefs: SharedPreferences, mode: String) {
        val id = prefs.getString("package_id", "") ?: ""
        saved(context).edit().putString("$id:parallax_mode", mode).apply()
        prefs.edit().putString("parallax_mode", mode).apply()
    }
}

class SpatialMotionControls(context: Context, private val prefs: SharedPreferences, recenter: () -> Unit) : LinearLayout(context) {
    private var refreshing = false
    private val sensitivity: SeekBar
    private val speed: SeekBar
    private val modes = listOf("stable", "standard", "enhanced")
    private val spinner: Spinner
    init {
        orientation = VERTICAL
        fun slider(label: String, key: String, minimum: Float, range: Float): SeekBar {
            val title = TextView(context)
            addView(title)
            val bar = SeekBar(context).apply { max = 100 }
            fun update(progress: Int) { title.text = "$label：${"%.2f".format(minimum + progress / 100f * range)}" }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, progress: Int, fromUser: Boolean) {
                    update(progress)
                    if (fromUser && !refreshing) SpatialMotionSettings.set(context, prefs, key, minimum + progress / 100f * range)
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
            addView(bar); update(0)
            return bar
        }
        sensitivity = slider("倾斜灵敏度", "sensitivity", 0.25f, 2.75f)
        speed = slider("视角响应速度（高更快）", "response_speed", 0f, 1f)
        addView(TextView(context).apply { text = "视差模式（满强度目标近远差，按屏幕宽度）：" })
        spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("稳定 · 6%", "标准 · 9%", "增强 · 12%"))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (!refreshing && modes[position] != prefs.getString("parallax_mode", "standard")) SpatialMotionSettings.setMode(context, prefs, modes[position])
                }
            }
        }
        addView(spinner)
        addView(Button(context).apply { text = "重新居中"; setOnClickListener { recenter() } })
        addView(TextView(context).apply { text = "灵敏度只影响倾斜响应；强度控制位移上限。6%–12% 为满强度、满视角理论请求（旧版的 2 倍），裁切余量或深度范围不足会限幅。单图无法恢复被遮挡背景，增强后请检查脸部与边缘。" })
        refresh()
    }
    fun refresh() {
        refreshing = true
        sensitivity.progress = ((prefs.getFloat("sensitivity", 1f) - 0.25f) / 2.75f * 100).toInt()
        speed.progress = (prefs.getFloat("response_speed", 0.65f) * 100).toInt()
        spinner.setSelection(modes.indexOf(prefs.getString("parallax_mode", "standard")).coerceAtLeast(0))
        refreshing = false
    }
}

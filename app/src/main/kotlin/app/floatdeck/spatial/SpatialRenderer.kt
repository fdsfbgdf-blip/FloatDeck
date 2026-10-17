package app.floatdeck.spatial

import android.content.Context
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import app.floatdeck.gl.ShaderProgram
import app.floatdeck.gl.Shaders
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Used unchanged by wallpaper and live preview. All GL resources belong to the GL thread. */
class SpatialRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private var program = 0
    private val textures = IntArray(2)
    private val clockTextures = IntArray(2)
    private var maskPath = ""
    private var minute = -1L
    private var maskReady = false
    @Volatile var showClock = false
    private var loaded = ""
    private var aspect = 1f
    private var width = 1
    private var height = 1
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    private var x = 0f
    private var y = 0f
    private var last = 0L
    private var ready = false
    private val prefs = SpatialAssets.prefs(context)
    private val uniforms = mutableMapOf<String, Int>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = ShaderProgram.compile(Shaders.spatialVertex, Shaders.spatialFragment)
        uniforms.clear()
        for (name in listOf("uImage", "uDepth", "uCover", "uMotion", "uClock", "uMask", "uClockEnabled")) uniforms[name] = GL.glGetUniformLocation(program, name)
        textures.fill(0)
        clockTextures.fill(0)
        minute = -1L
        maskPath = ""
        loaded = ""
        ready = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        minute = -1L
        this.width = maxOf(width, 1)
        this.height = maxOf(height, 1)
        GL.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GL.glClearColor(0.06f, 0.08f, 0.1f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        val paths = listOf(prefs.getString("image", ""), prefs.getString("depth", ""))
        val signature = paths.joinToString("|")
        if (signature != loaded) {
            loaded = signature
            ready = false
            GL.glDeleteTextures(2, textures, 0)
            textures.fill(0)
            if (paths.all { !it.isNullOrEmpty() && File(it!!).isFile }) {
                try {
                    GL.glGenTextures(2, textures, 0)
                    paths.forEachIndexed { index, path ->
                        val bitmap = SpatialAssets.decode(File(path!!))
                        try {
                            if (index == 0) aspect = bitmap.width.toFloat() / bitmap.height
                            GL.glBindTexture(GL.GL_TEXTURE_2D, textures[index])
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                            GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
                        } finally { bitmap.recycle() }
                    }
                    ready = GL.glGetError() == GL.GL_NO_ERROR
                } catch (error: Exception) {
                    android.util.Log.e("SpatialRenderer", "Texture load failed", error)
                }
            }
        }
        if (!ready) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.033f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        val alpha = 1f - kotlin.math.exp(-dt * 10f)
        x += (tiltX - x) * alpha
        y += (tiltY - y) * alpha
        val strength = prefs.getFloat("strength", 0.5f)
        val sensitivity = prefs.getFloat("sensitivity", 1f)
        val angle = prefs.getFloat("angle", 8f)
        val cover = SpatialMath.cover(aspect, width.toFloat() / height, prefs.getFloat("crop", 0.04f))
        GL.glDisable(GL.GL_BLEND)
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glUseProgram(program)
        for (i in 0..1) {
            GL.glActiveTexture(GL.GL_TEXTURE0 + i)
            GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
        }
        updateClock()
        GL.glUniform1i(uniforms.getValue("uClockEnabled"), if (showClock && maskReady) 1 else 0)
        GL.glUniform1i(uniforms.getValue("uClock"), 2)
        GL.glUniform1i(uniforms.getValue("uMask"), 3)
        GL.glUniform1i(uniforms.getValue("uImage"), 0)
        GL.glUniform1i(uniforms.getValue("uDepth"), 1)
        GL.glUniform2f(uniforms.getValue("uCover"), cover[0], cover[1])
        GL.glUniform2f(uniforms.getValue("uMotion"), SpatialMath.motion(x, sensitivity, angle, strength) * cover[0], SpatialMath.motion(y, sensitivity, angle, strength) * cover[1])
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        GL.glEnable(GL.GL_BLEND)
    }

    // Preview only. WallpaperService never enables showClock.
    private fun updateClock() {
        if (!showClock) return
        val path = prefs.getString("mask", "") ?: ""
        if (path != maskPath) {
            maskPath = path
            maskReady = false
            if (File(path).isFile) {
                runCatching {
                    val bitmap = SpatialAssets.decode(File(path))
                    try { uploadClockTexture(1, bitmap); maskReady = true } finally { bitmap.recycle() }
                }.onFailure { android.util.Log.e("SpatialRenderer", "Mask unavailable", it) }
            }
        }
        val currentMinute = System.currentTimeMillis() / 60_000L
        if (minute != currentMinute) {
            minute = currentMinute
            val h = maxOf(1, (512f * height / width).toInt()).coerceAtMost(2048)
            val bitmap = android.graphics.Bitmap.createBitmap(512, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 92f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            canvas.drawText(time, 256f, h * 0.35f, paint)
            try { uploadClockTexture(0, bitmap) } finally { bitmap.recycle() }
        }
        for (i in 0..1) {
            GL.glActiveTexture(GL.GL_TEXTURE2 + i)
            GL.glBindTexture(GL.GL_TEXTURE_2D, clockTextures[i])
        }
    }

    private fun uploadClockTexture(index: Int, bitmap: android.graphics.Bitmap) {
        if (clockTextures[index] == 0) {
            val id = IntArray(1)
            GL.glGenTextures(1, id, 0)
            clockTextures[index] = id[0]
        }
        GL.glActiveTexture(GL.GL_TEXTURE2 + index)
        GL.glBindTexture(GL.GL_TEXTURE_2D, clockTextures[index])
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun releaseImages() {
        GL.glDeleteTextures(2, clockTextures, 0)
        GL.glDeleteTextures(2, textures, 0)
        clockTextures.fill(0)
        textures.fill(0)
        loaded = ""
        maskPath = ""
        minute = -1L
        ready = false
        maskReady = false
    }

    fun release() {
        releaseImages()
        if (program != 0) GL.glDeleteProgram(program)
        program = 0
        ready = false
    }
}

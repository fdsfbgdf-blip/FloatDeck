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
class SpatialRenderer(
    private val context: Context,
    private val prefs: android.content.SharedPreferences = SpatialAssets.prefs(context),
) : GLSurfaceView.Renderer {
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
    private var fpsStart = 0L
    private var fpsFrames = 0
    private var fps = 0f
    private var ready = false
    @Volatile private var resetView = false
    fun recenter() { resetView = true }
    @Volatile var forcedView: SpatialPose.Input? = null
    @Volatile var diagnosticBoost = false
    @Volatile var diagnostics = SpatialRenderStatus()
        private set
    private var failedSignature = ""
    private var retryAt = 0L
    private val uniforms = mutableMapOf<String, Int>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        diagnostics = SpatialRenderStatus(
            glVersion = GL.glGetString(GL.GL_VERSION) ?: "GL 版本不可用",
            gpu = GL.glGetString(GL.GL_RENDERER) ?: "GPU 信息不可用",
        )
        program = try { ShaderProgram.compile(Shaders.spatialVertex, Shaders.spatialFragment) } catch (error: Exception) {
            diagnostics = diagnostics.copy(message = "空间着色器编译/链接失败：${error.message}")
            0
        }
        uniforms.clear()
        for (name in listOf("uImage", "uDepth", "uCover", "uMotion", "uCamera", "uDepthRange", "uTexel", "uSurfaceTolerance", "uClock", "uMask", "uClockEnabled")) uniforms[name] = GL.glGetUniformLocation(program, name)
        textures.fill(0)
        clockTextures.fill(0)
        minute = -1L
        maskPath = ""
        loaded = ""
        ready = false
        failedSignature = ""
        retryAt = 0L
        x = 0f; y = 0f; last = 0L; fpsStart = 0L; fpsFrames = 0; fps = 0f
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
        val state = prefs.all
        val paths = listOf(state["image"] as? String ?: "", state["depth"] as? String ?: "")
        val signature = paths.joinToString("|")
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (program == 0) return
        if (signature != loaded && (signature != failedSignature || now >= retryAt)) {
            failedSignature = signature
            retryAt = now + 1_000_000_000L
            diagnostics = diagnostics.copy(name = state["package_name"] as? String ?: "高级工具壁纸", packageId = state["package_id"] as? String ?: "", ready = false, message = "正在加载空间纹理")
            ready = false
            GL.glDeleteTextures(2, textures, 0)
            textures.fill(0)
            if (paths.all { !it.isNullOrEmpty() && File(it!!).isFile }) {
                try {
                    for (attempt in 0 until 8) { if (GL.glGetError() == GL.GL_NO_ERROR) break }
                    GL.glGenTextures(2, textures, 0)
                    paths.forEachIndexed { index, path ->
                        val bitmap = SpatialAssets.decode(File(path!!))
                        try {
                            if (index == 0) {
                                aspect = bitmap.width.toFloat() / bitmap.height
                                diagnostics = diagnostics.copy(imageWidth = bitmap.width, imageHeight = bitmap.height)
                            } else {
                                require(bitmap.width == diagnostics.imageWidth && bitmap.height == diagnostics.imageHeight) { "原图与深度纹理尺寸不一致" }
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val repaired = SpatialDepthEdges.repair(pixels, bitmap.width, bitmap.height)
                                bitmap.setPixels(repaired, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val row = IntArray(bitmap.width)
                                val levels = BooleanArray(256)
                                var min = 255
                                var max = 0
                                for (y in 0 until bitmap.height) {
                                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                                    for (pixel in row) {
                                        val depth = (pixel ushr 16) and 255
                                        min = minOf(min, depth); max = maxOf(max, depth); levels[depth] = true
                                    }
                                }
                                diagnostics = diagnostics.copy(depthWidth = bitmap.width, depthHeight = bitmap.height, depthMin = min, depthMax = max, depthLevels = levels.count { it })
                            }
                            GL.glActiveTexture(GL.GL_TEXTURE0 + index)
                            GL.glBindTexture(GL.GL_TEXTURE_2D, textures[index])
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, if (index == 1) GL.GL_NEAREST else GL.GL_LINEAR)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, if (index == 1) GL.GL_NEAREST else GL.GL_LINEAR)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                            GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
                            val uploadError = GL.glGetError()
                            check(uploadError == GL.GL_NO_ERROR) { "${if (index == 0) "原图" else "深度图"}上传失败 GL=0x${uploadError.toString(16)}" }
                        } finally { bitmap.recycle() }
                    }
                    ready = true
                    loaded = signature
                    diagnostics = diagnostics.copy(ready = true, imageTexture = textures[0], depthTexture = textures[1], message = "空间 GPU 高度场已加载；GL 上传正常")
                } catch (error: Exception) {
                    android.util.Log.e("SpatialRenderer", "Texture load failed", error)
                    diagnostics = diagnostics.copy(ready = false, message = "纹理加载失败，将重试：${error.message}")
                }
            } else {
                diagnostics = diagnostics.copy(ready = false, message = "原图或深度图路径缺失，请重新选择/导入壁纸")
            }
        }
        if (!ready) return
        if (resetView || now - last > 500_000_000L) { x = 0f; y = 0f; resetView = false; fpsStart = 0L; fpsFrames = 0 }
        val dt = if (last == 0L) 0.033f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        val forced = forcedView
        val strength = if (diagnosticBoost) 1f else (state["strength"] as? Float ?: 0.5f)
        val sensitivity = if (diagnosticBoost) 1f else (state["sensitivity"] as? Float ?: 1f)
        val angle = if (diagnosticBoost) 12f else (state["angle"] as? Float ?: 8f)
        val speed = state["response_speed"] as? Float ?: 0.65f
        val mode = if (diagnosticBoost) "enhanced" else (state["parallax_mode"] as? String ?: "standard")
        val alpha = SpatialMath.smoothing(dt, speed)
        if (forced != null) {
            x = forced.x.coerceIn(-1f, 1f); y = forced.y.coerceIn(-1f, 1f)
        } else {
            val targetX = SpatialMath.response(tiltX, sensitivity, angle)
            val targetY = SpatialMath.response(tiltY, sensitivity, angle)
            x += (targetX - x) * alpha; y += (targetY - y) * alpha
        }
        val cover = SpatialMath.cover(aspect, width.toFloat() / height, (state["crop"] as? Float ?: 0.04f))
        val requested = SpatialMath.amplitude(strength, mode)
        val amplitude = SpatialMath.safeAmplitude(requested, cover, width, height, diagnostics.imageWidth, diagnostics.imageHeight)
        val mx = x * amplitude
        val my = y * amplitude * width / height
        val depthSpan = SpatialMath.normalizedDepth(diagnostics.depthMax / 255f, diagnostics.depthMin / 255f, diagnostics.depthMax / 255f) -
            SpatialMath.normalizedDepth(diagnostics.depthMin / 255f, diagnostics.depthMin / 255f, diagnostics.depthMax / 255f)
        if (fpsStart == 0L) fpsStart = now
        fpsFrames++
        if (now - fpsStart >= 500_000_000L) {
            fps = fpsFrames * 1e9f / (now - fpsStart); fpsStart = now; fpsFrames = 0
        }
        GL.glDisable(GL.GL_BLEND)
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glUseProgram(program)
        for (i in 0..1) {
            GL.glActiveTexture(GL.GL_TEXTURE0 + i)
            GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
        }
        updateClock(state)
        GL.glUniform1i(uniforms.getValue("uClockEnabled"), if (showClock && maskReady) 1 else 0)
        GL.glUniform1i(uniforms.getValue("uClock"), 2)
        GL.glUniform1i(uniforms.getValue("uMask"), 3)
        GL.glUniform1i(uniforms.getValue("uImage"), 0)
        GL.glUniform1i(uniforms.getValue("uDepth"), 1)
        GL.glUniform2f(uniforms.getValue("uCover"), cover[0], cover[1])
        GL.glUniform2f(uniforms.getValue("uMotion"), mx * cover[0], my * cover[1])
        GL.glUniform2f(uniforms.getValue("uCamera"), mx * cover[0] * SpatialMath.CAMERA_SHARE, my * cover[1] * SpatialMath.CAMERA_SHARE)
        GL.glUniform2f(uniforms.getValue("uDepthRange"), diagnostics.depthMin / 255f, diagnostics.depthMax / 255f)
        GL.glUniform2f(uniforms.getValue("uTexel"), 1f / diagnostics.depthWidth, 1f / diagnostics.depthHeight)
        GL.glUniform1f(uniforms.getValue("uSurfaceTolerance"), if (mode == "stable") 0.002f else 0.004f)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        val drawError = GL.glGetError()
        diagnostics = diagnostics.copy(
            ready = drawError == GL.GL_NO_ERROR,
            message = if (drawError == GL.GL_NO_ERROR) "空间 GPU 高度场绘制正常；GL=0" else "绘制错误 GL=0x${drawError.toString(16)}",
            frames = diagnostics.frames + 1, lastFrameNanos = now,
            viewX = x, viewY = y, motionX = mx * cover[0], motionY = my * cover[1],
            estimatedNearFarPixelsX = mx * depthSpan * width,
            estimatedNearFarPixelsY = my * depthSpan * height,
            cameraX = mx * cover[0] * SpatialMath.CAMERA_SHARE, cameraY = my * cover[1] * SpatialMath.CAMERA_SHARE,
            fps = fps, mode = mode, speed = speed, targetPercent = requested * 100f,
            limitReason = if (amplitude + 0.00001f < requested) "裁切余量不足，已限幅" else if (depthSpan < 0.99f) "深度变化不足，避免放大深度噪声" else "当前裁切余量可用；断层局部回退仍可能降低位移",
            strength = strength, sensitivity = sensitivity, angle = angle,
            crop = state["crop"] as? Float ?: 0.04f, forced = forced != null, boosted = diagnosticBoost,
        )
        GL.glEnable(GL.GL_BLEND)
    }

    // Preview only. WallpaperService never enables showClock.
    private fun updateClock(state: Map<String, *>) {
        if (!showClock) return
        val path = state["mask"] as? String ?: ""
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
        diagnostics = diagnostics.copy(ready = false, message = "纹理已释放，等待重新加载")
    }

    fun release() {
        releaseImages()
        if (program != 0) GL.glDeleteProgram(program)
        program = 0
        ready = false
    }
}

package app.floatdeck.spatial

import android.content.Context
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import app.floatdeck.gl.ShaderProgram
import app.floatdeck.gl.Shaders
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Used unchanged by wallpaper and live preview. All GL resources belong to the GL thread. */
class SpatialRenderer(
    private val context: Context,
    private val prefs: android.content.SharedPreferences = SpatialAssets.prefs(context),
    private val diagnosticSession: Boolean = false,
) : GLSurfaceView.Renderer {
    private var probe = SpatialGpuProbe()
    private var probePoints = emptyList<SpatialGpuProbe.Point>()
    @Volatile var measureRequested = false
    private var fallbackReadback: ByteBuffer? = null
    private var normalizationMin = 0
    private var normalizationMax = 255
    private var program = 0
    private val programs = mutableMapOf<SpatialAlgorithm, Int>()
    @Volatile var diagnosticAlgorithm = SpatialAlgorithm.V3
    @Volatile var captureComparisonRequested = false
    @Volatile var comparisonMessage = "尚未导出彩色对照与性能记录"
        private set
    @Volatile var comparisonZip: File? = null
        private set
    private var activeAlgorithm = SpatialAlgorithm.V3
    private fun selectProgram(algorithm: SpatialAlgorithm) {
        program = programs.getValue(algorithm)
        uniforms.clear()
        for (name in listOf("uImage", "uDepth", "uCover", "uMotion", "uCamera", "uDepthRange", "uTexel", "uSurfaceTolerance", "uShowFallback", "uMeasureFallback", "uClock", "uMask", "uClockEnabled")) uniforms[name] = GL.glGetUniformLocation(program, name)
    }
    private val textures = IntArray(2)
    private val clockTextures = IntArray(2)
    private var maskPath = ""
    private var minute = -1L
    private var maskReady = false
    @Volatile var showClock = false
    private var loaded = ""
    private var boundaryProfile: SpatialBoundaryProfile? = null
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
    @Volatile var showFallback = false
    /** Diagnostic-page-only switch; the wallpaper renderer leaves this at false. */
    @Volatile var useOriginalDepth = false
    /** Requests one explicit GPU mask readback; never read back every frame. */
    @Volatile var measureFallbackRequested = false
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
        programs.clear() // IDs from a lost context must not be deleted in the new context.
        try {
            programs[SpatialAlgorithm.V3] = ShaderProgram.compile(Shaders.spatialVertex, Shaders.spatialFragment)
            if (diagnosticSession) {
                programs[SpatialAlgorithm.V1] = ShaderProgram.compile(Shaders.spatialVertex, Shaders.spatialV1Fragment)
                programs[SpatialAlgorithm.V2] = ShaderProgram.compile(Shaders.spatialVertex, Shaders.spatialV2Fragment)
            }
            activeAlgorithm = SpatialAlgorithm.V3
            selectProgram(activeAlgorithm)
        } catch (error: Exception) {
            diagnostics = diagnostics.copy(message = "空间着色器编译/链接失败：${error.message}")
            program = 0
        }
        probe = SpatialGpuProbe() // context-owned IDs cannot survive context recreation
        probePoints = emptyList()
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
        // Switching original/repaired depth forces a real texture reload for an A/B comparison.
        val originalDepth = diagnosticSession && useOriginalDepth // snapshot once per frame/load
        val signature = paths.joinToString("|") + "|depthMode=${if (originalDepth) "original" else "repaired"}"
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (program == 0) return
        val algorithm = if (diagnosticSession) diagnosticAlgorithm else SpatialAlgorithm.V3
        if (algorithm != activeAlgorithm) {
            activeAlgorithm = algorithm
            selectProgram(algorithm)
            diagnostics = diagnostics.copy(fallbackPixels = -1, fallbackPercent = -1f,
                fallbackMeasureMessage = "算法已切换，旧测量已作废", renderedOffset = "算法已切换，等待重新测量")
        }
        if (signature != loaded && (signature != failedSignature || now >= retryAt)) {
            failedSignature = signature
            retryAt = now + 1_000_000_000L
            diagnostics = diagnostics.copy(fallbackPixels = -1, fallbackPercent = -1f, fallbackMeasureMessage = "纹理正在重载，旧测量已作废", renderedOffset = "等待重新测量", name = state["package_name"] as? String ?: "高级工具壁纸", packageId = state["package_id"] as? String ?: "", ready = false, message = "正在加载空间纹理")
            ready = false
            boundaryProfile = null
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
                                val originalPixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(originalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val repairedPixels = SpatialDepthEdges.repair(originalPixels, bitmap.width, bitmap.height)
                                val comparison = SpatialDepthComparisonAnalyzer.analyze(originalPixels, repairedPixels)
                                val selectedPixels = if (originalDepth) originalPixels else repairedPixels
                                val selectedStats = if (originalDepth) comparison.original else comparison.repaired
                                bitmap.setPixels(selectedPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val min = selectedStats.min
                                val max = selectedStats.max
                                // A/B changes the texture ONLY. Keep production normalization,
                                // boundary plan and crop identical, even if repair changes extrema.
                                normalizationMin = comparison.repaired.min
                                normalizationMax = comparison.repaired.max
                                boundaryProfile = SpatialBoundaryProfile.create(repairedPixels, bitmap.width, bitmap.height, normalizationMin, normalizationMax)
                                probePoints = SpatialGpuProbe.points(selectedPixels, bitmap.width, bitmap.height, normalizationMin, normalizationMax)
                                diagnostics = diagnostics.copy(renderedOffset = "尚未点击 GPU 实测")
                                diagnostics = diagnostics.copy(
                                    depthWidth = bitmap.width,
                                    depthHeight = bitmap.height,
                                    depthMin = min,
                                    depthMax = max,
                                    depthLevels = selectedStats.levels,
                                    depthRepairEnabled = !originalDepth,
                                    depthRepairChangedPixels = comparison.changedPixels,
                                    depthRepairChangedPercent = comparison.changedPercent,
                                    depthRepairMeanAbsDifference = comparison.meanAbsoluteDifference,
                                    depthRepairMaxDifference = comparison.maxAbsoluteDifference,
                                    originalDepthMin = comparison.original.min,
                                    originalDepthMax = comparison.original.max,
                                    originalDepthLevels = comparison.original.levels,
                                    repairedDepthMin = comparison.repaired.min,
                                    repairedDepthMax = comparison.repaired.max,
                                    repairedDepthLevels = comparison.repaired.levels,
                                    fallbackGlError = -1,
                                    fallbackCapturedNanos = 0L,
                                    fallbackPixels = -1,
                                    fallbackPercent = -1f,
                                    fallbackSampleWidth = 0,
                                    fallbackSampleHeight = 0,
                                    fallbackMeasureMessage = "尚未测量求交失败比例",
                                    fallbackSampleViewX = 0f,
                                    fallbackSampleViewY = 0f,
                                    fallbackSampleMotionX = 0f,
                                    fallbackSampleMotionY = 0f,
                                    fallbackSampleStrength = 0f,
                                    fallbackSampleDepthMode = "未测",
                                )
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
            val length = kotlin.math.hypot(forced.x, forced.y).coerceAtLeast(1f)
            x = forced.x / length; y = forced.y / length
        } else {
            val target = SpatialMath.responseVector(tiltX, tiltY, sensitivity, angle)
            x += (target.x - x) * alpha; y += (target.y - y) * alpha
        }
        val baseCover = SpatialMath.cover(aspect, width.toFloat() / height, (state["crop"] as? Float ?: 0.04f))
        val requested = SpatialMath.amplitude(strength, mode)
        val plan = SpatialBoundaryPlanner.plan(baseCover, boundaryProfile, width, height,
            diagnostics.imageWidth, diagnostics.imageHeight, requested, x, y)
        val cover = plan.cover
        val amplitude = plan.gain
        val mx = plan.appliedX
        val my = plan.appliedY * width / height
        val depthSpan = SpatialMath.normalizedDepth(diagnostics.depthMax / 255f, normalizationMin / 255f, normalizationMax / 255f) -
            SpatialMath.normalizedDepth(diagnostics.depthMin / 255f, normalizationMin / 255f, normalizationMax / 255f)
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
        GL.glUniform2f(uniforms.getValue("uDepthRange"), normalizationMin / 255f, normalizationMax / 255f)
        GL.glUniform2f(uniforms.getValue("uTexel"), 1f / diagnostics.depthWidth, 1f / diagnostics.depthHeight)
        GL.glUniform1f(uniforms.getValue("uSurfaceTolerance"), if (mode == "stable") 0.002f else 0.004f)
        GL.glUniform1i(uniforms.getValue("uShowFallback"), if (diagnosticSession && showFallback) 1 else 0)
        GL.glUniform1i(uniforms.getValue("uMeasureFallback"), 0)
        if (measureFallbackRequested) {
            measureFallbackRequested = false
            var maskGlError = -1
            val dither = GL.glIsEnabled(GL.GL_DITHER)
            if (diagnosticSession) try {
                GL.glDisable(GL.GL_DITHER)
                // Draw a binary hit/fallback mask into the current back buffer, read it once,
                // then immediately redraw the normal preview before the frame is presented.
                GL.glUniform1i(uniforms.getValue("uMeasureFallback"), 1)
                GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
                val byteCount = width.toLong() * height.toLong() * 4L
                check(byteCount in 4L..64L * 1024 * 1024) { "诊断预览尺寸过大，无法读取 GPU 掩码" }
                val pixels = (fallbackReadback?.takeIf { it.capacity() == byteCount.toInt() }
                    ?: ByteBuffer.allocateDirect(byteCount.toInt()).order(ByteOrder.nativeOrder()).also { fallbackReadback = it })
                pixels.clear()
                GL.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
                maskGlError = GL.glGetError()
                check(maskGlError == GL.GL_NO_ERROR) { "GPU 掩码读取失败 GL=0x${maskGlError.toString(16)}" }
                val failed = SpatialFallbackMask.count(pixels, width * height)
                diagnostics = diagnostics.copy(
                    fallbackGlError = maskGlError,
                    fallbackCapturedNanos = now,
                    fallbackSampleForced = forced != null,
                    fallbackPixels = failed,
                    fallbackPercent = failed * 100f / (width * height).coerceAtLeast(1),
                    fallbackSampleWidth = width,
                    fallbackSampleHeight = height,
                    fallbackMeasureMessage = "GPU 实测完成；红色代表本帧进入回退路径（也可能包含画面边缘余量不足）",
                    fallbackSampleViewX = x,
                    fallbackSampleViewY = y,
                    fallbackSampleMotionX = mx * cover[0],
                    fallbackSampleMotionY = my * cover[1],
                    fallbackSampleStrength = strength,
                    fallbackSampleAlgorithm = activeAlgorithm.name,
                    fallbackSampleDepthMode = if (originalDepth) "原始深度" else "APP 修整深度",
                )
            } catch (error: Exception) {
                diagnostics = diagnostics.copy(
                    fallbackGlError = maskGlError,
                    fallbackPixels = -1,
                    fallbackPercent = -1f,
                    fallbackSampleWidth = width,
                    fallbackSampleHeight = height,
                    fallbackMeasureMessage = "GPU 回退比例测量失败：${error.message}",
                )
                android.util.Log.w("SpatialRenderer", "Fallback-mask readback failed", error)
            } catch (error: OutOfMemoryError) {
                fallbackReadback = null
                diagnostics = diagnostics.copy(fallbackPixels = -1, fallbackPercent = -1f,
                    fallbackGlError = maskGlError, fallbackMeasureMessage = "GPU 读回缓冲内存不足，未得出比例")
            } finally {
                GL.glUniform1i(uniforms.getValue("uMeasureFallback"), 0)
                if (dither) GL.glEnable(GL.GL_DITHER)
            }
        }
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        val drawError = GL.glGetError()
        if (captureComparisonRequested) {
            captureComparisonRequested = false
            if (diagnosticSession) {
                comparisonMessage = "正在导出同参数彩色帧、失败掩码与同步/GPU计时"
                runCatching {
                    comparisonZip = SpatialComparisonCapture.capture(context, programs, program, width, height,
                        org.json.JSONObject().put("view_x", x).put("view_y", y).put("strength", strength)
                            .put("mode", mode).put("depth_mode", if(originalDepth) "original" else "repaired")
                            .put("motion_x", mx*cover[0]).put("motion_y", my*cover[1])
                            .put("normalization_min", normalizationMin).put("normalization_max", normalizationMax)
                            .put("gpu", diagnostics.gpu).put("gl", diagnostics.glVersion), paths)
                    comparisonMessage = "对照 ZIP 已生成；各算法使用同一纹理/视角/强度，未知背景棋盘并非补全"
                }.onFailure { comparisonZip = null; comparisonMessage = "对照导出失败：${it.message}" }
            }
        }
        if (measureRequested) {
            measureRequested = false
            diagnostics = diagnostics.copy(renderedOffset = probe.measure(program, probePoints, cover, width, height, mx*cover[0], my*cover[1]) +
                "\n捕获视角 %.3f, %.3f；捕获时间 %d ms（调整视角后需重新点击）".format(java.util.Locale.US,x,y,now/1_000_000))
        }
        diagnostics = diagnostics.copy(
            algorithm = activeAlgorithm.name,
            ready = drawError == GL.GL_NO_ERROR,
            message = if (drawError == GL.GL_NO_ERROR) "空间 GPU 高度场绘制正常；GL=0" else "绘制错误 GL=0x${drawError.toString(16)}",
            frames = diagnostics.frames + 1, lastFrameNanos = now,
            viewX = x, viewY = y, motionX = mx * cover[0], motionY = my * cover[1],
            estimatedNearFarPixelsX = mx * depthSpan * width,
            estimatedNearFarPixelsY = my * depthSpan * height,
            cameraX = mx * cover[0] * SpatialMath.CAMERA_SHARE, cameraY = my * cover[1] * SpatialMath.CAMERA_SHARE,
            fps = fps, mode = mode, speed = speed, targetPercent = requested * 100f, safePercent = amplitude * 100f, showFallback = diagnosticSession && showFallback,
            requestedX = plan.requestedX, requestedY = plan.requestedY,
            appliedX = plan.appliedX, appliedY = plan.appliedY,
            limitX = plan.limitX, limitY = plan.limitY, extraZoom = plan.extraZoom,
            edgeDepths = "左%.3f 右%.3f 上%.3f 下%.3f".format(java.util.Locale.US, plan.edges.left, plan.edges.right, plan.edges.top, plan.edges.bottom),
            directionDegrees = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat(),
            limitReason = plan.reason + if (depthSpan < 0.99f) "；深度变化不足，近远差缩小" else "",
            strength = strength, sensitivity = sensitivity, angle = angle,
            crop = state["crop"] as? Float ?: 0.04f, forced = forced != null, boosted = diagnosticBoost,
            depthRepairEnabled = !originalDepth,
            normalizationMin = normalizationMin, normalizationMax = normalizationMax,
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
        fallbackReadback = null
        probe.release()
        probePoints = emptyList()
        GL.glDeleteTextures(2, clockTextures, 0)
        GL.glDeleteTextures(2, textures, 0)
        clockTextures.fill(0)
        textures.fill(0)
        loaded = ""
        maskPath = ""
        minute = -1L
        ready = false
        boundaryProfile = null
        maskReady = false
        diagnostics = diagnostics.copy(ready = false, message = "纹理已释放，等待重新加载")
    }

    fun release() {
        releaseImages()
        programs.values.forEach { GL.glDeleteProgram(it) }
        programs.clear()
        program = 0
        ready = false
    }
}

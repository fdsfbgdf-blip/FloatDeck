package app.floatdeck.spatial

/** Immutable GL-thread snapshot; dimensions/statistics describe the bitmap actually uploaded. */
data class SpatialRenderStatus(
    val name: String = "未选择壁纸",
    val packageId: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val depthWidth: Int = 0,
    val depthHeight: Int = 0,
    val depthMin: Int = 0,
    val depthMax: Int = 0,
    val depthLevels: Int = 0,
    val imageTexture: Int = 0,
    val depthTexture: Int = 0,
    val glVersion: String = "尚未创建 GL 上下文",
    val gpu: String = "未知",
    val message: String = "等待加载",
    val ready: Boolean = false,
    val frames: Long = 0,
    val lastFrameNanos: Long = 0,
    val viewX: Float = 0f,
    val viewY: Float = 0f,
    val motionX: Float = 0f,
    val motionY: Float = 0f,
    val estimatedNearFarPixelsX: Float = 0f,
    val estimatedNearFarPixelsY: Float = 0f,
    val strength: Float = 0f,
    val sensitivity: Float = 0f,
    val angle: Float = 0f,
    val crop: Float = 0f,
    val forced: Boolean = false,
    val boosted: Boolean = false,
)

/** Multiple wallpaper Engines may coexist (preview and installed); do not merge their state. */
object SpatialWallpaperRuntime {
    data class Session(
        val id: String,
        val preview: Boolean,
        val visible: Boolean,
        val running: Boolean,
        val spatial: Boolean,
        val sensor: String,
        val sensorRegistered: Boolean,
        val sensorEvents: Long,
        val sensorLastNanos: Long,
        val render: SpatialRenderStatus,
        val message: String = "",
    )
    val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
}

package app.floatdeck.spatial

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Versioned independently from the application and from FloatDeck card templates. */
data class SpatialPackage(
    val name: String,
    val image: String,
    val mask: String?,
    val depthDirection: String,
    val strength: Float,
    val sensitivity: Float,
    val angle: Float,
    val crop: Float,
) {
    fun json(): JSONObject = JSONObject()
        .put("format", "floatdeck-spatial")
        .put("version", 1)
        .put("name", name)
        .put("assets", JSONObject().put("image", image).put("depth", "depth.png").apply { mask?.let { put("mask", it) } })
        .put("depthDirection", depthDirection)
        .put("preset", JSONObject().put("strength", strength).put("sensitivity", sensitivity).put("maxAngleDegrees", angle).put("crop", crop))

    companion object {
        private fun keys(json: JSONObject, allowed: Set<String>) {
            require(json.keys().asSequence().all { it in allowed }) { "manifest 包含未定义字段" }
        }

        fun parse(text: String): SpatialPackage {
            require(text.toByteArray(Charsets.UTF_8).size <= SpatialPackageArchive.MAX_MANIFEST) { "manifest 超限" }
            var nesting = 0
            var quoted = false
            var escaped = false
            for (character in text) {
                if (quoted) {
                    if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
                } else when (character) {
                    '"' -> quoted = true
                    '{', '[' -> { nesting++; require(nesting <= 8) { "manifest 嵌套过深" } }
                    '}', ']' -> nesting--
                }
            }
            val json = JSONObject(text)
            keys(json, setOf("format", "version", "name", "assets", "depthDirection", "preset"))
            require(json.get("format") == "floatdeck-spatial") { "这不是空间壁纸包；不支持原版卡片模板" }
            require(json.get("version") is Number && json.getDouble("version") == 1.0) { "不支持的空间包版本" }
            val name = json.get("name") as? String ?: error("壁纸名称必须是文字")
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "壁纸名称无效" }
            val assets = json.getJSONObject("assets")
            keys(assets, setOf("image", "depth", "mask"))
            val image = assets.get("image") as? String ?: error("原图引用无效")
            require(image in setOf("wallpaper.jpg", "wallpaper.png", "wallpaper.webp")) { "原图文件名必须是 wallpaper.jpg/png/webp" }
            require(assets.get("depth") == "depth.png") { "深度图必须是 depth.png" }
            val mask = if (assets.has("mask")) assets.get("mask") as? String ?: error("遮罩引用无效") else null
            require(mask == null || mask == "mask.png") { "遮罩必须是 mask.png" }
            val direction = json.get("depthDirection") as? String ?: error("深度方向无效")
            require(direction in setOf("white-near", "black-near")) { "深度方向无效" }
            val preset = json.getJSONObject("preset")
            keys(preset, setOf("strength", "sensitivity", "maxAngleDegrees", "crop"))
            fun number(key: String, min: Float, max: Float): Float {
                val raw = preset.get(key)
                require(raw is Number) { "$key 必须是数值" }
                val value = raw.toFloat()
                require(value.isFinite() && value in min..max) { "$key 超出允许范围" }
                return value
            }
            return SpatialPackage(name, image, mask, direction, number("strength", 0f, 1f), number("sensitivity", 0.2f, 3f), number("maxAngleDegrees", 1f, 15f), number("crop", 0f, 0.3f))
        }
    }
}

/** Limits apply both to ZIP metadata and bytes actually read. Never restores ZIP permissions. */
object SpatialPackageArchive {
    const val MAX_ARCHIVE = 64L * 1024 * 1024
    const val MAX_FILE = 32L * 1024 * 1024
    const val MAX_TOTAL = 96L * 1024 * 1024
    const val MAX_MANIFEST = 16L * 1024
    private val allowed = setOf("manifest.json", "wallpaper.jpg", "wallpaper.png", "wallpaper.webp", "depth.png", "mask.png")

    fun copyBounded(input: InputStream, destination: File, limit: Long) {
        destination.outputStream().use { output ->
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= limit) { "文件超过允许大小" }
                output.write(buffer, 0, count)
            }
        }
    }

    // Bound central-directory parsing before ZipFile can allocate entries. Version 1
    // intentionally excludes ZIP64, archive comments, split archives and appended data.
    private fun preflight(archive: File) {
        RandomAccessFile(archive, "r").use { input ->
            require(input.length() >= 22) { "ZIP 已截断" }
            fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
            require(u32() == 0x04034b50L) { "ZIP 文件头无效" }
            input.seek(input.length() - 22)
            require(u32() == 0x06054b50L) { "不支持 ZIP64、ZIP 注释或尾随数据" }
            require(u16() == 0 && u16() == 0) { "不支持分卷 ZIP" }
            val diskCount = u16()
            val totalCount = u16()
            require(diskCount == totalCount && totalCount in 3..4) { "ZIP 文件数量无效" }
            val size = u32()
            val offset = u32()
            require(size in 1..4096 && offset + size == input.length() - 22 && offset >= 4) { "ZIP 中央目录超限或损坏" }
            require(u16() == 0) { "不支持 ZIP 注释" }
        }
    }

    /** Returns a semantic content hash, so repacking identical content is a duplicate. */
    fun unpack(archive: File, target: File): Pair<SpatialPackage, String> {
        require(archive.length() in 1..MAX_ARCHIVE) { "ZIP 为空或超过 64 MiB" }
        require(target.mkdirs() || (target.isDirectory && target.list().isNullOrEmpty())) { "临时目录无效" }
        preflight(archive)
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 3..4) { "空间 ZIP 必须仅包含 3 或 4 个根目录文件" }
            require(entries.map { it.name }.toSet().size == entries.size) { "ZIP 包含重复文件名" }
            var declared = 0L
            for (entry in entries) {
                require(!entry.isDirectory && entry.name in allowed) { "ZIP 含非法路径或文件类型：${entry.name.take(100)}" }
                require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) { "不支持的压缩方式" }
                val limit = if (entry.name == "manifest.json") MAX_MANIFEST else MAX_FILE
                require(entry.size in 1..limit && entry.compressedSize > 0) { "ZIP 文件大小无效" }
                require(entry.size <= entry.compressedSize * 200L) { "ZIP 压缩比异常" }
                declared += entry.size
                require(declared <= MAX_TOTAL) { "ZIP 解压总大小超限" }
            }
            var expanded = 0L
            for (entry in entries) {
                val destination = File(target, entry.name)
                require(destination.canonicalFile.parentFile == target.canonicalFile) { "ZIP 路径越界" }
                val crc = CRC32()
                var bytes = 0L
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            bytes += count
                            expanded += count
                            val limit = if (entry.name == "manifest.json") MAX_MANIFEST else MAX_FILE
                            require(bytes <= limit && expanded <= MAX_TOTAL) { "ZIP 实际解压数据超限" }
                            crc.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(bytes == entry.size && crc.value == entry.crc) { "ZIP 文件损坏或 CRC 不匹配" }
            }
            val manifestBytes = File(target, "manifest.json").readBytes()
            val manifestText = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(manifestBytes)).toString()
            val manifest = SpatialPackage.parse(manifestText)
            val expected = setOfNotNull("manifest.json", manifest.image, "depth.png", manifest.mask)
            require(entries.map { it.name }.toSet() == expected) { "ZIP 内容与 manifest 引用不一致" }
            val digest = MessageDigest.getInstance("SHA-256")
            // Fixed order is independent of JSONObject's implementation-dependent key order.
            val fields = listOf(manifest.name, manifest.image, manifest.mask ?: "", manifest.depthDirection, manifest.strength.toString(), manifest.sensitivity.toString(), manifest.angle.toString(), manifest.crop.toString())
            fields.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
            for (name in expected.minus("manifest.json").sorted()) {
                digest.update(name.toByteArray(Charsets.US_ASCII))
                val hash = MessageDigest.getInstance("SHA-256")
                File(target, name).inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                }
                digest.update(hash.digest())
            }
            return manifest to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

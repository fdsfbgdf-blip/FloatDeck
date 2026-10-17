package app.floatdeck.spatial

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.util.UUID

/** Android decoding adapter. No package code or external URI is retained or executed. */
object SpatialPackageStore {
    fun library(context: Context) = SpatialLibrary(File(context.filesDir, "spatial-library")) { directory, manifest ->
        prepare(directory, manifest)
    }

    fun previewPrefs(context: Context): SharedPreferences = context.getSharedPreferences("spatial-player-preview", Context.MODE_PRIVATE)

    fun import(context: Context, uri: Uri): SpatialLibrary.Imported {
        val archive = File(context.cacheDir, "spatial-${UUID.randomUUID()}.zip")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取 ZIP" }
                SpatialPackageArchive.copyBounded(input, archive, SpatialPackageArchive.MAX_ARCHIVE)
            }
            return library(context).importArchive(archive)
        } finally { archive.delete() }
    }

    /** One SharedPreferences commit exposes paths and parameters as a complete snapshot. */
    fun publish(entry: SpatialLibrary.Entry, prefs: SharedPreferences, enableWallpaper: Boolean) {
        val manifest = entry.manifest
        val edit = prefs.edit()
            .putString("package_id", entry.id)
            .putString("package_name", manifest.name)
            .putString("image", File(entry.directory, "image.png").path)
            .putString("depth", File(entry.directory, "depth.png").path)
            .putString("original", File(entry.directory, "source/${manifest.image}").path)
            .putFloat("strength", entry.strength)
            .putFloat("sensitivity", manifest.sensitivity)
            .putFloat("angle", manifest.angle)
            .putFloat("crop", manifest.crop)
            .putBoolean("enabled", enableWallpaper)
        if (manifest.mask == null) edit.remove("mask") else edit.putString("mask", File(entry.directory, "mask.png").path)
        check(edit.commit()) { "保存壁纸设置失败" }
    }

    private data class Size(val width: Int, val height: Int)

    private fun inspect(file: File): Size {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val expected = when (file.extension) { "jpg" -> "image/jpeg"; "webp" -> "image/webp"; "png" -> "image/png"; else -> "" }
        require(options.outMimeType == expected && expected.isNotEmpty()) { "素材实际类型与扩展名不符：${file.name}" }
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192 && options.outWidth.toLong() * options.outHeight <= 40_000_000L) { "素材尺寸超限（最长边 8192，最多 4000 万像素）" }
        return Size(options.outWidth, options.outHeight)
    }

    private fun decode(file: File, size: Size): Bitmap {
        val scale = minOf(1.0, 2048.0 / maxOf(size.width, size.height))
        val width = maxOf(1, (size.width * scale).toInt())
        val height = maxOf(1, (size.height * scale).toInt())
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        }
        while (maxOf(size.width, size.height) / options.inSampleSize > 2048) options.inSampleSize *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "素材解码失败" }
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun prepare(directory: File, manifest: SpatialPackage) {
        val source = File(directory, "source")
        val names = listOfNotNull(manifest.image, "depth.png", manifest.mask)
        val sizes = names.associateWith { inspect(File(source, it)) }
        val expected = sizes.getValue(manifest.image)
        if (manifest.image.endsWith(".jpg")) {
            val exif = android.media.ExifInterface(File(source, manifest.image).path)
            val orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            require(orientation == android.media.ExifInterface.ORIENTATION_NORMAL || orientation == android.media.ExifInterface.ORIENTATION_UNDEFINED) { "请先将 JPEG 方向应用到像素，空间包不接受带旋转/镜像方向的 JPEG" }
        }
        require(sizes.values.all { it == expected }) { "原图、深度图和遮罩必须具有完全相同的像素尺寸" }
        for (name in names) {
            val bitmap = decode(File(source, name), expected)
            try {
                if (name != manifest.image) {
                    val row = IntArray(bitmap.width)
                    for (y in 0 until bitmap.height) {
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        for (x in row.indices) {
                            val pixel = row[x]
                            require(Color.alpha(pixel) == 255 && kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 1 && kotlin.math.abs(Color.red(pixel) - Color.blue(pixel)) <= 1) { "深度图和遮罩必须为不透明灰度 PNG" }
                            if (name == "depth.png" && manifest.depthDirection == "black-near") {
                                val value = 255 - Color.red(pixel)
                                row[x] = Color.rgb(value, value, value)
                            }
                        }
                        if (name == "depth.png" && manifest.depthDirection == "black-near") bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    }
                }
                val destination = File(directory, if (name == manifest.image) "image.png" else name)
                destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "素材保存失败" } }
            } finally { bitmap.recycle() }
        }
    }
}

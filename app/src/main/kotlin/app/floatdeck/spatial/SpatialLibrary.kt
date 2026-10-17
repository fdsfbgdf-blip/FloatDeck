package app.floatdeck.spatial

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Durable library; originals and prepared textures become visible together via directory rename. */
class SpatialLibrary(private val root: File, private val prepare: (File, SpatialPackage) -> Unit) {
    data class Entry(val id: String, val directory: File, val manifest: SpatialPackage, val strength: Float)
    data class Imported(val entry: Entry, val duplicate: Boolean)

    companion object { private val lock = Any() }

    init { check(root.mkdirs() || root.isDirectory) }

    fun entries(): List<Entry> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }
            .mapNotNull { runCatching { read(it.name) }.getOrNull() }.sortedBy { it.manifest.name }
    }

    fun read(id: String): Entry {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "壁纸 ID 无效" }
        val directory = File(root, id)
        val manifest = SpatialPackage.parse(File(directory, "record.json").readText())
        require(File(directory, "image.png").isFile && File(directory, "depth.png").isFile) { "壁纸数据不完整" }
        require(manifest.mask == null || File(directory, "mask.png").isFile) { "遮罩数据不完整" }
        val strength = File(directory, "strength.txt").takeIf { it.isFile }?.readText()?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0f..1f } ?: manifest.strength
        return Entry(id, directory, manifest, strength)
    }

    fun selected(): Entry? = synchronized(lock) {
        runCatching { read(File(root, "selected.txt").readText()) }.getOrNull()
    }

    fun select(id: String): Entry = synchronized(lock) {
        val entry = read(id)
        atomicText(File(root, "selected.txt"), id)
        entry
    }

    fun setStrength(id: String, strength: Float) = synchronized(lock) {
        require(strength.isFinite() && strength in 0f..1f)
        val entry = read(id)
        atomicText(File(entry.directory, "strength.txt"), strength.toString())
    }

    fun importArchive(archive: File): Imported = synchronized(lock) {
        val staging = File(root, ".import-${UUID.randomUUID()}")
        check(staging.mkdir())
        try {
            val (manifest, id) = SpatialPackageArchive.unpack(archive, File(staging, "source"))
            val destination = File(root, id)
            if (destination.exists()) return@synchronized Imported(read(id), true)
            require(entries().size < 100) { "壁纸库最多保存 100 套，请先删除不需要的壁纸" }
            require(root.usableSpace > 192L * 1024 * 1024) { "存储空间不足，请至少保留 192 MiB 可用空间" }
            prepare(staging, manifest)
            require(listOfNotNull("image.png", "depth.png", manifest.mask).all { File(staging, it).isFile && File(staging, it).length() > 0 }) { "壁纸工作素材不完整" }
            File(staging, "record.json").writeText(manifest.json().toString(), Charsets.UTF_8)
            check(staging.renameTo(destination)) { "保存壁纸失败" }
            Imported(read(id), false)
        } finally { staging.deleteRecursively() }
    }

    /** Active selection cannot be deleted; renderers may still have its textures loaded. */
    fun delete(id: String) = synchronized(lock) {
        require(selected()?.id != id) { "请先切换到其他壁纸" }
        check(read(id).directory.deleteRecursively()) { "删除失败" }
    }

    private fun atomicText(file: File, text: String) {
        val temporary = File(file.parentFile, ".state-${UUID.randomUUID()}")
        try {
            temporary.writeText(text, Charsets.UTF_8)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

package app.floatdeck.spatial

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpatialPackageTest {
    @TempDir lateinit var temp: File
    private fun manifest() = SpatialPackage("Test", "wallpaper.png", null, "white-near", 0.45f, 1f, 8f, 0.04f)
    private fun content(json: JSONObject = manifest().json()) = linkedMapOf("manifest.json" to json.toString().toByteArray(), "wallpaper.png" to byteArrayOf(1, 2, 3), "depth.png" to byteArrayOf(4, 5, 6))
    private fun zip(files: Map<String, ByteArray>, name: String = "test.zip"): File {
        val file = File(temp, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return file
    }
    private fun unpack(file: File) = SpatialPackageArchive.unpack(file, File(temp, "unpacked-${java.util.UUID.randomUUID()}"))
    private fun library(prepare: (File, SpatialPackage) -> Unit = { directory, _ ->
        File(directory, "image.png").writeBytes(byteArrayOf(1))
        File(directory, "depth.png").writeBytes(byteArrayOf(2))
    }) = SpatialLibrary(File(temp, "library"), prepare)

    @Test fun `valid package preserves complete preset and independent format`() {
        val (parsed, hash) = unpack(zip(content()))
        assertEquals(manifest(), parsed)
        assertTrue(hash.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun `optional mask and inverse direction are supported`() {
        val json = manifest().json().put("depthDirection", "black-near")
        json.getJSONObject("assets").put("mask", "mask.png")
        val files = content(json).apply { put("mask.png", byteArrayOf(7)) }
        val parsed = unpack(zip(files)).first
        assertEquals("black-near", parsed.depthDirection)
        assertEquals("mask.png", parsed.mask)
    }
    @ParameterizedTest @ValueSource(strings = ["../depth.png", "/depth.png", "folder/depth.png", "..\\depth.png", "C:\\depth.png", "payload.apk", "Depth.png"])
    fun `rejects traversal nested paths and arbitrary files`(name: String) {
        val files = content().apply { remove("depth.png"); put(name, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { unpack(zip(files)) }
        assertFalse(File(temp, "depth.png").exists())
    }
    @Test fun `rejects duplicate central and local names`() {
        val file = zip(content().apply { put("depth.pNg", byteArrayOf(1)) })
        val bytes = file.readBytes()
        val pattern = "depth.pNg".toByteArray()
        for (i in 0..bytes.size - pattern.size) if (pattern.indices.all { bytes[i + it] == pattern[it] }) "depth.png".toByteArray().copyInto(bytes, i)
        file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { unpack(file) }
    }
    @Test fun `rejects zip bomb by ratio before extraction`() {
        assertThrows(IllegalArgumentException::class.java) { unpack(zip(content().apply { put("depth.png", ByteArray(2 * 1024 * 1024)) })) }
    }
    @Test fun `rejects too many entries before ZipFile allocation`() {
        assertThrows(IllegalArgumentException::class.java) { unpack(zip(content().apply { put("mask.png", byteArrayOf(1)); put("extra.png", byteArrayOf(1)) })) }
    }
    @Test fun `rejects truncated zip`() {
        val file = zip(content())
        file.writeBytes(file.readBytes().dropLast(8).toByteArray())
        assertThrows(Exception::class.java) { unpack(file) }
    }
    @Test fun `rejects appended data`() {
        val file = zip(content()); file.appendText("trailing")
        assertThrows(IllegalArgumentException::class.java) { unpack(file) }
    }
    @Test fun `rejects CRC corruption even when decompression succeeds`() {
        val file = File(temp, "crc.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            content().forEach { (name, bytes) ->
                val crc = java.util.zip.CRC32().apply { update(bytes) }
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; this.crc = crc.value })
                zip.write(bytes); zip.closeEntry()
            }
        }
        val bytes = file.readBytes()
        val needle = byteArrayOf(4, 5, 6)
        val index = (0..bytes.size - 3).first { bytes.copyOfRange(it, it + 3).contentEquals(needle) }
        bytes[index] = 8; file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { unpack(file) }
    }
    @Test fun `rejects missing or unreferenced assets`() {
        assertThrows(IllegalArgumentException::class.java) { unpack(zip(content().apply { put("mask.png", byteArrayOf(1)) })) }
    }
    @ParameterizedTest @ValueSource(strings = ["format", "version", "assets", "preset", "depthDirection", "name"])
    fun `rejects missing mandatory manifest fields`(field: String) {
        val json = manifest().json().apply { remove(field) }
        assertThrows(Exception::class.java) { SpatialPackage.parse(json.toString()) }
    }
    @Test fun `rejects card templates unsupported versions and unknown fields`() {
        for (json in listOf(manifest().json().put("format", "floatdeck"), manifest().json().put("version", 2), manifest().json().put("cards", "payload"))) {
            assertThrows(IllegalArgumentException::class.java) { SpatialPackage.parse(json.toString()) }
        }
    }
    @ParameterizedTest @ValueSource(strings = ["strength", "sensitivity", "maxAngleDegrees", "crop"])
    fun `rejects out of bounds and string parameters`(key: String) {
        for (value in listOf<Any>(100, -1, "0.5")) {
            val json = manifest().json(); json.getJSONObject("preset").put(key, value)
            assertThrows(IllegalArgumentException::class.java) { SpatialPackage.parse(json.toString()) }
        }
    }
    @Test fun `rejects excessive nesting before JSON recursion`() {
        assertThrows(IllegalArgumentException::class.java) { SpatialPackage.parse("[".repeat(1000) + "]".repeat(1000)) }
    }
    @Test fun `input byte limits do not rely on stream available`() {
        assertThrows(IllegalArgumentException::class.java) { SpatialPackageArchive.copyBounded(ByteArrayInputStream(ByteArray(100)), File(temp, "copy"), 50) }
    }
    @Test fun `semantic duplicate import reuses entry and preserves user strength`() {
        val library = library()
        val original = library.importArchive(zip(content(), "one.zip"))
        library.setStrength(original.entry.id, 0.8f)
        val duplicate = library.importArchive(zip(content(manifest().json()), "two.zip"))
        assertTrue(duplicate.duplicate)
        assertEquals(original.entry.id, duplicate.entry.id)
        assertEquals(0.8f, duplicate.entry.strength)
        assertEquals(1, library.entries().size)
    }
    @Test fun `switch selection and strength survive fresh library instance`() {
        val library = library()
        val one = library.importArchive(zip(content(), "one.zip")).entry
        val two = library.importArchive(zip(content(manifest().json().put("name", "Second")), "two.zip")).entry
        library.select(one.id); library.setStrength(one.id, 0.2f); library.select(two.id)
        val reopened = library()
        assertEquals(two.id, reopened.selected()!!.id)
        assertEquals(2, reopened.entries().size)
        assertEquals(0.2f, reopened.select(one.id).strength)
    }
    @Test fun `failed image preparation leaves prior selection and library unchanged`() {
        val good = library().importArchive(zip(content(), "good.zip")).entry
        library().select(good.id)
        val failing = library { _, _ -> error("Invalid image") }
        assertThrows(IllegalStateException::class.java) { failing.importArchive(zip(content(manifest().json().put("name", "Bad")), "bad.zip")) }
        assertEquals(good.id, library().selected()!!.id)
        assertEquals(1, library().entries().size)
        assertTrue(File(temp, "library").listFiles()!!.none { it.name.startsWith(".import-") })
    }
    @Test fun `cannot delete active selection or traverse ids`() {
        val library = library(); val entry = library.importArchive(zip(content())).entry
        library.select(entry.id)
        assertThrows(IllegalArgumentException::class.java) { library.delete(entry.id) }
        assertThrows(IllegalArgumentException::class.java) { library.read("../outside") }
    }
    @Test fun `real generated sample packages pass archive validation`() {
        for (name in listOf("sunrise", "dusk")) {
            val archive = File("../samples/spatial/$name.spatial.zip")
            assertTrue(archive.isFile)
            val (manifest, _) = unpack(archive)
            assertEquals(if (name == "sunrise") "white-near" else "black-near", manifest.depthDirection)
        }
    }
}

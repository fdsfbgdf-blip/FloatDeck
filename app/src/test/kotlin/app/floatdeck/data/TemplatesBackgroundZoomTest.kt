package app.floatdeck.data

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for the background.zoom field stored directly in template.json
 * ([Templates.parseBackgroundZoom] / [Templates.withBackgroundZoom]).
 */
class TemplatesBackgroundZoomTest {
    @TempDir
    lateinit var tempDir: File

    private fun templateJson(background: String? = null): JSONObject {
        val json =
            JSONObject(
                """
                {"id": "t", "name": "T", "wallpaper": "w.webp",
                 "portraits": {"left": [{"file": "a.webp", "label": "A"}], "right": []}}
                """.trimIndent(),
            )
        if (background != null) json.put("background", JSONObject(background))
        return json
    }

    @Test
    fun `missing background falls back to default zoom of 1`() {
        assertEquals(1f, Templates.parseBackgroundZoom(templateJson()), 1e-6f)
    }

    @Test
    fun `zoom is read from the background object`() {
        assertEquals(1.6f, Templates.parseBackgroundZoom(templateJson("""{"zoom": 1.6}""")), 1e-6f)
    }

    @Test
    fun `withBackgroundZoom sets zoom and preserves everything else`() {
        val json = templateJson("""{"zoom": 1.2}""")
        val updated = Templates.withBackgroundZoom(json, 1.5f)

        assertEquals(1.5f, Templates.parseBackgroundZoom(updated), 1e-6f)
        assertEquals("t", updated.getString("id"))
        assertEquals("w.webp", updated.getString("wallpaper"))
        assertEquals(
            "a.webp",
            updated.getJSONObject("portraits").getJSONArray("left").getJSONObject(0).getString("file"),
        )
    }

    @Test
    fun `round trip through the full load keeps the zoom`() {
        val dir = File(tempDir, "t")
        dir.mkdirs()
        File(dir, "template.json").writeText(Templates.withBackgroundZoom(templateJson(), 1.8f).toString())

        val def = Templates.loadTemplateFromDir(dir)
        assertNotNull(def)
        assertEquals(1.8f, def!!.backgroundZoom, 1e-6f)
        assertTrue(def.left.isNotEmpty())
    }
}

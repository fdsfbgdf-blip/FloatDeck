package app.floatdeck.settings

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.floatdeck.data.RemoteTemplateLoader
import app.floatdeck.data.Templates
import coil3.compose.AsyncImage
import java.io.File

/**
 * Template list thumbnail: loads the template wallpaper as a preview.
 *
 * Coil provides the memory cache and view-size downsampling; model resolution
 * is a separate function so a settings-page redesign can reuse it.
 */
@Composable
fun TemplateThumbnail(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    if (model == null) {
        Surface(
            modifier = modifier.clip(shape),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {}
    } else {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    }
}

/**
 * Resolves the Coil model for a template wallpaper: a file URI for imported
 * templates, an android_asset URI for built-in ones; null when the template
 * or its wallpaper is missing.
 */
fun templateWallpaperModel(
    context: Context,
    templateId: String,
): Any? {
    val remoteDir = File(context.filesDir, "${RemoteTemplateLoader.TEMPLATE_DIR}/$templateId")
    if (remoteDir.isDirectory) {
        val def = Templates.loadTemplateFromDir(remoteDir) ?: return null
        return File(remoteDir, def.wallpaper).takeIf { it.exists() }?.toUri()
    }
    val def = Templates.loadTemplate(context, templateId) ?: return null
    return "file:///android_asset/templates/$templateId/${def.wallpaper}".toUri()
}

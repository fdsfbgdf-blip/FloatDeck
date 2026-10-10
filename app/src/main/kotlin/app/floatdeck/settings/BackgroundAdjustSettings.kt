@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.floatdeck.R
import app.floatdeck.data.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Zoom slider bounds; MAX_ZOOM mirrors FloatDeckRenderer.MAX_BACKGROUND_ZOOM
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 2f

/**
 * Background zoom adjustment for the selected template.
 *
 * Self-contained component: reads the current zoom from the template's own
 * template.json and writes it back when the slider drag ends (the write bumps
 * a revision counter that hot-reloads the running wallpaper). Kept in its own
 * file so a settings-page redesign can move it freely.
 */
@Composable
fun BackgroundAdjustSettings(
    templateId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(templateId) {
        zoom =
            withContext(Dispatchers.IO) {
                Templates.backgroundZoom(context, templateId).coerceIn(MIN_ZOOM, MAX_ZOOM)
            }
    }

    if (templateId.isBlank()) {
        Text(
            stringResource(R.string.background_adjust_no_template),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    fun persist(value: Float) {
        zoom = value
        scope.launch(Dispatchers.IO) {
            Templates.saveBackgroundZoom(context, templateId, value)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.background_adjust_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.background_adjust_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = zoom,
                onValueChange = { zoom = it },
                onValueChangeFinished = { persist(zoom) },
                valueRange = MIN_ZOOM..MAX_ZOOM,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(onClick = { persist(1f) }) {
            Text(stringResource(R.string.background_reset))
        }
    }
}

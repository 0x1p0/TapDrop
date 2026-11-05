package app.tapdrop.ui.design

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Td
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFramePercent

/**
 * A thumbnail for a photo, video (a frame from a little way in) or other content URI, loaded and cached by Coil
 * with a crossfade. Shows [fallback] on a well while loading, when [uri] is null, or when it can't be read.
 * With a [mime] that isn't a photo or video (a PDF, a zip…) nothing is loaded and the well shows [fallback]
 * straight away, tinted with [accent] when given (see `fileKind`). [overlay] draws on top (a [PlayBadge]),
 * told whether the picture has loaded. Clip it with the shape you want (`Modifier.clip(RoundedCornerShape(14.dp))`).
 */
@Composable
fun MediaThumb(
    uri: Uri?,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
    contentDescription: String? = null,
    mime: String? = null,
    accent: Color? = null,
    iconSize: Dp = 24.dp,
    overlay: @Composable BoxScope.(loaded: Boolean) -> Unit = {},
) {
    val c = Td.colors
    val ctx = LocalContext.current
    var loaded by remember(uri) { mutableStateOf(false) }
    val visual = mime == null || mime.startsWith("image/") || mime.startsWith("video/")
    val well = accent?.copy(alpha = if (c.isDark) 0.22f else 0.13f) ?: c.surfaceSunken
    val tint = accent?.let { if (c.isDark) lighten(it, 0.18f) else darken(it, 0.12f) } ?: c.textTertiary
    Box(modifier.background(well), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Icon(fallback, null, tint = tint, modifier = Modifier.size(iconSize))
        }
        if (uri != null && visual) {
            val request = remember(uri) {
                ImageRequest.Builder(ctx)
                    .data(uri)
                    // Videos open on a black frame more often than not: take one a tenth of the way in.
                    .videoFramePercent(0.1)
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success },
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay(loaded)
    }
}

/** The small play disc on a video's thumbnail. */
@Composable
fun PlayBadge(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

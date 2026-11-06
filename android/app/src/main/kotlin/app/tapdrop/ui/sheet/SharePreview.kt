package app.tapdrop.ui.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.MediaThumb
import app.tapdrop.ui.design.darken
import app.tapdrop.ui.design.lighten
import app.tapdrop.ui.design.segmentShape
import app.tapdrop.ui.fmtBytes
import kotlinx.coroutines.delay

/**
 * A rich preview of what's being shared: photos and videos as a grid, a link as a card with its site, text as
 * a snippet, other files as rows with their kind and size. The content is the hero of the share sheet.
 */
@Composable
fun SharePreview(content: ShareContent, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val link = content.link
        when {
            link != null -> LinkCard(link, content.subject)
            content.text != null -> TextCard(content.text)
            else -> {
                val media = content.media
                if (media.isNotEmpty()) MediaGrid(media, if (content.others.isEmpty()) content.totalBytes else null)
                if (content.others.isNotEmpty()) FileRows(content.others)
            }
        }
    }
}

/** While the shared files are being read: a soft shimmering block in the preview's place. */
@Composable
fun PreviewPlaceholder(modifier: Modifier = Modifier, height: Dp = 180.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .platter(RoundedCornerShape(24.dp))
            .shimmer()
            .semantics { contentDescription = "Loading preview" },
    )
}

// MARK: - photos and videos

@Composable
fun MediaGrid(items: List<SharedFile>, totalBytes: Long?, modifier: Modifier = Modifier) {
    val outer = 24.dp
    val inner = 8.dp
    val gap = 4.dp
    val shown = items.take(4)
    val more = items.size - shown.size
    Box(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = when {
                    items.size == 1 -> items[0].name
                    items.all { it.isImage } -> "${items.size} photos"
                    else -> "${items.size} photos and videos"
                }
            },
    ) {
        when (shown.size) {
            1 -> MediaTile(shown[0], 0, RoundedCornerShape(outer), Modifier.fillMaxWidth().height(220.dp))
            2 -> Row(Modifier.fillMaxWidth().height(184.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                MediaTile(shown[0], 0, RoundedCornerShape(outer, inner, inner, outer), Modifier.weight(1f).fillMaxHeight())
                MediaTile(shown[1], 1, RoundedCornerShape(inner, outer, outer, inner), Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.fillMaxWidth().height(228.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                MediaTile(shown[0], 0, RoundedCornerShape(outer, inner, inner, outer), Modifier.weight(1.45f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    MediaTile(shown[1], 1, RoundedCornerShape(inner, outer, inner, inner), Modifier.weight(1f).fillMaxWidth())
                    MediaTile(shown[2], 2, RoundedCornerShape(inner, inner, outer, inner), Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.fillMaxWidth().height(126.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MediaTile(shown[0], 0, RoundedCornerShape(outer, inner, inner, inner), Modifier.weight(1f).fillMaxHeight())
                    MediaTile(shown[1], 1, RoundedCornerShape(inner, outer, inner, inner), Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().height(126.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MediaTile(shown[2], 2, RoundedCornerShape(inner, inner, inner, outer), Modifier.weight(1f).fillMaxHeight())
                    MediaTile(shown[3], 3, RoundedCornerShape(inner, inner, outer, inner), Modifier.weight(1f).fillMaxHeight(), more)
                }
            }
        }
        if (totalBytes != null) {
            Text(
                fmtBytes(totalBytes),
                style = TdType.caption.copy(fontFeatureSettings = "tnum"),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MediaTile(f: SharedFile, index: Int, shape: Shape, modifier: Modifier, more: Int = 0) {
    val reduced = Td.reducedMotion
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            delay(40L + 55L * index)
            a.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 260f))
        }
    }
    Box(
        modifier
            .graphicsLayer {
                alpha = a.value.coerceIn(0f, 1f)
                val s = 0.9f + 0.1f * a.value
                scaleX = s
                scaleY = s
            }
            .clip(shape),
    ) {
        MediaThumb(f.uri, Modifier.fillMaxSize(), fallback = fileKind(f.name, f.mime).icon)
        if (f.isVideo) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)), contentAlignment = Alignment.Center) {
                Text("+$more", style = TdType.title, color = Color.White)
            }
        }
    }
}

// MARK: - links and text

/**
 * A link as a card: the page title when the app sent one, the site, and a padlock for https.
 * Without [platter] it sits bare, for use inside another card.
 */
@Composable
fun LinkCard(url: String, title: String?, modifier: Modifier = Modifier, platter: Boolean = true) {
    val c = Td.colors
    val domain = linkDomain(url)
    val heading = title?.trim()?.takeIf { it.isNotEmpty() && it != url }
    val secure = url.startsWith("https://", ignoreCase = true)
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (platter) Modifier
                    .heightIn(min = 84.dp)
                    .platter(RoundedCornerShape(24.dp))
                    .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                else Modifier
            )
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(domain)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(heading ?: domain, style = TdType.subtitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (secure) {
                    Icon(Icons.Rounded.Lock, "Secure", tint = c.textTertiary, modifier = Modifier.padding(end = 4.dp).size(13.dp))
                }
                Text(
                    if (heading != null) domain else linkPath(url).ifEmpty { url },
                    style = TdType.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The site's initial on a tonal tile: a favicon-like mark without fetching anything. */
@Composable
private fun Monogram(domain: String) {
    val c = Td.colors
    val hues = listOf(Color(0xFF3A3A40), Color(0xFF2E2E33), Color(0xFF45454B), Color(0xFF26262B), Color(0xFF505057))
    val hue = hues[(domain.hashCode() and 0x7fffffff) % hues.size]
    val letter = domain.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    Box(
        Modifier
            .size(52.dp)
            .background(hue.copy(alpha = if (c.isDark) 0.22f else 0.14f), RoundedCornerShape(16.dp))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            style = TdType.title.copy(fontWeight = FontWeight.Bold),
            color = if (c.isDark) lighten(hue, 0.18f) else darken(hue, 0.12f),
        )
    }
}

/** Text as a snippet, with how much there is. */
@Composable
fun TextCard(text: String, modifier: Modifier = Modifier) {
    val c = Td.colors
    val t = text.trim()
    Column(
        modifier
            .fillMaxWidth()
            .platter(RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(t, style = TdType.body, color = c.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Text(
            if (t.length == 1) "1 character" else "${t.length} characters",
            style = TdType.caption.copy(fontFeatureSettings = "tnum"),
            color = c.textTertiary,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

// MARK: - files

/** Files as segmented rows: kind chip, name, kind and size. More than four collapse into "and N more". */
@Composable
fun FileRows(files: List<SharedFile>, modifier: Modifier = Modifier) {
    val shown = if (files.size > 4) files.take(3) else files
    val rest = files.drop(shown.size)
    val count = shown.size + if (rest.isNotEmpty()) 1 else 0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEachIndexed { i, f -> FileRow(f, segmentShape(i, count)) }
        if (rest.isNotEmpty()) {
            val bytes = if (rest.all { it.size >= 0 }) rest.sumOf { it.size } else -1L
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .platter(segmentShape(count - 1, count))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "and ${rest.size} more" + if (bytes >= 0) " · ${fmtBytes(bytes)}" else "",
                    style = TdType.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = Td.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun FileRow(f: SharedFile, shape: Shape) {
    val c = Td.colors
    val kind = fileKind(f.name, f.mime)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .platter(shape)
            .semantics(mergeDescendants = true) { }
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(kind.icon, kind.accent, size = 40.dp, iconSize = 22.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                f.name,
                style = TdType.body.copy(fontWeight = FontWeight.Medium),
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(fileDetail(f), style = TdType.bodySmall.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
        }
    }
}

// MARK: - shimmer

/** A slow band of light across a placeholder. Still under reduced motion. */
@Composable
fun Modifier.shimmer(): Modifier {
    if (Td.reducedMotion) return this
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    val glint = if (Td.colors.isDark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.55f)
    return drawWithContent {
        drawContent()
        val w = size.width
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent, 0.5f to glint, 1f to Color.Transparent,
                start = Offset(w * x - w * 0.3f, 0f),
                end = Offset(w * x + w * 0.3f, size.height),
            ),
        )
    }
}

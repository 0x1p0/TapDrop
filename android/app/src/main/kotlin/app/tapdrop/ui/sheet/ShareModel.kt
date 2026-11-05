package app.tapdrop.ui.sheet

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.tapdrop.ui.Brand
import app.tapdrop.ui.fmtBytes

/** One shared file, as the sheet shows it. [size] is -1 when the app didn't say. */
@Immutable
data class SharedFile(val uri: Uri?, val name: String, val mime: String, val size: Long) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
    val isVisual get() = isImage || isVideo
}

/** Everything another app handed to "Send to Mac", ready to preview. */
@Immutable
data class ShareContent(
    val files: List<SharedFile> = emptyList(),
    val text: String? = null,
    /** The page title apps like Chrome send along with a link. */
    val subject: String? = null,
) {
    val media: List<SharedFile> get() = files.filter { it.isVisual }
    val others: List<SharedFile> get() = files.filterNot { it.isVisual }
    val isEmpty: Boolean get() = files.isEmpty() && text.isNullOrBlank()

    /** The text, when it is exactly one web address. */
    val link: String? get() = text?.trim()?.takeIf { isWebLink(it) }

    /** Total size when every file reported one. */
    val totalBytes: Long? get() = if (files.isNotEmpty() && files.all { it.size >= 0 }) files.sumOf { it.size } else null

    /** "Send 3 photos", "Send link", "Send file"… for the main button. */
    val action: String
        get() = when {
            link != null -> "Send link"
            text != null -> "Send text"
            files.size == 1 -> when {
                files[0].isImage -> "Send photo"
                files[0].isVideo -> "Send video"
                else -> "Send file"
            }
            files.all { it.isImage } -> "Send ${files.size} photos"
            files.all { it.isVideo } -> "Send ${files.size} videos"
            files.none { it.isVisual } -> "Send ${files.size} files"
            else -> "Send ${files.size} items"
        }

    companion object {
        fun isWebLink(s: String): Boolean {
            if (s.contains(' ') || s.contains('\n')) return false
            val scheme = runCatching { Uri.parse(s).scheme }.getOrNull()?.lowercase()
            return scheme == "http" || scheme == "https"
        }
    }
}

/** What kind of file this is: an icon and a hue for its chip, and a short name ("PDF", "Video"). */
@Immutable
data class FileKind(val icon: ImageVector, val accent: Color, val label: String)

fun fileKind(name: String, mime: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> FileKind(Icons.Rounded.Image, Brand.Orchid, ext.uppercase().ifEmpty { "Image" })
        mime.startsWith("video/") -> FileKind(Icons.Rounded.VideoFile, Brand.Violet, ext.uppercase().ifEmpty { "Video" })
        mime.startsWith("audio/") -> FileKind(Icons.Rounded.AudioFile, Brand.Warning, ext.uppercase().ifEmpty { "Audio" })
        mime == "application/pdf" || ext == "pdf" -> FileKind(Icons.Rounded.PictureAsPdf, Brand.Danger, "PDF")
        ext in setOf("zip", "rar", "7z", "gz", "tgz", "tar", "xz") || mime.contains("zip") ->
            FileKind(Icons.Rounded.FolderZip, Color(0xFF8A8FA3), ext.uppercase().ifEmpty { "Archive" })
        ext == "apk" || mime == "application/vnd.android.package-archive" -> FileKind(Icons.Rounded.Android, Brand.Success, "APK")
        ext in setOf("xls", "xlsx", "csv", "numbers", "ods") || mime.contains("spreadsheet") ->
            FileKind(Icons.Rounded.TableChart, Brand.Success, ext.uppercase())
        ext in setOf("ppt", "pptx", "key", "odp") || mime.contains("presentation") ->
            FileKind(Icons.Rounded.Slideshow, Brand.Warning, ext.uppercase())
        ext in setOf("doc", "docx", "pages", "odt", "rtf") || mime.contains("word") ->
            FileKind(Icons.Rounded.Description, Brand.Blue, ext.uppercase())
        mime.startsWith("text/") || ext in setOf("txt", "md", "json", "csv", "log") ->
            FileKind(Icons.AutoMirrored.Rounded.TextSnippet, Brand.Teal, ext.uppercase().ifEmpty { "Text" })
        else -> FileKind(Icons.AutoMirrored.Rounded.InsertDriveFile, Brand.Blue, ext.uppercase().ifEmpty { "File" })
    }
}

/** "PDF · 2.4 MB", or just "PDF" when the size is unknown. */
fun fileDetail(f: SharedFile): String {
    val kind = fileKind(f.name, f.mime).label
    return if (f.size >= 0) "$kind · ${fmtBytes(f.size)}" else kind
}

/** "apple.com" from "https://www.apple.com/macbook-air/". */
fun linkDomain(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

/** "/macbook-air" from "https://www.apple.com/macbook-air/?ref=x", or "" for a bare domain. */
fun linkPath(url: String): String {
    val u = runCatching { Uri.parse(url) }.getOrNull() ?: return ""
    val path = (u.path ?: "").trimEnd('/')
    return path
}

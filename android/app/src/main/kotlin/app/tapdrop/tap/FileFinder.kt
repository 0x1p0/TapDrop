package app.tapdrop.tap

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.abs

/**
 * Turns a file name shown on screen (a row in Files, a document in a chat, a PDF viewer's title)
 * into the actual file in shared storage, via Android's media index.
 * Photos/videos/audio need media access; other documents need "All files access" (opt-in).
 */
class FileFinder(private val ctx: Context) {
    data class Hit(val uri: Uri, val name: String, val mime: String, val size: Long, val path: String?)

    /** A file shown in a list: its name, the size shown next to it, and where the row is. */
    data class Row(val name: String, val size: Long?, val rect: android.graphics.Rect)

    sealed interface Outcome {
        data class Found(val file: Hit) : Outcome
        /** Probably there, but TapDrop isn't allowed to see documents yet. */
        data object NeedsAccess : Outcome
        data object NotFound : Outcome
    }

    fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    fun open(h: Hit): InputStream =
        runCatching { ctx.contentResolver.openInputStream(h.uri) }.getOrNull()
            ?: h.path?.let { FileInputStream(it) }
            ?: throw java.io.FileNotFoundException(h.name)

    fun find(name: String, sizeHint: Long?): Outcome {
        val rows = mutableListOf<Hit>()
        runCatching {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, 20)
            }
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(
                    MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                    @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
                ),
                args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val base = when (c.getInt(1)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    }
                    rows += Hit(
                        ContentUris.withAppendedId(base, id),
                        c.getString(2) ?: name,
                        c.getString(3) ?: "application/octet-stream",
                        c.getLong(4),
                        c.getString(5),
                    )
                }
            }
        }
        if (rows.isEmpty()) return if (!hasAllFiles() && !isMediaName(name)) Outcome.NeedsAccess else Outcome.NotFound
        val best = sizeHint?.let { h -> rows.minByOrNull { abs(it.size - h) }?.takeIf { abs(it.size - h) <= h * 0.15 + 2048 } }
        return Outcome.Found(best ?: rows.first())
    }

    /**
     * A title shown without its extension ("Boarding pass" for "Boarding pass.pdf", a player's "Holiday clip"):
     * the newest document ([docs]) or video ([videos]) whose name is exactly that plus an extension.
     */
    fun findTitled(title: String, docs: Boolean, videos: Boolean): Hit? {
        val stem = (if (looksLikeFile(title)) title.substringBeforeLast('.') else title).trim()
        if (stem.length < 3 || (!docs && !videos)) return null
        val esc = stem.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return runCatching {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$esc.%"))
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, 20)
            }
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(
                    MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                    @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
                ),
                args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2) ?: continue
                    // LIKE ignores case and "name.part.pdf"; insist on exactly the title plus one extension.
                    if (!name.substringBeforeLast('.').equals(stem, ignoreCase = true)) continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val type = c.getInt(1)
                    val video = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    if (!(videos && video) && !(docs && ext in docExt)) continue
                    val base = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    return@runCatching Hit(
                        ContentUris.withAppendedId(base, c.getLong(0)), name,
                        c.getString(3) ?: "application/octet-stream", c.getLong(4), c.getString(5),
                    )
                }
                null
            }
        }.getOrNull()
    }

    companion object {
        /** Documents a viewer shows one at a time. */
        val docExt = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "md", "csv",
            "epub", "pages", "numbers", "key", "zip", "rar", "7z",
        )
        private val clock = Regex("^\\d{1,2}:\\d{2}(\\s?[AaPp][Mm])?$")
        private val uiWords = setOf(
            "back", "navigate up", "more options", "more", "share", "search", "open with", "print", "edit", "done", "close",
            "menu", "options", "download", "save", "delete", "info", "details", "favorite", "add to", "send", "play", "pause",
        )

        /**
         * What a viewer calls the thing it shows: the window's title, the last title the app announced, and the texts
         * in its top bar — best first, without buttons, clocks and the like.
         */
        fun titlesOnScreen(boxes: List<ScreenText.Box>, height: Int, extra: List<String?>): List<String> {
            val top = boxes.filter { it.rect.bottom < height * 0.22 && it.rect.height() < height * 0.12 }
                .sortedBy { it.rect.top }
                .mapNotNull { it.text ?: it.desc }
            return (extra.filterNotNull() + top)
                .map { it.trim() }
                .filter { t ->
                    t.length in 3..160 && t.count { it.isLetterOrDigit() } >= 3 && !clock.matches(t) &&
                        t.lowercase() !in uiWords && !t.contains("://")
                }
                .distinct()
                .take(10)
        }

        private val fileName = Regex("^[^/\\\\\\n]{1,200}\\.[A-Za-z0-9]{1,8}$")
        private val size = Regex("(\\d+(?:[.,]\\d+)?)\\s*(bytes|B|KB|kB|MB|GB)\\b")
        private val mediaExt = setOf("jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "mp4", "mov", "mkv", "webm", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

        fun looksLikeFile(s: String): Boolean {
            val t = s.trim()
            return fileName.matches(t) && !t.contains("://") && !t.startsWith("www.") && t.substringAfterLast('.').any { it.isLetter() }
        }

        fun isMediaName(n: String) = n.substringAfterLast('.', "").lowercase() in mediaExt

        /** "1.2 MB" → bytes. */
        fun parseSize(s: String): Long? {
            val m = size.find(s) ?: return null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val mul = when (m.groupValues[2].uppercase()) {
                "KB" -> 1024.0
                "MB" -> 1024.0 * 1024
                "GB" -> 1024.0 * 1024 * 1024
                else -> 1.0
            }
            return (v * mul).toLong()
        }

        /** The file row under (x, y): the smallest element around the point whose texts include a file name. */
        fun fileAt(boxes: List<ScreenText.Box>, x: Int, y: Int, screenArea: Long): Row? {
            val around = boxes.filter { it.rect.contains(x, y) && it.area < screenArea * 0.4 }.sortedBy { it.area }
            for (c in around) {
                val texts = boxes.filter { c.rect.contains(it.rect.centerX(), it.rect.centerY()) }.mapNotNull { it.text ?: it.desc }
                val name = texts.firstOrNull { looksLikeFile(it) } ?: continue
                return Row(name.trim(), texts.firstNotNullOfOrNull { parseSize(it) }, c.rect)
            }
            return null
        }

        /** A document open in a viewer: its name is usually the title near the top of the screen. */
        fun fileOnScreen(boxes: List<ScreenText.Box>, height: Int): String? =
            boxes.filter { it.rect.bottom < height * 0.2 }.mapNotNull { it.text ?: it.desc }.firstOrNull { looksLikeFile(it) }?.trim()
    }
}

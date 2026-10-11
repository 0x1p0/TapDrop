package app.tapdrop.net

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.tapdrop.core.BatchSink
import app.tapdrop.core.DecryptException
import app.tapdrop.core.Endpoint
import app.tapdrop.core.IncomingFile
import app.tapdrop.core.OutItem
import app.tapdrop.core.PairRejectedException
import app.tapdrop.core.Pong
import app.tapdrop.core.Progress
import app.tapdrop.core.TapDropClient
import app.tapdrop.core.UnpairedException
import app.tapdrop.core.UnreachableException
import app.tapdrop.data.HistoryEntry
import app.tapdrop.data.Prefs
import app.tapdrop.data.SavedMac
import app.tapdrop.ui.Haptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException

data class Received(val uri: Uri?, val name: String, val mime: String, val text: String? = null, val clip: Boolean = false) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
    val isLink get() = text != null && Uri.parse(text.trim()).scheme?.startsWith("http") == true && !text.trim().contains(' ')
}

sealed interface TState {
    data object Idle : TState

    data class Working(
        val macId: String,
        val macName: String,
        val outgoing: Boolean,
        val title: String,
        val done: Long = 0,
        val total: Long = -1,
        val current: String = "",
    ) : TState {
        val progress: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data class Done(
        val macId: String,
        val macName: String,
        val outgoing: Boolean,
        val title: String,
        val subtitle: String,
        val received: List<Received> = emptyList(),
        val empty: Boolean = false,
    ) : TState

    data class Failed(val macId: String, val macName: String, val message: String) : TState
}

/** Runs transfers on an app-wide scope (they survive the share sheet closing) and exposes live state. */
class Transfers(
    private val app: Context,
    private val prefs: Prefs,
    private val finder: MacFinder,
    private val scope: CoroutineScope,
) {
    val client = TapDropClient(prefs.identity)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<TState>(TState.Idle)
    val state: StateFlow<TState> = _state.asStateFlow()

    val busy get() = _state.value is TState.Working

    fun reset() {
        if (!busy) _state.value = TState.Idle
    }

    private suspend fun <T> onMac(mac: SavedMac, op: suspend (Endpoint) -> T): T = withContext(Dispatchers.IO) {
        val first = finder.endpointFor(mac.id) ?: mac.endpoint
        try {
            op(first)
        } catch (e: UnreachableException) {
            // The Mac may have a new IP address; ask Bonjour where it went.
            val fresh = finder.awaitEndpoint(mac.id, 4000) ?: throw e
            if (fresh == first) throw e
            op(fresh)
        }
    }

    suspend fun ping(mac: SavedMac): Pong? = runCatching { onMac(mac) { client.ping(it, mac.macKey) } }.getOrNull()

    /**
     * Pairs with a Mac; cancelling the coroutine hangs up (which withdraws the prompt on the Mac).
     * The caller saves the result (after its success animation) via [Prefs.saveMac].
     */
    suspend fun pair(ep: Endpoint, onCode: (code: String, macName: String) -> Unit): SavedMac = coroutineScope {
        var conn: java.io.Closeable? = null
        val job = async(Dispatchers.IO) { client.pair(ep, { conn = it }, onCode) }
        val r = try {
            job.await()
        } catch (e: CancellationException) {
            runCatching { conn?.close() }
            throw e
        }
        SavedMac(r.macId, r.macName, r.key, ep.host, ep.port)
    }

    fun send(
        mac: SavedMac,
        items: List<OutItem>,
        label: String,
        preview: Uri? = null,
        previewMime: String? = null,
        record: Boolean = true,
        haptics: Boolean = true,
    ): Job =
        run(mac, outgoing = true, title = "Sending $label", haptics = haptics) { ep, progress ->
            val n = client.send(ep, mac.macKey, items, progress)
            if (record) prefs.addHistory(HistoryEntry(true, mac.name, label, System.currentTimeMillis(), preview?.toString(), previewMime))
            TState.Done(mac.id, mac.name, true, "Sent", if (n == 1) label else "$n items")
        }

    fun receiveInbox(mac: SavedMac): Job = run(mac, outgoing = false, title = "Receiving from ${mac.name}") { ep, progress ->
        val sink = MediaStoreSink(app)
        client.pullInbox(ep, mac.macKey, sink, progress)
        finishReceive(mac, sink.items, "Nothing waiting on your Mac")
    }

    fun pullClipboard(mac: SavedMac): Job = run(mac, outgoing = false, title = "Getting Mac clipboard") { ep, progress ->
        val sink = MediaStoreSink(app)
        client.getClipboard(ep, mac.macKey, sink, progress)
        finishReceive(mac, sink.items, "Mac clipboard is empty")
    }

    private suspend fun finishReceive(mac: SavedMac, items: List<Received>, emptyMessage: String): TState {
        if (items.isEmpty()) return TState.Done(mac.id, mac.name, false, emptyMessage, "", empty = true)
        withContext(Dispatchers.Main) { applyClipboard(items) }
        val (title, subtitle) = describe(items)
        val first = items.first()
        prefs.addHistory(HistoryEntry(false, mac.name, subtitle.ifEmpty { title }, System.currentTimeMillis(), first.uri?.toString(), first.mime))
        return TState.Done(mac.id, mac.name, false, title, subtitle, items)
    }

    private fun run(
        mac: SavedMac,
        outgoing: Boolean,
        title: String,
        haptics: Boolean = true,
        block: suspend (Endpoint, Progress) -> TState,
    ): Job =
        scope.launch {
            mutex.withLock {
                _state.value = TState.Working(mac.id, mac.name, outgoing, title)
                var last = 0L
                val progress = Progress { done, total, current ->
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 40 || (total in 1..done)) {
                        last = now
                        _state.value = TState.Working(mac.id, mac.name, outgoing, title, done, total, current)
                    }
                }
                val result = try {
                    onMac(mac) { ep -> block(ep, progress) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TState.Failed(mac.id, mac.name, friendly(e, mac.name))
                }
                _state.value = result
                if (haptics) when (result) {
                    is TState.Done -> if (!result.empty) Haptics.success(app) else Haptics.tick(app)
                    is TState.Failed -> Haptics.error(app)
                    else -> Unit
                }
            }
        }

    private fun applyClipboard(items: List<Received>) {
        val clip = items.lastOrNull { it.clip } ?: return
        val cm = app.getSystemService(ClipboardManager::class.java)
        when {
            clip.text != null -> cm.setPrimaryClip(ClipData.newPlainText("TapDrop", clip.text))
            clip.uri != null -> cm.setPrimaryClip(ClipData.newUri(app.contentResolver, clip.name, clip.uri))
        }
    }

    companion object {
        fun friendly(e: Throwable, macName: String): String = when (e) {
            is UnreachableException -> "Can't reach $macName. Make sure TapDrop is running and you're on the same Wi‑Fi."
            is UnpairedException, is DecryptException -> "$macName doesn't recognise this phone anymore. Pair again in TapDrop."
            is PairRejectedException -> "Pairing was declined on the Mac."
            is SocketTimeoutException -> "The connection timed out."
            is FileNotFoundException -> "Couldn't open one of the files."
            is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "The connection was interrupted."
            else -> e.message ?: "Something went wrong."
        }

        fun describe(items: List<Received>): Pair<String, String> {
            val files = items.filter { it.text == null }
            val texts = items.filter { it.text != null }
            return when {
                files.isEmpty() && texts.size == 1 ->
                    if (texts[0].isLink) "Link copied" to texts[0].text!!.trim() else "Text copied" to texts[0].text!!.take(80)
                files.size == 1 && texts.isEmpty() ->
                    (if (files[0].clip) "Copied image" else "Received") to files[0].name
                files.isNotEmpty() && files.all { it.isImage || it.isVideo } && texts.isEmpty() ->
                    "Received ${files.size} ${if (files.all { it.isImage }) "photos" else "items"}" to "Saved to Photos"
                else -> "Received ${items.size} items" to "Saved to Downloads › TapDrop"
            }
        }
    }
}

/** Saves incoming files into MediaStore: photos → Pictures/TapDrop, videos → Movies/TapDrop, rest → Download/TapDrop. */
class MediaStoreSink(private val ctx: Context) : BatchSink {
    val items = mutableListOf<Received>()

    override fun openFile(name: String, size: Long, mime: String, clip: Boolean): IncomingFile {
        val type = mime.ifBlank { Outgoing.guessMime(name) }
        val (collection, dir) = when {
            type.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/TapDrop"
            type.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/TapDrop"
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/TapDrop"
        }
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, values) ?: throw IOException("Couldn't save $name")
        val stream = (cr.openOutputStream(uri) ?: throw IOException("Couldn't save $name")).buffered(1 shl 17)
        return object : IncomingFile {
            override val out: OutputStream = stream
            override fun commit() {
                stream.close()
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                items += Received(uri, name, type, clip = clip)
            }
            override fun abort() {
                runCatching { stream.close() }
                runCatching { cr.delete(uri, null, null) }
            }
        }
    }

    override fun text(text: String, clip: Boolean) {
        items += Received(null, "Text", "text/plain", text = text, clip = clip)
    }
}

data class LatestMedia(val uri: Uri, val name: String, val mime: String, val addedSec: Long)

/** Turns Android things (content URIs, clipboard) into protocol items. */
object Outgoing {
    fun guessMime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    fun fromUri(ctx: Context, uri: Uri, clip: Boolean = false): OutItem.File? {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        val mime = cr.getType(uri) ?: guessMime(name ?: "")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val finalName = name ?: ("TapDrop-${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: ""))
        return OutItem.File(finalName, size, mime, clip) {
            cr.openInputStream(uri) ?: throw FileNotFoundException(finalName)
        }
    }

    fun fromClip(ctx: Context, clip: ClipData?): List<OutItem> {
        clip ?: return emptyList()
        val out = mutableListOf<OutItem>()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri
            if (uri != null) {
                fromUri(ctx, uri, clip = true)?.let(out::add)
            } else {
                val text = item.coerceToText(ctx)?.toString()
                if (!text.isNullOrEmpty()) out += OutItem.Text(text, clip = true)
            }
        }
        return out
    }

    fun label(items: List<OutItem>): String {
        val files = items.filterIsInstance<OutItem.File>()
        val texts = items.filterIsInstance<OutItem.Text>()
        return when {
            files.isEmpty() && texts.size == 1 -> {
                val t = texts[0].text.trim()
                if (Uri.parse(t).scheme?.startsWith("http") == true && !t.contains(' ')) "link" else "text"
            }
            files.size == 1 && texts.isEmpty() -> files[0].name
            files.isNotEmpty() && files.all { it.mime.startsWith("image/") } -> "${files.size} photos"
            files.isNotEmpty() && files.all { it.mime.startsWith("image/") || it.mime.startsWith("video/") } -> "${files.size} photos & videos"
            else -> "${items.size} items"
        }
    }

    /** Newest photo or video in the gallery (needs READ_MEDIA_IMAGES/VIDEO). */
    fun latestMedia(ctx: Context): LatestMedia? = runCatching {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
            )
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        ctx.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Files.FileColumns.MEDIA_TYPE,
            ),
            args, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val id = c.getLong(0)
            val type = c.getInt(4)
            val base = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            LatestMedia(android.content.ContentUris.withAppendedId(base, id), c.getString(1) ?: "Photo", c.getString(2) ?: "image/jpeg", c.getLong(3))
        }
    }.getOrNull()
}

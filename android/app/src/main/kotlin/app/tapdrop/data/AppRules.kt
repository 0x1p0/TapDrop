package app.tapdrop.data

/** What holding the phone to the Mac's notch sends, chosen per app. */
enum class SendMode(val title: String, val detail: String) {
    SMART("Smart", "The page in browsers, the PDF, video or photo on screen, otherwise a screenshot"),
    LINK("Page link", "Opens the same page on your Mac"),
    MEDIA("Photo or video on screen", "The original file when it's in your gallery, else a screenshot"),
    LATEST_MEDIA("Latest photo or video", "Your newest shot, full quality"),
    SCREENSHOT("Screenshot", "Exactly what's on screen"),
    TEXT("Text on screen", "Copies the visible text to your Mac"),
    OFF("Off", "Holding to the notch does nothing here"),
}

object AppRules {
    /** Browser package → address-bar view ids. */
    val URL_BARS = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.chrome.dev" to listOf("url_bar"),
        "com.chrome.canary" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.vivaldi.browser" to listOf("url_bar"),
        "com.kiwibrowser.browser" to listOf("url_bar"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view"),
        "org.mozilla.focus" to listOf("display_url", "mozac_browser_toolbar_url_view"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        "com.opera.browser" to listOf("url_field"),
    )

    val GALLERIES = setOf(
        "com.google.android.apps.photos",
        "com.google.android.apps.photosgo",
        "com.sec.android.gallery3d",
        "com.android.gallery3d",
        "com.miui.gallery",
        "com.oneplus.gallery",
        "com.coloros.gallery3d",
        "com.vivo.gallery",
        "org.fossify.gallery",
        "com.simplemobiletools.gallery.pro",
        "com.google.android.apps.nbu.files",
        "com.google.android.documentsui",
    )

    /** Apps that show one document at a time (PDF and office viewers, file managers' viewers). */
    val DOC_VIEWERS = setOf(
        "com.google.android.apps.docs",
        "com.google.android.apps.pdfviewer",
        "com.google.android.apps.nbu.files",
        "com.google.android.documentsui",
        "com.adobe.reader",
        "com.microsoft.office.officehubrow",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "cn.wps.moffice_eng",
        "cn.wps.moffice_lite",
        "com.sec.android.app.myfiles",
        "com.xodo.pdf.reader",
        "com.foxit.mobile.pdf.lite",
        "com.dropbox.android",
        "com.microsoft.skydrive",
        "org.fossify.filemanager",
        "com.simplemobiletools.filemanager.pro",
        "com.mi.android.globalFileexplorer",
    )

    /** Video players that play files from the phone (their title is usually the file's name). */
    val VIDEO_PLAYERS = setOf(
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "com.google.android.apps.photos",
        "com.google.android.apps.photosgo",
        "com.google.android.apps.nbu.files",
        "com.sec.android.app.videoplayer",
        "com.samsung.android.video",
        "com.miui.videoplayer",
        "is.xyz.mpv",
        "com.brouken.player",
    )

    /** Chat apps whose received photos and videos land in the phone's library (shown full screen in their viewers). */
    val CHATS = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
    )

    fun isBrowser(pkg: String) = pkg in URL_BARS
    fun isChat(pkg: String) = pkg in CHATS
    fun isDocViewer(pkg: String) = pkg in DOC_VIEWERS
    fun isVideoPlayer(pkg: String) = pkg in VIDEO_PLAYERS
    fun isGallery(pkg: String) = pkg in GALLERIES
    fun isCamera(pkg: String) = pkg == "com.google.android.GoogleCamera" || pkg.contains("camera", ignoreCase = true)

    /**
     * Smart: in a browser the page itself (its address, so it opens on the Mac; a screenshot when the address can't
     * be read), in a camera the newest shot, elsewhere the photo/video on screen if it can be found in the library,
     * otherwise a screenshot.
     */
    fun smart(pkg: String): SendMode = when {
        isBrowser(pkg) -> SendMode.LINK
        isCamera(pkg) -> SendMode.LATEST_MEDIA
        else -> SendMode.MEDIA
    }

    fun options(pkg: String): List<SendMode> = buildList {
        add(SendMode.SMART)
        if (isBrowser(pkg)) add(SendMode.LINK)
        add(SendMode.MEDIA)
        add(SendMode.LATEST_MEDIA)
        add(SendMode.SCREENSHOT)
        add(SendMode.TEXT)
        add(SendMode.OFF)
    }
}

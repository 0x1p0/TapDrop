package app.tapdrop.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.tapdrop.tap.NotchTapService

/** What this phone is, what it supports, and the brand-specific steps that keep TapDrop working on it. */
object DeviceSupport {
    private val maker = Build.MANUFACTURER.orEmpty()
    private val brand = maker.lowercase()

    val isPixel get() = brand == "google"
    val supported get() = Build.VERSION.SDK_INT >= 33

    /** "Google Pixel 9", "Samsung SM-S911B". */
    val modelName: String
        get() {
            val m = Build.MODEL.orEmpty()
            val b = maker.replaceFirstChar { it.uppercase() }
            return if (m.startsWith(maker, ignoreCase = true)) m else "$b $m"
        }

    val androidVersion: String get() = "Android ${Build.VERSION.RELEASE}"

    fun hasNfc(ctx: Context) = NfcAdapter.getDefaultAdapter(ctx) != null
    fun hasMotionSensor(ctx: Context) =
        ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /** A proximity sensor lets the phone start a tap itself when its top meets the notch. */
    fun hasProximity(ctx: Context) =
        ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // MARK: staying alive in the background

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** The system "Let TapDrop always run in the background?" prompt (falls back to App info). */
    fun requestUnrestricted(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { openAppInfo(ctx) }
    }

    fun openAppInfo(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openAccessibility(ctx: Context) {
        val component = ComponentName(ctx, NotchTapService::class.java).flattenToString()
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(details) }.onFailure {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Where TapDrop shows up in Accessibility settings on this phone. */
    val accessibilityPath: String
        get() = when (brand) {
            "samsung" -> "Accessibility → Installed apps → TapDrop → On"
            "xiaomi", "redmi", "poco" -> "Accessibility → Downloaded apps → TapDrop → On"
            else -> "Accessibility → Downloaded apps (or Installed apps) → TapDrop → On"
        }

    /** Phones that stop background apps aggressively, and how to stop them stopping TapDrop. */
    data class KeepAlive(val brand: String, val steps: List<String>)

    val keepAlive: KeepAlive?
        get() = when (brand) {
            "samsung" -> KeepAlive(
                "Samsung",
                listOf(
                    "App info → Battery → Unrestricted",
                    "Settings → Battery → Background usage limits → make sure TapDrop isn't a sleeping app",
                ),
            )
            "xiaomi", "redmi", "poco" -> KeepAlive(
                "Xiaomi",
                listOf(
                    "App info → Autostart → On",
                    "App info → Battery saver → No restrictions",
                    "In Recents, pull TapDrop down to lock it",
                ),
            )
            "oneplus", "oppo", "realme" -> KeepAlive(
                maker.replaceFirstChar { it.uppercase() },
                listOf(
                    "App info → Battery usage → Allow background activity",
                    "App info → Battery usage → Allow auto launch",
                ),
            )
            "vivo", "iqoo" -> KeepAlive(
                "vivo",
                listOf("Settings → Battery → Background power consumption → TapDrop → Allow"),
            )
            "honor", "huawei" -> KeepAlive(
                maker.replaceFirstChar { it.uppercase() },
                listOf("Settings → Battery → App launch → TapDrop → Manage manually, all switches on"),
            )
            "motorola", "nothing", "sony", "asus", "nokia", "hmd global", "fairphone", "tecno", "infinix" -> KeepAlive(
                maker.replaceFirstChar { it.uppercase() },
                listOf("App info → Battery → Unrestricted"),
            )
            else -> null  // Pixels and stock Android keep accessibility services running on their own
        }
}

/** Which phones and Macs TapDrop works with. Mirrored in the Mac app's Settings → Compatibility. */
object Compatibility {
    data class Group(val name: String, val models: String)

    const val androidVersions = "Android 13, 14, 15, 16 and newer"
    const val macRequirement = "macOS 15 Sequoia or newer, on a Mac with Apple silicon (M1 or later)"
    const val tested = "Works with Android 13 or newer from any brand. Tap to send needs a Mac with a notch."

    val pixels = listOf(
        "Pixel 10 · 10 Pro · 10 Pro XL · 10 Pro Fold",
        "Pixel 9 · 9 Pro · 9 Pro XL · 9 Pro Fold · 9a",
        "Pixel 8 · 8 Pro · 8a",
        "Pixel 7 · 7 Pro · 7a · Fold · Tablet",
        "Pixel 6 · 6 Pro · 6a",
        "Pixel 5 · 5a",
        "Pixel 4 · 4 XL · 4a · 4a (5G)",
    )

    val brands = listOf(
        Group("Samsung Galaxy", "S20 and newer, Note 20, Z Fold / Flip 3 and newer, most A-series from 2021 on"),
        Group("OnePlus", "OnePlus 8 and newer, Nord models on Android 13+"),
        Group("Nothing", "Phone (1), (2), (2a), (3) and CMF Phone"),
        Group("Xiaomi · Redmi · POCO", "Models on HyperOS or MIUI 14"),
        Group("Motorola", "Edge and moto g models updated to Android 13+"),
        Group("Sony Xperia", "Xperia 1, 5 and 10 IV and newer"),
        Group("Oppo · Realme · Vivo · iQOO", "Models updated to Android 13+"),
    )

    val notSupported = listOf(
        "Pixel 3 and older (they stopped at Android 12)",
        "Any phone on Android 12 or older",
        "Huawei phones on HarmonyOS, Amazon Fire tablets",
    )

    /** A kind of Mac and what TapDrop does on it. */
    data class MacGroup(val name: String, val models: String, val can: String, val tap: Boolean, val runs: Boolean = true)

    /** Tapping needs the light sensor in a MacBook's notch; everything else runs on any Apple silicon Mac. */
    val macs = listOf(
        MacGroup(
            "MacBook Air and MacBook Pro with a notch",
            "MacBook Air (M2 and later) · MacBook Pro 14″ and 16″ (2021 and later)",
            "Tap to send, and everything else", tap = true,
        ),
        MacGroup(
            "Other Macs with Apple silicon",
            "MacBook Air (M1), 13″ MacBook Pro, iMac, Mac mini, Mac Studio and Mac Pro",
            "Sharing, codes and clipboard", tap = false,
        ),
        MacGroup("Intel Macs", "TapDrop needs a universal build for them", "Not yet", tap = false, runs = false),
    )

    /** One thing a setup needs, and why. */
    data class Need(val title: String, val detail: String)

    val needs = listOf(
        Need("Phone and Mac on the same Wi‑Fi", "Guest or hotel Wi‑Fi that keeps devices apart won't work"),
        Need("TapDrop on both", "Paired once, with a 6-digit code from your Mac"),
        Need("To tap: a MacBook with a notch", "And TapDrop turned on in Accessibility on your phone"),
        Need("Photos & videos access", "So originals are sent instead of screenshots"),
    )
}

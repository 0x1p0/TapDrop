package app.tapdrop.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.tapdrop.R
import app.tapdrop.data.SavedMac

/** Publishes each paired Mac as a Direct Share target (top row of the Android share sheet). */
object Shortcuts {
    const val CATEGORY = "app.tapdrop.category.MAC"
    private const val PREFIX = "mac_"

    fun idFor(macId: String) = PREFIX + macId

    fun macIdFrom(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun publish(ctx: Context, macs: List<SavedMac>) {
        runCatching {
            val wanted = macs.map { idFor(it.id) }.toSet()
            val stale = ShortcutManagerCompat.getDynamicShortcuts(ctx).map { it.id }.filter { it !in wanted }
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(ctx, stale)
            macs.forEach { mac ->
                val s = ShortcutInfoCompat.Builder(ctx, idFor(mac.id))
                    .setShortLabel(mac.name.take(24))
                    .setLongLabel(mac.name)
                    .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_shortcut_mac))
                    .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                    .setLongLived(true)
                    .setCategories(setOf(CATEGORY))
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(ctx, s)
            }
        }
    }

    fun reportUsed(ctx: Context, macId: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(ctx, idFor(macId)) }
    }
}

package app.tapdrop.ui.sheet

import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Td
import app.tapdrop.ui.design.GlassLayers
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * Window setup for TapDrop's translucent sheet activities (share, NFC tap): edge to edge, light status icons
 * over the dimmed app behind, navigation icons that match the sheet, and permission to blur what's behind.
 * Call before `super.onCreate`.
 */
fun ComponentActivity.installSheetWindow() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    window.attributes = window.attributes.apply { blurBehindRadius = 0 }
}

/**
 * The stage for a sheet that floats over another app. While [shown], the app behind is blurred (on phones
 * that support cross-window blur, eased in with the sheet), and the sheet in [content] becomes real glass over
 * it; where blur isn't available, the sheet stays a solid surface so nothing sharp shows through it.
 * Once [shown] turns false the blur eases out and then [onHidden] runs (finish the activity there).
 */
@Composable
fun SheetScene(shown: Boolean, onHidden: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val activity = LocalActivity.current
    val blur = rememberCrossWindowBlur()
    val reduced = Td.reducedMotion
    val level = remember { Animatable(0f) }
    val hidden by rememberUpdatedState(onHidden)
    LaunchedEffect(shown, blur) {
        if (shown) {
            if (blur) level.animateTo(1f, tween(if (reduced) 120 else 420, easing = FastOutSlowInEasing))
        } else {
            if (blur && level.value > 0f) level.animateTo(0f, tween(if (reduced) 80 else 170, easing = LinearOutSlowInEasing))
            hidden()
        }
    }
    val maxRadius = with(LocalDensity.current) { 26.dp.roundToPx() }
    LaunchedEffect(activity) {
        val window = activity?.window ?: return@LaunchedEffect
        snapshotFlow { (level.value * maxRadius).roundToInt() }
            .distinctUntilChanged()
            .collect { r -> window.attributes = window.attributes.apply { blurBehindRadius = r } }
    }
    if (blur) {
        // Nothing of our own lies under the sheet: the glass frosts the blurred app behind the window.
        GlassLayers(Modifier.fillMaxSize(), background = Color.Transparent, chrome = content) {}
    } else {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/** Whether the system will blur behind our window right now (it can't on some devices, or in battery saver). */
@Composable
private fun rememberCrossWindowBlur(): Boolean {
    val ctx = LocalContext.current
    val wm = remember(ctx) { ctx.getSystemService(WindowManager::class.java) }
    var on by remember { mutableStateOf(wm?.isCrossWindowBlurEnabled == true) }
    DisposableEffect(wm) {
        if (wm == null) return@DisposableEffect onDispose { }
        val listener = Consumer<Boolean> { on = it }
        wm.addCrossWindowBlurEnabledListener(ctx.mainExecutor, listener)
        onDispose { wm.removeCrossWindowBlurEnabledListener(listener) }
    }
    return on
}

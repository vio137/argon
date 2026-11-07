package me.ik.argon

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Motion tokens (compose-animations / android-compose-design guidance):
 * springs for spatial motion, one short tween for effects, and a hard gate
 * on the system reduced-motion setting (transition animation scale 0).
 * One orchestrated moment per screen; nothing loops.
 */
object ArgonMotion {
    val Spatial = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val Effects = tween<Float>(durationMillis = 160, easing = FastOutSlowInEasing)
}

private fun motionEnabledNow(resolver: android.content.ContentResolver): Boolean =
    // Both gates: a zero on EITHER scale means the user asked for no motion
    // (transition scale covers window/transition motion, animator duration
    // scale covers value animators like ours).
    Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) != 0f &&
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Live read of the system motion preference: observes both animation
 * scales and re-evaluates when either changes, so toggling the setting
 * while Argon runs takes effect on the next entrance. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val enabled = remember { mutableStateOf(motionEnabledNow(resolver)) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled.value = motionEnabledNow(resolver)
                }
            }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled.value
}

/**
 * Entrance for full-screen secondary pages: a single rise-and-fade on first
 * composition. Values are read inside the graphicsLayer block, so the frame
 * animation never recomposes the page. Reduced motion: jumps straight to
 * the end state.
 */
@Composable
fun PageEntrance(content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    val alpha = remember { Animatable(if (motion) 0f else 1f) }
    val rise = remember { Animatable(if (motion) 12f else 0f) }
    LaunchedEffect(motion) {
        if (motion) {
            launch { alpha.animateTo(1f, ArgonMotion.Effects) }
            launch { rise.animateTo(0f, ArgonMotion.Spatial) }
        } else {
            // Motion switched off (possibly mid-entrance): land on end state.
            alpha.snapTo(1f)
            rise.snapTo(0f)
        }
    }
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = rise.value.dp.toPx()
        },
    ) {
        content()
    }
}

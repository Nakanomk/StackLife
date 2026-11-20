package io.github.nakanomk.stacklife.transitions

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.itgsa.animation.sharedview.ViewAnimationConfig
import com.itgsa.animation.sharedview.ViewAnimationExtras
import com.itgsa.animation.sharedview.ViewAnimationManager
import com.itgsa.animation.sharedview.ViewAnimationType
import io.github.nakanomk.stacklife.StackLifeApplication

/** Local opt-in manifest only. The pinned AAR probes the ROM; an ordinary installation never loads it. */
internal class KitApplication : StackLifeApplication(), DetailTransitionHost {
    private val protocol by lazy {
        val version = platformCall { ViewAnimationManager.isViewAnimationSupported() }
        Log.i("StackLifeKit", "SharedView protocol=${version ?: "unavailable"}")
        version ?: 0
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    internal val sources = mutableMapOf<SourceKey, KitCardSource>()

    @Composable
    override fun listSource(scroll: LazyListState, bottomClearance: Dp, revision: Long): DetailListSource? {
        if (protocol <= 0) return null
        val activity = LocalActivity.current ?: return null
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val config = LocalConfiguration.current
        val list = remember(activity, scroll, lifecycle, config) {
            KitListSource(this, activity, lifecycle, scroll, Configuration(config))
        }
        val clearance = with(LocalDensity.current) { bottomClearance.roundToPx() }
        SideEffect { list.clearancePx = clearance; list.revision = revision }
        DisposableEffect(list) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) list.opening = null }
            lifecycle.addObserver(observer)
            onDispose { list.opening = null; lifecycle.removeObserver(observer) }
        }
        return list
    }

    internal fun open(source: KitCardSource, fallback: () -> Unit) {
        val list = source.list
        val activity = list.activity
        if (list.opening != null || sources[source.key] !== source) return
        if (!ValueAnimator.areAnimatorsEnabled() || !activity.hasWindowFocus() ||
            list.lifecycle.currentState != Lifecycle.State.RESUMED) return fallback()
        // If the ROM won't accept a safe BACK when a source disappears, keep both legs in Compose.
        val extras = platformCall { ViewAnimationManager.getViewAnimationExtras() }
        if (!permitsFollowSystem(extras?.get(ViewAnimationExtras.EXTRA_BACK_ANIM_TYPE))) return fallback()
        val start = platformCall { source.geometry() } ?: return fallback()
        val bitmap = try { Bitmap.createBitmap(start.window.width(), start.window.height(), Bitmap.Config.ARGB_8888) }
        catch (_: OutOfMemoryError) { return fallback() }
        val request = Any()
        list.opening = request
        val submitted = platformCall {
            // PixelCopy's Window API always needs WINDOW coordinates, even on a SCREEN-coordinate ROM.
            PixelCopy.request(activity.window, Rect(start.window), bitmap, { result ->
                val canNavigate = list.opening === request &&
                    list.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() &&
                    !activity.isFinishing && !activity.isDestroyed
                val usable = canNavigate && result == PixelCopy.SUCCESS && sources[source.key] === source &&
                    ValueAnimator.areAnimatorsEnabled() && platformCall { source.geometry() } == start
                val launched = usable && platformCall launch@{
                    // Default to system BACK until the detail host refreshes a live target.
                    val config = ViewAnimationConfig().setBackAnimType(ViewAnimationType.VIEW_ANIM_FOLLOW_SYSTEM)
                    val options = ViewAnimationManager.makeViewAnimationOptions(bitmap, start.kitBounds(extras), start.radius,
                        config.toBundle(), null) ?: return@launch false
                    activity.startActivity(Intent(activity, EntityDetailActivity::class.java)
                        .putExtra("kind", source.key.target.kind).putExtra("id", source.key.target.id), options.toBundle())
                    // Kit may retain the passed bitmap for this transition; do not recycle it.
                    true
                } == true
                if (!launched) {
                    bitmap.recycle()
                    if (list.opening === request) list.opening = null
                    if (canNavigate) fallback()
                }
            }, mainHandler)
        }
        if (submitted == null) {
            bitmap.recycle()
            list.opening = null
            fallback()
        }
    }

    internal fun refreshBack(detail: Activity, target: DetailTarget, latestRevision: Long?) {
        if (protocol <= 0) return
        platformCall {
            val config = ViewAnimationConfig().setBackAnimType(ViewAnimationType.VIEW_ANIM_FOLLOW_SYSTEM)
            val source = sources[SourceKey(detail.taskId, target)]
            // A covered activity can retain its old layout through rotation, resizing or a Room write.
            if (source != null && latestRevision == source.list.revision && ValueAnimator.areAnimatorsEnabled() &&
                source.list.configuration.sameCardLayout(detail.resources.configuration) &&
                source.list.activity.window.decorView.width == detail.window.decorView.width &&
                source.list.activity.window.decorView.height == detail.window.decorView.height) {
                platformCall {
                    source.geometry()?.let { fresh ->
                        config.setBounds(fresh.kitBounds(ViewAnimationManager.getViewAnimationExtras())).setRadius(fresh.radius)
                            .setBackAnimType(ViewAnimationType.VIEW_ANIM_DEFAULT)
                    }
                }
            }
            ViewAnimationManager.overrideViewAnimationTransition(detail, config.toBundle())
        }
    }
}

private fun Configuration.sameCardLayout(other: Configuration) = orientation == other.orientation &&
    screenWidthDp == other.screenWidthDp && screenHeightDp == other.screenHeightDp && densityDpi == other.densityDpi &&
    layoutDirection == other.layoutDirection && fontScale == other.fontScale && locales == other.locales

private inline fun <T> platformCall(action: () -> T): T? = try { action() }
catch (_: RuntimeException) { null }
catch (_: LinkageError) { null }

private fun CardGeometry.kitBounds(extras: Map<String, String>?) =
    if (usesWindowBounds(extras?.get(ViewAnimationExtras.EXTRA_BOUNDS))) window else screen

internal fun usesWindowBounds(meta: String?): Boolean {
    val token = meta?.substringAfter('|', "")?.substringBefore('|')?.trim()
    if (token.equals("SCREEN", ignoreCase = true)) return false
    if (token.equals("WINDOW", ignoreCase = true)) return true
    return meta?.contains("WINDOW", ignoreCase = true) == true
}

/** A ROM lacking an explicit system-back option cannot safely animate a source that might disappear. */
internal fun permitsFollowSystem(meta: String?): Boolean {
    if (meta == null) return false
    val values = meta.substringAfter('|', "").substringBefore('|').split(',').map(String::trim)
    return values.none { it.startsWith("VIEW_ANIM_") } || "VIEW_ANIM_FOLLOW_SYSTEM" in values
}

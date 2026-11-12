/*
 * SPDX-FileCopyrightText: MIRACLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.miraclo

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherState
import com.android.launcher3.util.TouchController
import kotlin.math.abs

/**
 * MIRACLO launcher features (Mira Studio › Home screen › Launcher), stored in Settings.Secure so
 * Settings can change them:
 * - miraclo_home_labels: 1 = names under home screen icons (default), 0 = icons only
 * - miraclo_page_transition: 0 slide (default), 1 cube, 2 depth, 3 fade
 * - miraclo_gesture_double_tap: 0 nothing (default), 1 lock the screen
 * - miraclo_gesture_swipe_down: 0 notifications, 1 search, 2 Quick Settings
 *   (unset: follows Home search's own swipe switch)
 * - miraclo_gesture_swipe_up: 0 app drawer (default), 1 search
 * - miraclo_gesture_pinch: 0 nothing (default), 1 overview, 2 edit home screen
 * - miraclo_layout_lock: 1 = icons and widgets can't be moved (default 0)
 * - miraclo_folder_blur: 1 = an open folder blurs the wallpaper (default 0)
 * - miraclo_hidden_apps: packages left out of the app drawer and search, comma separated
 */
object MiraCLOLauncher {
    const val KEY_LABELS = "miraclo_home_labels"
    const val KEY_TRANSITION = "miraclo_page_transition"
    const val KEY_DOUBLE_TAP = "miraclo_gesture_double_tap"
    const val KEY_SWIPE_DOWN = "miraclo_gesture_swipe_down"
    const val KEY_SWIPE_UP = "miraclo_gesture_swipe_up"
    const val KEY_PINCH = "miraclo_gesture_pinch"
    const val KEY_LAYOUT_LOCK = "miraclo_layout_lock"
    const val KEY_FOLDER_BLUR = "miraclo_folder_blur"
    const val KEY_HIDDEN = "miraclo_hidden_apps"

    const val TRANSITION_SLIDE = 0
    const val TRANSITION_CUBE = 1
    const val TRANSITION_DEPTH = 2
    const val TRANSITION_FADE = 3

    const val SWIPE_NOTIFICATIONS = 0
    const val SWIPE_SEARCH = 1
    const val SWIPE_QUICK_SETTINGS = 2

    const val SWIPE_UP_DRAWER = 0
    const val SWIPE_UP_SEARCH = 1

    const val PINCH_NOTHING = 0
    const val PINCH_OVERVIEW = 1
    const val PINCH_EDIT = 2

    /** Handled by System UI (it holds the power permission); only senders with STATUS_BAR. */
    private const val ACTION_SLEEP = "com.android.systemui.miraclo.action.SLEEP"

    /** How deep (0..1) the wallpaper blur and zoom go while a folder is open. */
    private const val FOLDER_DEPTH = 0.4f

    @Volatile private var labels = true
    @Volatile private var transition = TRANSITION_SLIDE
    @Volatile private var doubleTap = 0
    @Volatile private var swipeUp = SWIPE_UP_DRAWER
    @Volatile private var pinch = PINCH_NOTHING
    @Volatile private var layoutLock = false
    @Volatile private var folderBlur = false
    @Volatile private var hidden: Set<String> = emptySet()
    private var appContext: Context? = null
    private var folderAnim: ValueAnimator? = null

    /** Called when page transitions change, so the pages can be put back flat. */
    @JvmStatic var onTransitionChanged: Runnable? = null

    private fun int(context: Context, key: String, def: Int): Int =
        runCatching { Settings.Secure.getInt(context.contentResolver, key, def) }.getOrDefault(def)

    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        read(app)
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val oldLabels = labels
                    val oldHidden = hidden
                    val oldTransition = transition
                    read(app)
                    if (oldTransition != transition) onTransitionChanged?.run()
                    if (oldLabels != labels || oldHidden != hidden) {
                        // Rebind the home screen and the app drawer with the new labels / apps.
                        runCatching {
                            LauncherAppState.getInstance(app).model.forceReload("MIRACLO launcher")
                        }
                    }
                }
            }
        runCatching {
            for (key in listOf(
                KEY_LABELS, KEY_TRANSITION, KEY_DOUBLE_TAP, KEY_SWIPE_UP, KEY_PINCH,
                KEY_LAYOUT_LOCK, KEY_FOLDER_BLUR, KEY_HIDDEN,
            )) {
                app.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer)
            }
        }
    }

    private fun read(context: Context) {
        labels = int(context, KEY_LABELS, 1) != 0
        transition = int(context, KEY_TRANSITION, TRANSITION_SLIDE).coerceIn(0, 3)
        doubleTap = int(context, KEY_DOUBLE_TAP, 0)
        swipeUp = int(context, KEY_SWIPE_UP, SWIPE_UP_DRAWER)
        pinch = int(context, KEY_PINCH, PINCH_NOTHING)
        layoutLock = int(context, KEY_LAYOUT_LOCK, 0) != 0
        folderBlur = int(context, KEY_FOLDER_BLUR, 0) != 0
        hidden =
            (runCatching { Settings.Secure.getString(context.contentResolver, KEY_HIDDEN) }
                    .getOrNull() ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
    }

    // ---- home screen ----------------------------------------------------------------------------

    /** Whether home screen icons show their names. */
    @JvmStatic
    fun homeLabels(context: Context): Boolean {
        init(context)
        return labels
    }

    /** Whether [packageName] is hidden from the app drawer and search. */
    @JvmStatic
    fun isHidden(context: Context, packageName: String?): Boolean {
        init(context)
        return packageName != null && hidden.contains(packageName)
    }

    /** Whether icons and widgets are locked in place. */
    @JvmStatic
    fun layoutLocked(context: Context): Boolean {
        init(context)
        return layoutLock
    }

    /** Long press while the layout is locked: the shortcuts menu of an icon, never a drag. */
    @JvmStatic
    fun onLockedLongPress(v: View, launcher: Launcher): Boolean {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (v is com.android.launcher3.BubbleTextView) {
            v.startLongPressAction(launcher.popupControllerForAppIcons)
        } else {
            Toast.makeText(launcher, "Home screen layout is locked", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // ---- page transitions -----------------------------------------------------------------------

    /**
     * Applies the page transition to a page's icons and widgets. [progress] is the page's scroll
     * progress (-1..1, positive while the page is left of the screen's centre, 0 when settled);
     * [distance] is the scroll distance from one page to the next.
     */
    @JvmStatic
    fun applyPageTransition(v: View, progress: Float, distance: Int) {
        val p = if (progress.isNaN()) 0f else progress.coerceIn(-1f, 1f)
        val a = abs(p)
        var rotation = 0f
        var scale = 1f
        var alpha = 1f
        var tx = 0f
        when (if (p == 0f) TRANSITION_SLIDE else transition) {
            TRANSITION_CUBE -> {
                v.cameraDistance = 6f * maxOf(v.width, 1)
                v.pivotX = if (p > 0f) v.width.toFloat() else 0f
                v.pivotY = v.height / 2f
                rotation = -70f * p
            }
            TRANSITION_DEPTH -> {
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
                scale = 1f - 0.18f * a
                alpha = 1f - 0.9f * a
            }
            TRANSITION_FADE -> {
                // Stay in place and cross-fade: undo the scroll.
                tx = p * distance
                alpha = 1f - a
            }
        }
        if (v.rotationY != rotation) v.rotationY = rotation
        if (v.scaleX != scale) {
            v.scaleX = scale
            v.scaleY = scale
        }
        if (v.alpha != alpha) v.alpha = alpha
        if (v.translationX != tx) v.translationX = tx
    }

    // ---- folders ----------------------------------------------------------------------------------

    /** Blurs (and gently zooms) the wallpaper while a folder is open. */
    @JvmStatic
    fun folderBlur(launcher: Launcher?, open: Boolean) {
        launcher ?: return
        init(launcher)
        val depth = runCatching { launcher.depthController?.widgetDepth }.getOrNull() ?: return
        val to = if (open && folderBlur) FOLDER_DEPTH else 0f
        val from = depth.value
        if (from == to) return
        folderAnim?.cancel()
        folderAnim =
            ValueAnimator.ofFloat(from, to).apply {
                duration = if (open) 320L else 220L
                addUpdateListener { depth.value = it.animatedValue as Float }
                start()
            }
    }

    // ---- gestures ---------------------------------------------------------------------------------

    /** Double tap on an empty part of the home screen. Returns true if it did something. */
    @JvmStatic
    fun onDoubleTap(launcher: Launcher): Boolean {
        init(launcher)
        if (doubleTap != 1 || !homeIdle(launcher)) return false
        runCatching {
            launcher.sendBroadcast(Intent(ACTION_SLEEP).setPackage("com.android.systemui"))
        }
        return true
    }

    /** What a swipe down from the middle of the home screen does. */
    @JvmStatic
    fun swipeDownAction(context: Context): Int {
        val v = int(context, KEY_SWIPE_DOWN, -1)
        if (v in 0..2) return v
        return if (MiraCLOHomeSearch.swipeDownOpensSearch(context)) SWIPE_SEARCH
        else SWIPE_NOTIFICATIONS
    }

    private fun homeIdle(launcher: Launcher): Boolean =
        launcher.isInState(LauncherState.NORMAL) &&
            AbstractFloatingView.getTopOpenView(launcher) == null

    /** Pinch in on the home screen (fed every touch on the workspace). */
    class Pinch(private val launcher: Launcher) {
        private var startSpan = 0f
        private var fired = false
        private val detector =
            ScaleGestureDetector(
                launcher,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                        startSpan = d.currentSpan
                        fired = false
                        return true
                    }

                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        if (!fired && startSpan > 0f && d.currentSpan < startSpan * 0.7f) {
                            fired = true
                            onPinchIn()
                        }
                        return false
                    }
                },
            )

        fun onTouchEvent(ev: MotionEvent) {
            runCatching { detector.onTouchEvent(ev) }
        }

        private fun onPinchIn() {
            init(launcher)
            if (!homeIdle(launcher)) return
            when (pinch) {
                PINCH_OVERVIEW -> launcher.stateManager.goToState(LauncherState.OVERVIEW)
                PINCH_EDIT -> launcher.stateManager.goToState(LauncherState.EDIT_MODE)
            }
        }
    }

    /** Swipe up on the home screen opens search instead of the app drawer, when chosen. */
    class SwipeUpController(private val launcher: Launcher) : TouchController {
        private val slop = ViewConfiguration.get(launcher).scaledTouchSlop * 2f
        private var downX = 0f
        private var downY = 0f
        private var tracking = false

        override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    init(launcher)
                    downX = ev.x
                    downY = ev.y
                    val dl = launcher.dragLayer
                    tracking =
                        swipeUp == SWIPE_UP_SEARCH &&
                            homeIdle(launcher) &&
                            dl != null &&
                            ev.y < dl.height - launcher.deviceProfile.insets.bottom - slop
                }
                MotionEvent.ACTION_MOVE ->
                    if (tracking) {
                        val dy = ev.y - downY
                        val dx = ev.x - downX
                        if (-dy > slop && -dy > abs(dx)) {
                            tracking = false
                            MiraCLOHomeSearch.open(launcher)
                            return true
                        }
                        if (dy > slop || abs(dx) > slop) tracking = false
                    }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> tracking = false
            }
            return false
        }

        override fun onControllerTouchEvent(ev: MotionEvent): Boolean = true
    }
}

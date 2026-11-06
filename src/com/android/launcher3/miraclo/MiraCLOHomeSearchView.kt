/*
 * SPDX-FileCopyrightText: MIRACLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.miraclo

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import kotlin.math.abs

/**
 * The home search entry, drawn over the home screen:
 * - pill: the page dots themselves. When the home screen is still they turn into a "Search" pill,
 *   centred between the last row of apps and the dock; while pages are swiped they turn back into
 *   dots that follow the swipe. Android's own page dots are hidden meanwhile.
 * - bar: a full search bar below the dock (as on ColorOS).
 * With themed icons on, both take the theme's colours. A tap opens the chosen search app
 * (Mira Studio › Home search › Search with), or Mira search.
 */
class MiraCLOHomeSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var style = MiraCLOHomeSearch.STYLE_OFF
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val handler = Handler(Looper.getMainLooper())
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val target = RectF()
    private val sans = Typeface.create("mira-sans", Typeface.NORMAL)

    /** 1 = the search pill, 0 = page dots. */
    private var morph = 1f
    private var morphAnim: ValueAnimator? = null
    private var scrolling = false
    private var lastScroll = Int.MIN_VALUE
    private var hidIndicator = false
    private var themed = false
    private var lastKey = 0

    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    private val settle = Runnable {
        scrolling = false
        animateMorph(1f)
    }

    private val preDraw =
        ViewTreeObserver.OnPreDrawListener {
            val l = launcher()
            if (l != null && style == MiraCLOHomeSearch.STYLE_PILL) {
                // Android's page dots: ours take their place on the home screen.
                val pi = l.findViewById<View>(R.id.page_indicator)
                if (pi != null && l.isInState(LauncherState.NORMAL)) {
                    if (pi.alpha != 0f) pi.alpha = 0f
                    hidIndicator = true
                }
                val ws = l.workspace
                val sx = ws?.scrollX ?: 0
                if (lastScroll != Int.MIN_VALUE && sx != lastScroll) {
                    if (!scrolling) {
                        scrolling = true
                        animateMorph(0f)
                    }
                    handler.removeCallbacks(settle)
                    handler.postDelayed(settle, 420)
                }
                lastScroll = sx
            } else if (hidIndicator && l != null) {
                l.findViewById<View>(R.id.page_indicator)?.alpha = 1f
                hidIndicator = false
            }
            val key = stateKey()
            if (key != lastKey) {
                lastKey = key
                invalidate()
            }
            true
        }

    /** Changes when anything we draw moves, so we only redraw then. */
    private fun stateKey(): Int {
        val l = launcher() ?: return 0
        val ws = l.workspace
        return java.util.Objects.hash(dockAlpha(), dockIconsTop(), ws?.scrollX, ws?.currentPage, ws?.childCount)
    }

    private fun animateMorph(to: Float) {
        if (morph == to && morphAnim == null) return
        morphAnim?.cancel()
        morphAnim =
            ValueAnimator.ofFloat(morph, to).apply {
                duration = if (to > morph) 320 else 180
                interpolator = ease
                addUpdateListener {
                    morph = it.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            morphAnim = null
                        }
                    }
                )
                start()
            }
    }

    private fun refresh() {
        style = MiraCLOHomeSearch.style(context)
        themed = MiraCLOHomeSearch.themedIcons(context)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        for (key in listOf(MiraCLOHomeSearch.KEY_STYLE, MiraCLOHomeSearch.KEY_SCOPES, MiraCLOHomeSearch.KEY_APP)) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, observer)
        }
        viewTreeObserver.addOnPreDrawListener(preDraw)
        refresh()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(observer)
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        handler.removeCallbacks(settle)
        morphAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Themed icons may have been switched in Mira Studio meanwhile.
        if (visibility == VISIBLE) refresh()
    }

    private fun launcher(): Launcher? = runCatching { Launcher.getLauncher(context) }.getOrNull()

    private fun dockAlpha(): Float {
        val l = launcher() ?: return 0f
        // Only on the home screen itself: not in the app drawer, overview or while dragging.
        if (!l.isInState(LauncherState.NORMAL)) return 0f
        val hotseat = l.hotseat ?: return 0f
        if (hotseat.visibility != VISIBLE) return 0f
        return hotseat.alpha * hotseat.shortcutsAndWidgets.alpha
    }

    private val loc = IntArray(2)
    private val mine = IntArray(2)

    private fun yIn(v: View): Float {
        v.getLocationInWindow(loc)
        getLocationInWindow(mine)
        return (loc[1] - mine[1]).toFloat()
    }

    /** Top of the dock icons, in this view's coordinates. */
    private fun dockIconsTop(): Float {
        val icons = launcher()?.hotseat?.shortcutsAndWidgets ?: return Float.NaN
        return yIn(icons) + icons.paddingTop
    }

    private fun dockIconsBottom(): Float {
        val icons = launcher()?.hotseat?.shortcutsAndWidgets ?: return Float.NaN
        return yIn(icons) + icons.height - icons.paddingBottom
    }

    private val box = android.graphics.Rect()

    /** Top of the dock's icon artwork (not the cells), in this view's coordinates. */
    private fun dockArtTop(): Float {
        val icons = launcher()?.hotseat?.shortcutsAndWidgets ?: return Float.NaN
        var top = Float.NaN
        for (i in 0 until icons.childCount) {
            val c = icons.getChildAt(i)
            if (c.visibility != VISIBLE) continue
            val y =
                if (c is com.android.launcher3.BubbleTextView) {
                    c.getIconBounds(box)
                    yIn(c) + box.top
                } else yIn(c) + c.paddingTop
            if (top.isNaN() || y < top) top = y
        }
        return if (top.isNaN()) dockIconsTop() else top
    }

    private val cellBox = android.graphics.Rect()

    /**
     * Bottom of an app label in the grid's last row: the same on every page (the grid is the
     * same), so the pill never moves while swiping between pages.
     */
    private fun gridBottom(): Float {
        val ws = launcher()?.workspace ?: return Float.NaN
        val page = ws.getChildAt(ws.currentPage.coerceIn(0, maxOf(0, ws.childCount - 1)))
            as? com.android.launcher3.CellLayout ?: return Float.NaN
        if (page.countY <= 0) return Float.NaN
        page.cellToRect(0, page.countY - 1, 1, 1, cellBox)
        // Where a label ends inside its cell, measured on any app icon of any page.
        var labelEnd = Float.NaN
        loop@ for (p in 0 until ws.childCount) {
            val grid = (ws.getChildAt(p) as? com.android.launcher3.CellLayout)?.shortcutsAndWidgets
                ?: continue
            for (i in 0 until grid.childCount) {
                val c = grid.getChildAt(i)
                if (c is com.android.launcher3.BubbleTextView && !c.text.isNullOrEmpty() &&
                    android.graphics.Color.alpha(c.currentTextColor) != 0) {
                    labelEnd = c.baseline + c.paint.fontMetrics.descent
                    break@loop
                }
            }
        }
        if (labelEnd.isNaN()) labelEnd = cellBox.height().toFloat()
        return yIn(page) + cellBox.top + labelEnd
    }

    private fun colours(): Triple<Int, Int, Int> {
        if (!themed) return Triple(Color.argb(115, 30, 34, 44), Color.WHITE, Color.WHITE)
        val dark =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        // The same colours as themed icons.
        val bg = context.getColor(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100)
        val fg = context.getColor(if (dark) android.R.color.system_accent1_100 else android.R.color.system_accent1_700)
        return Triple(Color.argb(235, Color.red(bg), Color.green(bg), Color.blue(bg)), fg, fg)
    }

    private fun computeTarget(): Boolean {
        if (style == MiraCLOHomeSearch.STYLE_OFF) return false
        val iconsTop = dockIconsTop()
        if (iconsTop.isNaN()) return false
        if (style == MiraCLOHomeSearch.STYLE_PILL) {
            ink.textSize = dp(13f)
            ink.typeface = Typeface.create(sans, 600, false)
            val w = dp(14f) + dp(16f) + dp(6f) + ink.measureText(LABEL_PILL) + dp(16f)
            val h = dp(32f)
            // Centred between the last row of apps and the dock.
            val gb = gridBottom()
            val art = dockArtTop().let { if (it.isNaN()) iconsTop else it }
            val cy =
                if (gb.isNaN() || gb >= art) art - dp(26f)
                else (gb + art) / 2f
            target.set((width - w) / 2f, cy - h / 2f, (width + w) / 2f, cy + h / 2f)
        } else {
            val h = dp(48f)
            val top = dockIconsBottom() + dp(10f)
            target.set(dp(24f), top, width - dp(24f), top + h)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val a = dockAlpha()
        if (a <= 0.01f || !computeTarget()) return
        val (bg, text, icon) = colours()
        if (style == MiraCLOHomeSearch.STYLE_PILL) drawPill(canvas, a, bg, text, icon)
        else drawBar(canvas, a, bg, text, icon)
    }

    private fun drawPill(canvas: Canvas, a: Float, bg: Int, text: Int, icon: Int) {
        val l = launcher() ?: return
        val ws = l.workspace
        val pages = ws?.childCount ?: 1
        val t = morph
        // The dots' strip, and the pill: the shape grows from one into the other.
        val dotGap = dp(13f)
        val dotsW = maxOf(1, pages) * dotGap
        val dotsH = dp(14f)
        val w = dotsW + (target.width() - dotsW) * t
        val h = dotsH + (target.height() - dotsH) * t
        val cx = target.centerX()
        val cy = target.centerY()
        val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        fill.color = bg
        fill.alpha = (Color.alpha(bg) * a * t).toInt()
        canvas.drawRoundRect(r, h / 2f, h / 2f, fill)
        // Page dots, following the swipe.
        if (t < 0.99f && pages > 1) {
            val pos = pagePosition()
            val dotsA = a * (1f - t)
            val start = cx - (pages - 1) * dotGap / 2f
            for (i in 0 until pages) {
                val k = (1f - abs(pos - i)).coerceIn(0f, 1f)
                dot.color = if (themed) text else Color.WHITE
                dot.alpha = ((90 + 165 * k) * dotsA).toInt()
                canvas.drawCircle(start + i * dotGap, cy, dp(3f) + dp(0.8f) * k, dot)
            }
        }
        // Magnifier and label.
        if (t > 0.01f) {
            val alpha = (255 * a * ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)).toInt()
            ink.color = text
            ink.alpha = alpha
            glyph.color = icon
            glyph.alpha = alpha
            val iconSize = dp(16f)
            val iconLeft = r.left + dp(14f)
            drawMagnifier(canvas, iconLeft, cy, iconSize)
            ink.textSize = dp(13f)
            ink.typeface = Typeface.create(sans, 600, false)
            val fm = ink.fontMetrics
            canvas.save()
            canvas.clipRect(r)
            canvas.drawText(LABEL_PILL, iconLeft + iconSize + dp(6f), cy - (fm.ascent + fm.descent) / 2f, ink)
            canvas.restore()
        }
    }

    /** The page shown, with the fraction of a swipe in progress (0 = first page). */
    private fun pagePosition(): Float {
        val ws = launcher()?.workspace ?: return 0f
        val n = ws.childCount
        if (n < 2) return 0f
        val c0 = ws.getChildAt(0) ?: return 0f
        val c1 = ws.getChildAt(1) ?: return ws.currentPage.toFloat()
        val step = (c1.left - c0.left).toFloat()
        if (step == 0f) return ws.currentPage.toFloat()
        val p = (ws.scrollX - (c0.left - ws.paddingLeft)) / step
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        return (if (rtl) -p else p).coerceIn(0f, (n - 1).toFloat())
    }

    private fun drawBar(canvas: Canvas, a: Float, bg: Int, text: Int, icon: Int) {
        val r = target.height() / 2f
        fill.color = bg
        fill.alpha = (Color.alpha(bg) * a).toInt()
        canvas.drawRoundRect(target, r, r, fill)
        val alpha = (a * 255).toInt().coerceIn(0, 255)
        ink.color = text
        ink.alpha = alpha
        glyph.color = icon
        glyph.alpha = alpha
        val iconSize = dp(22f)
        val iconLeft = target.left + dp(18f)
        drawMagnifier(canvas, iconLeft, target.centerY(), iconSize)
        ink.textSize = dp(15f)
        ink.typeface = Typeface.create(sans, 400, false)
        val label = MiraCLOHomeSearch.barLabel(context)
        val fm = ink.fontMetrics
        canvas.drawText(label, iconLeft + iconSize + dp(12f), target.centerY() - (fm.ascent + fm.descent) / 2f, ink)
    }

    private fun drawMagnifier(canvas: Canvas, left: Float, cy: Float, size: Float) {
        glyph.strokeWidth = size * 0.12f
        val rr = size * 0.30f
        val cx = left + size * 0.42f
        val ccy = cy - size * 0.08f
        canvas.drawCircle(cx, ccy, rr, glyph)
        val d = rr * 0.72f
        canvas.drawLine(cx + d, ccy + d, cx + size * 0.5f, ccy + size * 0.5f, glyph)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dockAlpha() < 0.5f || !computeTarget()) return false
        // The dots only take taps once they are the pill again.
        if (style == MiraCLOHomeSearch.STYLE_PILL && morph < 0.6f) return false
        val hit = RectF(target).apply { inset(-dp(8f), -dp(8f)) }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !hit.contains(event.x, event.y)) {
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && hit.contains(event.x, event.y)) {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            MiraCLOHomeSearch.open(context)
        }
        return true
    }

    private companion object {
        const val LABEL_PILL = "Search"
    }
}

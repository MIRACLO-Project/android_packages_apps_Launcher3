/*
 * SPDX-FileCopyrightText: MIRACLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.miraclo

import android.content.Context
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
import com.android.launcher3.Launcher

/**
 * The home search entry, drawn over the home screen:
 * - pill: a small "Search" pill just above the dock (as on iOS)
 * - bar: a full search bar below the dock (as on ColorOS)
 * It follows the dock's position and fade, and only takes touches on itself.
 */
class MiraCLOHomeSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var style = MiraCLOHomeSearch.STYLE_OFF
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 30, 34, 44) }
    private val ink =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    private val glyph =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    private val target = RectF()
    private var lastAlpha = -1f
    private var lastTop = Float.NaN

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    private val preDraw =
        ViewTreeObserver.OnPreDrawListener {
            val a = dockAlpha()
            val t = dockIconsTop()
            if (a != lastAlpha || t != lastTop) {
                lastAlpha = a
                lastTop = t
                invalidate()
            }
            true
        }

    private fun refresh() {
        style = MiraCLOHomeSearch.style(context)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        for (key in listOf(MiraCLOHomeSearch.KEY_STYLE, MiraCLOHomeSearch.KEY_SCOPES)) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, observer)
        }
        viewTreeObserver.addOnPreDrawListener(preDraw)
        refresh()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(observer)
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    private fun launcher(): Launcher? = runCatching { Launcher.getLauncher(context) }.getOrNull()

    private fun dockAlpha(): Float {
        val l = launcher() ?: return 0f
        // Only on the home screen itself: not in the app drawer, overview or while dragging.
        if (!l.isInState(com.android.launcher3.LauncherState.NORMAL)) return 0f
        val hotseat = l.hotseat ?: return 0f
        if (hotseat.visibility != VISIBLE) return 0f
        return hotseat.alpha * hotseat.shortcutsAndWidgets.alpha
    }

    /** Top and bottom of the dock icons, in this view's coordinates. */
    private fun dockIconsTop(): Float {
        val hotseat = launcher()?.hotseat ?: return Float.NaN
        val icons = hotseat.shortcutsAndWidgets
        return hotseat.top + hotseat.translationY + icons.top + icons.translationY - top
    }

    private fun dockIconsBottom(): Float {
        val hotseat = launcher()?.hotseat ?: return Float.NaN
        val icons = hotseat.shortcutsAndWidgets
        return hotseat.top + hotseat.translationY + icons.bottom + icons.translationY - top
    }

    private fun computeTarget(): Boolean {
        if (style == MiraCLOHomeSearch.STYLE_OFF) return false
        val iconsTop = dockIconsTop()
        if (iconsTop.isNaN()) return false
        if (style == MiraCLOHomeSearch.STYLE_PILL) {
            ink.textSize = dp(13f)
            val w = dp(16f) + dp(18f) + dp(6f) + ink.measureText(LABEL_PILL) + dp(16f)
            val h = dp(32f)
            val bottom = iconsTop - dp(14f)
            target.set((width - w) / 2f, bottom - h, (width + w) / 2f, bottom)
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
        val alpha = (a * 255).toInt().coerceIn(0, 255)
        val r = target.height() / 2f
        fill.alpha = (115 * a).toInt()
        canvas.drawRoundRect(target, r, r, fill)
        ink.alpha = alpha
        glyph.alpha = alpha
        val pill = style == MiraCLOHomeSearch.STYLE_PILL
        val iconSize = if (pill) dp(18f) else dp(22f)
        val iconLeft = target.left + if (pill) dp(16f) else dp(18f)
        drawMagnifier(canvas, iconLeft, target.centerY(), iconSize)
        ink.textSize = if (pill) dp(13f) else dp(15f)
        ink.typeface =
            Typeface.create(if (pill) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        val label = if (pill) LABEL_PILL else LABEL_BAR
        val textX = iconLeft + iconSize + if (pill) dp(6f) else dp(12f)
        val fm = ink.fontMetrics
        canvas.drawText(label, textX, target.centerY() - (fm.ascent + fm.descent) / 2f, ink)
    }

    private fun drawMagnifier(canvas: Canvas, left: Float, cy: Float, size: Float) {
        glyph.strokeWidth = size * 0.11f
        val rr = size * 0.30f
        val cx = left + size * 0.42f
        val ccy = cy - size * 0.08f
        canvas.drawCircle(cx, ccy, rr, glyph)
        val d = rr * 0.72f
        canvas.drawLine(cx + d, ccy + d, cx + size * 0.5f, ccy + size * 0.5f, glyph)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dockAlpha() < 0.5f || !computeTarget()) return false
        val hit = RectF(target).apply { inset(-dp(6f), -dp(6f)) }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !hit.contains(event.x, event.y)) {
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && hit.contains(event.x, event.y)) {
            MiraCLOHomeSearch.open(context)
        }
        return true
    }

    private companion object {
        const val LABEL_PILL = "Search"
        const val LABEL_BAR = "Search apps, contacts, settings, web"
    }
}

/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep.views

import android.content.SharedPreferences
import kotlin.math.min
import kotlin.math.pow

/**
 * MiraCLO: the ColorOS ("stacked") recents style.
 *
 * The focused task keeps its normal place and size, so the swipe-up and quick-switch animations
 * are untouched. Older tasks are tucked behind it: each one only shows a strip of its edge, the
 * strips get thinner the deeper the task is, and deeper tasks are slightly smaller. Newer tasks
 * (only visible after scrolling into older ones) keep the normal spacing and are drawn on top.
 *
 * Proportions were measured from ColorOS: the first older card shows ~23% of its width, the next
 * ones a few percent each, and each card is ~4% smaller than the one in front of it.
 */
object MiraCLORecentsStyle {
    /** Launcher setting (Home settings > Recents style); ColorOS by default. */
    const val KEY_RECENTS_STYLE = "pref_recents_style"
    const val STYLE_DEFAULT = "default"
    const val STYLE_COLOROS = "coloros"

    /** Fraction of the card width the first older card shows. */
    private const val FIRST_EXPOSED = 0.23f
    /** Extra fraction shown by the second older card; each deeper card shows [DECAY] of that. */
    private const val NEXT_EXPOSED = 0.035f
    private const val DECAY = 0.55f
    /** Scale lost per step into the stack, and the smallest scale. */
    private const val SCALE_STEP = 0.04f
    private const val MIN_SCALE = 0.88f

    @JvmStatic
    fun isStacked(prefs: SharedPreferences): Boolean =
        prefs.getString(KEY_RECENTS_STYLE, STYLE_COLOROS) == STYLE_COLOROS

    /**
     * How much of the focused card's width separates its edge from the edge of a card [depth]
     * positions into the stack (0 = focused card, 1 = first older card, may be fractional while
     * scrolling).
     */
    @JvmStatic
    fun exposedFraction(depth: Float): Float {
        if (depth <= 0f) return 0f
        if (depth <= 1f) return FIRST_EXPOSED * depth
        val extra = depth - 1f
        // Geometric series, continuous in [depth]: NEXT * (1 - DECAY^extra) / (1 - DECAY)
        return FIRST_EXPOSED + NEXT_EXPOSED * (1f - DECAY.pow(extra)) / (1f - DECAY)
    }

    /** Scale of a card [depth] positions into the stack. */
    @JvmStatic
    fun scale(depth: Float): Float {
        if (depth <= 0f) return 1f
        return (1f - SCALE_STEP * min(depth, 3f)).coerceAtLeast(MIN_SCALE)
    }

    /**
     * Translation (towards the focused card, in px) that moves a card from its normal carousel
     * position into the stack.
     *
     * @param depth positions into the stack (> 0)
     * @param cardSize card width in px
     * @param pageSize distance between two cards in the normal carousel (card + spacing)
     */
    @JvmStatic
    fun translationTowardsFocus(depth: Float, cardSize: Float, pageSize: Float): Float {
        if (depth <= 0f) return 0f
        val s = scale(depth)
        // Centre offset that leaves only the exposed strip of the (scaled) card visible.
        val stackedOffset = exposedFraction(depth) * cardSize + (1f - s) * cardSize / 2f
        val normalOffset = depth * pageSize
        return normalOffset - stackedOffset
    }
}

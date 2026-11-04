/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.miraclo

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * MiraCLO home search (Mira Studio > Home screen & icons > Home search), stored in
 * Settings.Secure so Settings can change it:
 * - miraclo_home_search: 0 off, 1 small pill above the dock, 2 search bar below the dock
 * - miraclo_home_search_scopes: what to search ("apps,contacts,settings,files,messages,web")
 * - miraclo_home_search_swipe: 1 to open search with a swipe down on the home screen
 * - miraclo_themed_icons_drawer: 1 to theme the app drawer icons too (themed icons scope)
 * - miraclo_icon_scale: home screen, dock and drawer icon size in percent (80..120, default 100)
 */
object MiraCLOHomeSearch {
    const val KEY_STYLE = "miraclo_home_search"
    const val KEY_SCOPES = "miraclo_home_search_scopes"
    const val KEY_SWIPE = "miraclo_home_search_swipe"
    const val KEY_THEMED_DRAWER = "miraclo_themed_icons_drawer"
    const val KEY_ICON_SCALE = "miraclo_icon_scale"

    const val STYLE_OFF = 0
    const val STYLE_PILL = 1
    const val STYLE_BAR = 2

    const val DEFAULT_SCOPES = "apps,contacts,settings,files,web"

    private fun int(context: Context, key: String, def: Int): Int =
        runCatching { Settings.Secure.getInt(context.contentResolver, key, def) }.getOrDefault(def)

    @JvmStatic fun style(context: Context): Int = int(context, KEY_STYLE, STYLE_OFF)

    @JvmStatic
    fun swipeDownOpensSearch(context: Context): Boolean =
        style(context) != STYLE_OFF && int(context, KEY_SWIPE, 0) != 0

    @JvmStatic
    fun scopes(context: Context): Set<String> =
        (runCatching { Settings.Secure.getString(context.contentResolver, KEY_SCOPES) }.getOrNull()
                ?: DEFAULT_SCOPES)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Whether themed icons also apply in the app drawer (default: home screen only, as stock). */
    @JvmStatic
    fun themedIconsInDrawer(context: Context): Boolean {
        init(context)
        return themedDrawer
    }

    // Cached copies, kept fresh by an observer (read while laying out and binding icons).
    @Volatile private var themedDrawer = false
    @Volatile private var barBelow = false
    @Volatile private var scale = 1f
    private var appContext: Context? = null

    /** Called when the icon size or the search bar position changes (re-lays out the grid). */
    @JvmStatic var onLayoutChanged: Runnable? = null

    /** Whether our search bar sits below the dock, so the dock keeps room for it. */
    @JvmStatic fun barBelowDock(): Boolean = barBelow

    /** Icon size multiplier (0.8..1.2). */
    @JvmStatic fun iconScale(): Float = scale

    /** Scales an icon size in px. */
    @JvmStatic fun scaled(px: Int): Int = if (scale == 1f) px else Math.round(px * scale)

    /** Scales an icon size in dp, returning a new array. */
    @JvmStatic
    fun scaled(dps: FloatArray): FloatArray =
        if (scale == 1f) dps else FloatArray(dps.size) { dps[it] * scale }

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
                    val oldDrawer = themedDrawer
                    val oldBar = barBelow
                    val oldScale = scale
                    read(app)
                    if (oldBar != barBelow || oldScale != scale) onLayoutChanged?.run()
                    if (oldDrawer != themedDrawer || oldScale != scale) {
                        // Rebind every icon so the drawer picks up (or drops) themed icons.
                        runCatching {
                            com.android.launcher3.LauncherAppState.getInstance(app).model
                                .forceReload("MiraCLO icon settings")
                        }
                    }
                }
            }
        runCatching {
            for (key in listOf(KEY_STYLE, KEY_THEMED_DRAWER, KEY_ICON_SCALE)) {
                app.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer)
            }
        }
    }

    private fun read(context: Context) {
        themedDrawer = int(context, KEY_THEMED_DRAWER, 0) != 0
        barBelow = int(context, KEY_STYLE, STYLE_OFF) == STYLE_BAR
        scale = int(context, KEY_ICON_SCALE, 100).coerceIn(80, 120) / 100f
    }

    @JvmStatic
    fun open(context: Context) {
        context.startActivity(
            Intent(context, MiraCLOSearchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }
}

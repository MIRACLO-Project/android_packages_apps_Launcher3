/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.miraclo

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.launcher3.LauncherAppState
import com.android.launcher3.graphics.ShapeDelegate
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.GraphicsUtils.generateIconShape
import com.android.launcher3.icons.IconShape
import com.android.launcher3.shapes.ShapesProvider

/**
 * The app drawer's own icon shape (Mira Studio › Home screen & icons, App drawer tab), stored in
 * Settings.Secure "miraclo_icon_shape_drawer" as a shape key. Empty: the same shape as the home
 * screen.
 */
object MiraCLOShapes {
    const val KEY_DRAWER = "miraclo_icon_shape_drawer"

    private var cachedKey: String? = null
    private var cachedSize = 0
    private var cached: IconShape? = null
    @Volatile private var observing = false

    /** The drawer's shape, or null to use the home screen's. Main thread. */
    @JvmStatic
    fun drawerShape(context: Context): IconShape? {
        watch(context)
        val key =
            runCatching { Settings.Secure.getString(context.contentResolver, KEY_DRAWER) }
                .getOrNull()
        if (key.isNullOrEmpty()) return null
        val size = ThemeManager.INSTANCE.get(context).iconShapeData.value.pathSize
        if (size <= 0) return null
        if (key == cachedKey && size == cachedSize) return cached
        val model = ShapesProvider.iconShapes.firstOrNull { it.key == key } ?: return null
        val delegate = ShapeDelegate.pickBestShape(model.pathString)
        cached = generateIconShape(size, delegate.getPath(size.toFloat()))
        cachedKey = key
        cachedSize = size
        return cached
    }

    @Synchronized
    private fun watch(context: Context) {
        if (observing) return
        observing = true
        val app = context.applicationContext ?: context
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_DRAWER),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        // Rebind the app drawer so every icon takes the new shape.
                        runCatching {
                            LauncherAppState.getInstance(app).model.forceReload("MiraCLO drawer shape")
                        }
                    }
                },
            )
        }
    }
}

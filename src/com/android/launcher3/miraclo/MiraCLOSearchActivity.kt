/*
 * SPDX-FileCopyrightText: MIRACLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.miraclo

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * MIRACLO global search, opened from the home search pill or bar (or a swipe down on the home
 * screen): one search across apps, contacts, settings, files, messages and the web, each of which
 * can be turned off in Mira Studio.
 */
class MiraCLOSearchActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var results: LinearLayout
    private var scopes: Set<String> = emptySet()
    private val density by lazy { resources.displayMetrics.density }

    private fun dp(v: Float) = (v * density).toInt()

    private data class Row(val title: String, val sub: String, val icon: Drawable?, val glyph: String?, val open: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scopes = MiraCLOHomeSearch.scopes(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = dp(30f) }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.argb(184, 10, 12, 18)))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(48f), dp(16f), 0)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Color.argb(36, 255, 255, 255), dp(24f).toFloat())
            setPadding(dp(16f), 0, dp(16f), 0)
        }
        field.addView(
            TextView(this).apply {
                text = "⌕"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        input = EditText(this).apply {
            hint = "Search"
            setHintTextColor(Color.argb(150, 255, 255, 255))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10f), 0, 0, 0)
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    firstResult?.invoke()
                    true
                } else false
            }
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) = search(s?.toString().orEmpty())
                }
            )
        }
        field.addView(input, LinearLayout.LayoutParams(0, dp(48f), 1f))
        top.addView(field, LinearLayout.LayoutParams(0, dp(48f), 1f))
        top.addView(
            TextView(this).apply {
                text = "Cancel"
                setTextColor(Color.parseColor("#A8C7FA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(14f), dp(10f), dp(4f), dp(10f))
                setOnClickListener { finish() }
            }
        )
        root.addView(top)
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(24f))
        }
        root.addView(
            ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        askPermissions()
        input.requestFocus()
        input.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun askPermissions() {
        val wanted = mutableListOf<String>()
        if ("contacts" in scopes) wanted += Manifest.permission.READ_CONTACTS
        if ("files" in scopes) {
            wanted += Manifest.permission.READ_MEDIA_IMAGES
            wanted += Manifest.permission.READ_MEDIA_VIDEO
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun round(color: Int, radius: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private var firstResult: (() -> Unit)? = null

    private fun search(raw: String) {
        val q = raw.trim()
        results.removeAllViews()
        firstResult = null
        if (q.isEmpty()) return
        if ("apps" in scopes) showApps(q)
        if ("settings" in scopes) section("SETTINGS", settingsRows(q))
        if ("contacts" in scopes) section("CONTACTS", contactRows(q))
        if ("files" in scopes) section("FILES", fileRows(q))
        if ("messages" in scopes) section("MESSAGES", messageRows(q))
        if ("web" in scopes) {
            section(
                "WEB",
                listOf(
                    Row(q, "Search the web", null, "⌕") {
                        open(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
                    }
                ),
            )
        }
    }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.argb(153, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(6f), dp(14f), dp(6f), dp(8f))
        }

    private fun showApps(q: String) {
        val pm = packageManager
        val apps =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { !MiraCLOLauncher.isHidden(this, it.activityInfo.packageName) }
                .filter { it.loadLabel(pm).toString().contains(q, ignoreCase = true) }
                .sortedBy { val l = it.loadLabel(pm).toString(); if (l.startsWith(q, true)) 0 else 1 }
                .take(8)
        if (apps.isEmpty()) return
        results.addView(label("APPS"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        apps.forEachIndexed { i, info ->
            val launch = {
                open(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                )
            }
            if (i == 0) firstResult = launch
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4f), 0, dp(4f), 0)
                setOnClickListener { launch() }
            }
            cell.addView(
                ImageView(this).apply { setImageDrawable(info.loadIcon(pm)) },
                LinearLayout.LayoutParams(dp(52f), dp(52f)),
            )
            cell.addView(
                TextView(this).apply {
                    text = info.loadLabel(pm)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6f), 0, 0)
                },
                LinearLayout.LayoutParams(dp(72f), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            row.addView(cell)
        }
        results.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun section(title: String, rows: List<Row>) {
        if (rows.isEmpty()) return
        if (firstResult == null) firstResult = rows.first().open
        results.addView(label(title))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.argb(26, 255, 255, 255), dp(20f).toFloat())
            clipToOutline = true
        }
        rows.forEach { r ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                setOnClickListener { r.open() }
            }
            val bubble = FrameLayout(this).apply {
                background = round(Color.argb(36, 255, 255, 255), dp(18f).toFloat())
            }
            if (r.icon != null) {
                bubble.addView(ImageView(this).apply { setImageDrawable(r.icon) }, FrameLayout.LayoutParams(dp(36f), dp(36f)))
            } else {
                bubble.addView(
                    TextView(this).apply {
                        text = r.glyph ?: r.title.take(1).uppercase()
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        gravity = Gravity.CENTER
                    },
                    FrameLayout.LayoutParams(dp(36f), dp(36f)),
                )
            }
            line.addView(bubble, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), 0, 0, 0)
            }
            texts.addView(TextView(this).apply {
                text = r.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (r.sub.isNotEmpty()) {
                texts.addView(TextView(this).apply {
                    text = r.sub
                    setTextColor(Color.argb(153, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(line)
        }
        results.addView(card)
    }

    /** Settings pages that can be found by name (and a few other words). */
    private val settingsIndex =
        listOf(
            Triple("Wi‑Fi", "Network & internet", Settings.ACTION_WIFI_SETTINGS) to "wifi wlan internet network",
            Triple("Wi‑Fi hotspot", "Network & internet › Hotspot", "android.settings.TETHER_SETTINGS") to "hotspot tethering",
            Triple("Mobile network", "Network & internet › SIMs", Settings.ACTION_NETWORK_OPERATOR_SETTINGS) to "sim mobile data 5g volte",
            Triple("Bluetooth", "Connected devices", Settings.ACTION_BLUETOOTH_SETTINGS) to "bluetooth pair headphones",
            Triple("Display", "Display", Settings.ACTION_DISPLAY_SETTINGS) to "brightness dark theme screen timeout font",
            Triple("Wallpaper & style", "Wallpaper", "android.settings.WALLPAPER_SETTINGS") to "wallpaper colours colors theme",
            Triple("Sound & vibration", "Sound", Settings.ACTION_SOUND_SETTINGS) to "volume ringtone vibration",
            Triple("Notifications", "Notifications", Settings.ACTION_NOTIFICATION_SETTINGS) to "notification",
            Triple("Apps", "Apps", Settings.ACTION_APPLICATION_SETTINGS) to "apps default applications",
            Triple("Battery", "Battery", Settings.ACTION_BATTERY_SAVER_SETTINGS) to "battery saver power",
            Triple("Storage", "Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS) to "storage space",
            Triple("Security & privacy", "Security", Settings.ACTION_SECURITY_SETTINGS) to "lock screen password fingerprint",
            Triple("Location", "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS) to "gps location",
            Triple("Accessibility", "Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS) to "accessibility talkback",
            Triple("Date & time", "System", Settings.ACTION_DATE_SETTINGS) to "date time clock timezone",
            Triple("Languages", "System", Settings.ACTION_LOCALE_SETTINGS) to "language keyboard",
            Triple("Developer options", "System", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) to "developer usb debugging adb",
            Triple("About phone", "About", Settings.ACTION_DEVICE_INFO_SETTINGS) to "about version build miraclo",
            Triple("Do Not Disturb", "Modes", "android.settings.ZEN_MODE_SETTINGS") to "dnd silent modes",
            Triple("NFC", "Connected devices", Settings.ACTION_NFC_SETTINGS) to "nfc tap pay",
            Triple("Airplane mode", "Network & internet", Settings.ACTION_AIRPLANE_MODE_SETTINGS) to "airplane flight",
        )

    private fun settingsRows(q: String): List<Row> =
        settingsIndex
            .filter { (t, words) -> t.first.contains(q, true) || words.contains(q.lowercase()) }
            .take(5)
            .map { (t, _) -> Row(t.first, t.second, null, "⚙") { open(Intent(t.third)) } }

    private fun contactRows(q: String): List<Row> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
                contentResolver
                    .query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        val out = mutableListOf<Row>()
                        while (c.moveToNext() && out.size < 5) {
                            val id = c.getLong(0)
                            val name = c.getString(1) ?: continue
                            out += Row(name, "Contact", null, null) {
                                open(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)))
                            }
                        }
                        out
                    }
            }
            .getOrNull()
            .orEmpty()
    }

    private fun fileRows(q: String): List<Row> =
        runCatching {
                val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                contentResolver
                    .query(
                        uri,
                        arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE),
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$q%"),
                        "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
                    )
                    ?.use { c ->
                        val out = mutableListOf<Row>()
                        while (c.moveToNext() && out.size < 5) {
                            val id = c.getLong(0)
                            val name = c.getString(1) ?: continue
                            val mime = c.getString(2)
                            out += Row(name, mime ?: "File", null, "▤") {
                                open(Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(uri, id), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                        }
                        out
                    }
            }
            .getOrNull()
            .orEmpty()

    private fun messageRows(q: String): List<Row> =
        runCatching {
                contentResolver
                    .query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                        "${Telephony.Sms.BODY} LIKE ?",
                        arrayOf("%$q%"),
                        "${Telephony.Sms.DATE} DESC",
                    )
                    ?.use { c ->
                        val out = mutableListOf<Row>()
                        while (c.moveToNext() && out.size < 5) {
                            val thread = c.getLong(0)
                            out += Row(c.getString(1) ?: "", c.getString(2) ?: "", null, "✉") {
                                open(Intent(Intent.ACTION_VIEW, Uri.parse("content://mms-sms/conversations/$thread")))
                            }
                        }
                        out
                    }
            }
            .getOrNull()
            .orEmpty()

    private fun open(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}

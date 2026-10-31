/*
 * Copyright (C) 2026 MiraCLO
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.icons;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.model.data.ItemInfoWithIcon;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiraCLO icon packs (Launcher settings > Icon packs). Works with any icon pack that ships an
 * appfilter (ADW / Nova / Lawnchair format). The home screen and the app drawer can use
 * different packs. Icons the pack covers are taken from it; calendar icons follow the date.
 * Apps the pack does not cover keep their normal icon.
 */
public final class MiraCLOIconPacks {

    private static final String TAG = "MiraCLOIconPacks";

    public static final String KEY_SAME = "pref_icon_pack_same";
    public static final String KEY_HOME = "pref_icon_pack_home";
    public static final String KEY_DRAWER = "pref_icon_pack_drawer";

    /** Intent actions icon packs register for. */
    private static final String[] PACK_ACTIONS = {
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.teslacoilsw.launcher.THEME",
        "app.lawnchair.icons.THEMED_ICON",
    };

    private static final Map<String, Pack> sPacks = new HashMap<>();
    private static final LruCache<String, BitmapInfo> sIcons = new LruCache<>(400);

    private MiraCLOIconPacks() {}

    /** Installed icon packs: package name to label, sorted by label. */
    public static LinkedHashMap<String, CharSequence> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, CharSequence> found = new HashMap<>();
        for (String action : PACK_ACTIONS) {
            List<ResolveInfo> list;
            try {
                list = pm.queryIntentActivities(new Intent(action), PackageManager.MATCH_ALL);
            } catch (Exception e) {
                continue;
            }
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (!found.containsKey(pkg)) {
                    found.put(pkg, ri.activityInfo.applicationInfo.loadLabel(pm));
                }
            }
        }
        List<Map.Entry<String, CharSequence>> entries = new ArrayList<>(found.entrySet());
        entries.sort((a, b) -> a.getValue().toString().compareToIgnoreCase(b.getValue().toString()));
        LinkedHashMap<String, CharSequence> out = new LinkedHashMap<>();
        for (Map.Entry<String, CharSequence> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Forgets loaded packs and icons (the choice changed or a pack was updated). */
    public static synchronized void clear() {
        sPacks.clear();
        sIcons.evictAll();
    }

    /** The pack chosen for where [display] shows icons, or null for the normal icons. */
    private static String packFor(Context context, int display) {
        SharedPreferences prefs = LauncherPrefs.getPrefs(context);
        boolean drawer = display == BubbleTextView.DISPLAY_ALL_APPS
                || display == BubbleTextView.DISPLAY_PREDICTION_ROW
                || display == BubbleTextView.DISPLAY_SEARCH_RESULT
                || display == BubbleTextView.DISPLAY_SEARCH_RESULT_SMALL
                || display == BubbleTextView.DISPLAY_SEARCH_RESULT_APP_ROW;
        boolean same = prefs.getBoolean(KEY_SAME, true);
        String pkg = prefs.getString(drawer && !same ? KEY_DRAWER : KEY_HOME, "");
        return TextUtils.isEmpty(pkg) ? null : pkg;
    }

    /**
     * The icon pack's version of [info]'s icon for a view showing icons as [display], or null
     * when no pack is chosen there or the pack does not cover the app.
     */
    public static FastBitmapDrawable iconFor(Context context, ItemInfoWithIcon info, int display) {
        try {
            String pkg = packFor(context, display);
            if (pkg == null) return null;
            // Only app icons; shortcuts, widgets and work-profile apps keep their own icons.
            if (info.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) return null;
            if (info.user != null && !info.user.equals(Process.myUserHandle())) return null;
            ComponentName cn = info.getTargetComponent();
            if (cn == null) return null;
            BitmapInfo bitmap = bitmapFor(context, pkg, cn);
            return bitmap == null ? null : bitmap.newIcon(context, 0);
        } catch (Exception e) {
            Log.w(TAG, "Icon pack icon failed", e);
            return null;
        }
    }

    private static synchronized BitmapInfo bitmapFor(Context context, String pkg, ComponentName cn) {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
        String key = pkg + "|" + cn.flattenToString() + "|" + day;
        BitmapInfo cached = sIcons.get(key);
        if (cached != null) return cached;
        Pack pack = sPacks.get(pkg);
        if (pack == null) {
            pack = Pack.load(context, pkg);
            if (pack == null) return null;
            sPacks.put(pkg, pack);
        }
        Drawable d = pack.drawableFor(cn, day);
        if (d == null) return null;
        int size = InvariantDeviceProfile.INSTANCE.get(context).iconBitmapSize;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        BitmapInfo info = BitmapInfo.fromBitmap(b);
        sIcons.put(key, info);
        return info;
    }

    /** One icon pack's appfilter: component to drawable name, and calendar prefixes. */
    private static final class Pack {
        final String pkg;
        final Resources res;
        final Map<String, String> drawables = new HashMap<>();
        final Map<String, String> calendars = new HashMap<>();

        Pack(String pkg, Resources res) {
            this.pkg = pkg;
            this.res = res;
        }

        static Pack load(Context context, String pkg) {
            try {
                Resources res = context.getPackageManager().getResourcesForApplication(pkg);
                Pack pack = new Pack(pkg, res);
                XmlPullParser parser = null;
                InputStream in = null;
                int id = res.getIdentifier("appfilter", "xml", pkg);
                if (id != 0) {
                    parser = res.getXml(id);
                } else {
                    in = res.getAssets().open("appfilter.xml");
                    parser = XmlPullParserFactory.newInstance().newPullParser();
                    parser.setInput(in, "UTF-8");
                }
                try {
                    int type;
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                        if (type != XmlPullParser.START_TAG) continue;
                        String tag = parser.getName();
                        String comp = parser.getAttributeValue(null, "component");
                        String comp2 = normalize(comp);
                        if ("item".equals(tag)) {
                            String drawable = parser.getAttributeValue(null, "drawable");
                            if (comp2 != null && drawable != null && !pack.drawables.containsKey(comp2)) {
                                pack.drawables.put(comp2, drawable);
                            }
                        } else if ("calendar".equals(tag)) {
                            String prefix = parser.getAttributeValue(null, "prefix");
                            if (comp2 != null && prefix != null) pack.calendars.put(comp2, prefix);
                        }
                    }
                } finally {
                    if (in != null) in.close();
                }
                return pack;
            } catch (Exception e) {
                Log.w(TAG, "Could not load icon pack " + pkg, e);
                return null;
            }
        }

        /** "ComponentInfo{pkg/cls}" to "pkg/cls", with ".cls" expanded. */
        static String normalize(String comp) {
            if (comp == null) return null;
            String s = comp.trim();
            if (s.startsWith("ComponentInfo{") && s.endsWith("}")) {
                s = s.substring("ComponentInfo{".length(), s.length() - 1);
            }
            ComponentName cn = ComponentName.unflattenFromString(s);
            return cn == null ? null : cn.flattenToString();
        }

        Drawable drawableFor(ComponentName cn, int day) {
            String key = cn.flattenToString();
            String name = null;
            String prefix = calendars.get(key);
            if (prefix != null) name = prefix + day;
            if (name == null) name = drawables.get(key);
            if (name == null) return null;
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) id = res.getIdentifier(name, "mipmap", pkg);
            if (id == 0) return null;
            return res.getDrawableForDensity(id, res.getDisplayMetrics().densityDpi, null);
        }
    }
}

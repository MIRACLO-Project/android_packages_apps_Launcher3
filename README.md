# MIRACLO: Launcher3

Android 17 Launcher3 (Quickstep) from Qualcomm CLO with MIRACLO's home screen features.
Branch: `aurora`. Private: access on request (see [MIRACLO](https://github.com/MIRACLO-Project/MIRACLO)).

## Where MIRACLO's work lives

| Area | Path |
|---|---|
| Labels, page transitions, gestures, layout lock, folder blur, hidden apps | `src/com/android/launcher3/miraclo/MiraCLOLauncher.kt` |
| Home search | `src/com/android/launcher3/miraclo/MiraCLOHomeSearch.kt` |
| App drawer shape, themed icons for every icon, circle default | `src/com/android/launcher3/miraclo/MiraCLOShapes.kt`, `BubbleTextView.java`, `graphics/ThemeManager.kt`, `model/data/ItemInfoWithIcon.java` |
| Icon packs | `src/com/android/launcher3/icons/MiraCLOIconPacks.java` |
| Recents styles | `quickstep/src/com/android/quickstep/views/MiraCLORecentsStyle.kt` |
| Swipe down from the middle (search, notifications, Quick Settings) | `quickstep/src/com/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController.java` |

The grid names in `res/xml/device_profiles.xml` are saved in users' settings: renaming them resets
the home grid.

After testing with `adb install -r`: `adb shell pm uninstall-system-updates com.android.launcher3`.

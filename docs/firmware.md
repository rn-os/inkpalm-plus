# Moaan InkPalm Plus firmware facts

Found by reading the device's `framework.jar`, `services.jar` and `SystemUI.apk` with `dexdump` (2026-10-19).
Firmware `MAS_EPD305_L81B804_T44_V09`, Android 11 (SDK 30), Rockchip RK3566, 720×1440, `ro.sf.lcd_density=276`.
Bootloader is unlocked (`ro.boot.flash.locked=0`, verified boot `orange`) but the device is not rooted.
Raw dumps: `docs/device/`.

## Per-app e-ink settings (what InkPalm+ edits)

Moaan already has per-app DPI, refresh, contrast and "bleach". Its editor screen
(`com.android.systemui.moan.eink.EinkSettingsActivity`) isn't reachable, so InkPalm+ replaces it.

**Table:** `content://com.android.systemui.eink/einksettings` (`com.android.systemui/.statusbar.phone.EinkSettingsProvider`).
Readable and writable by ordinary apps (no permission), and by `adb shell content`. One row per package; SystemUI
adds a row the first time an app opens.

| Column | Meaning |
|---|---|
| `package_name` | |
| `app_dpi`, `is_dpi_setting` | Density the app is drawn at; flag 1 = use it |
| `refresh_mode`, `refresh_frequency`, `is_refresh_setting` | Waveform (below); full refresh after N partial updates (`persist.vendor.fullmode_cnt`); flag |
| `app_contrast`, `is_contrast_setting` | SurfaceFlinger gamma (`debug.sf.gamma.gamma`) while the app is in front; flag |
| `app_anim_filter` | 0/1 |
| `app_bleach_mode`, `app_bleach_text_plus` | Bleach on/off; text plus = `Settings.Secure.high_text_contrast_enabled` |
| `app_bleach_icon_color`, `app_bleach_cover_color`, `app_bleach_bg_color`, `app_contrast_gain` | Bleach levels (ranges not yet mapped) |

**Refresh waveforms** (`refresh_mode`, Rockchip EPD codes from `EinkManager$EinkMode`): -1 system, 7 PART_GC16 (Normal),
0 AUTO, 12 A2, 13 A2_DITHER, 14 DU, 15 DU4. Others exist (2–6 full GC16/GL16…, 8–11 partial GL16/GLR16/GLD16/GCC16, 22–23 AUTO_DU/DU4).
The Quick Settings refresh tile is separate: `Settings.Global.moan_eink_mode` + `persist.sys.mogu.refresh_type` (0 Clear, 1 Normal, 2 Quick).

**Contrast steps** (`EinkAppConfig.CONTRAST_VAUE_ARRAY`, lightest → darkest): 1.5 1.4 1.3 1.2 1.1 1.0 0.9 0.8 0.7 0.6 0.5 0.4 0.3 0.2 0.1.
System default `persist.sys.gamma.level=0.8`. The global slider is `Settings.Global.mogu_contrast_value` (index).

## How it's applied

1. An activity comes to the front → `ActivityTaskManager` logs `linfeifei_top … mCurrentPacakgeName` and queries the
   provider for that package. `EinkSettingsProvider.query` then sets the waveform, `fullmode_cnt`, gamma, bleach,
   and calls `PackageManager.setAppDpiToMap(pkg, dpi)` (or -1 when `is_dpi_setting=0`).
2. `Activity.performCreate` calls `IPackageManager.getAppDpi(pkg)` and, if > 0, overrides the activity's
   `Configuration.densityDpi`/`DisplayMetrics.densityDpi`. Log: `app_dpi: performCreate.dpi=N,PackageName=…`.
   Only new activities pick it up, so **DPI needs the app reopened**; nothing else in the system changes density.
3. Because step 1 happens after the first activity is created, a new DPI written only to the table shows on the
   *second* launch. InkPalm+ also calls `setAppDpiToMap` itself when saving.

**Moaan's PackageManager additions** (no permission checks in `PackageManagerService`; hidden API, reach with
HiddenApiBypass or `service call package`):

| Binder code | Method |
|---|---|
| 200 | `setAppDpiToXML(String pkg, int oldDpi, int newDpi)`: edits `/data/system/package_app_dpi_config.xml` (replaces `dpi="old"` on the pkg line, else appends) |
| 201 | `setAppDpiToMap(String pkg, int dpi)` |
| 202 | `getAppDpiMap()` |
| 203 | `getAppDpi(String pkg)` |

`adb shell service call package 203 s16 <pkg>` reads it back (`ffffffff` = -1).

## Live changes (quick panel)

Moaan itself has no live DPI: its own DPI slider force-stops the app. What InkPalm+ does instead (all verified 2026-10-19):

- **Contrast / refresh / full-refresh interval:** `EinkService.setProperty` (`eink` binder call 5, interface
  `android.os.IEinkManager`) has **no permission check** and sets any system property as system_server.
  `debug.sf.gamma.gamma` changes the screen at once; `sys.eink.mode` = waveform; `persist.vendor.fullmode_cnt`.
  From shell: `adb shell service call eink 5 s16 <key> s16 <value>`.
- **What SystemUI sets when an app comes to the front** (`EinkSettingsProvider.query`): `sys.eink.mode=7` always;
  `persist.sys.mogu.refresh_type` = the row's `refresh_mode` if `is_refresh_setting`, else `Settings.Global.moan_eink_mode`
  (Quick Settings: 0 Clear, 1 Normal, 2 Quick); `persist.vendor.fullmode_cnt` = the row's `refresh_frequency`, else **40**;
  gamma from the row, else `CONTRAST_VAUE_ARRAY[mogu_contrast_value]`. `Live.applyDisplay` copies this exactly.
- **Apps can't read** `sys.eink.*`, `persist.vendor.*` or `persist.sys.mogu.*` (SELinux; `SystemProperties.get` returns
  the default). Shell can. Settings.Global keys (`moan_eink_mode`, `mogu_contrast_value`) are readable.
- **The device froze once** (2026-10-19, needed a hard reboot) while the harness wrote these properties back through
  the `eink` service after a reinstall, and then a provider query hung. Not reproduced since; the harness now
  times out every adb call and checks the provider answers before each check.
- **DPI:** write the row + `setAppDpiToMap`, then change `Settings.System.font_scale` by 0.000001. That's a
  configuration change, so Android recreates open activities in the same process (state kept), and
  `performCreate` applies the new per-app DPI. Recreates *every* app's activities, and apps that handle
  `fontScale` changes themselves won't redraw. Needs WRITE_SETTINGS ("Modify system settings") or Shizuku.
  The stock `font_scale` was already `1.0000801`.

## Fast while scrolling (RefreshHelpers)

`TYPE_VIEW_SCROLLED` events (no window content needed) switch `persist.sys.mogu.refresh_type` to 12 (A2) through the
eink service; 0.5 s after the last one, `Live.applyDisplay` restores the app's own values and one full refresh runs.
Lists at their end send no scroll events. A short boost is easy to miss by polling `getprop` over adb (~0.3 s per
call); the service logs "scroll boost on/off".

## Rotation

No accelerometer, so the display never turns on its own (Moaan keeps `moan.rotation.lock.setting`,
`persist.sys.mogu.rotation`). A 1×1 `TYPE_ACCESSIBILITY_OVERLAY` window with `screenOrientation` set turns the display
and overrides the app in front; removing it hands control back (verified: `dumpsys window displays` `mRotation` 0 ↔ 1).
InkPalm+ remembers a rotation per app and applies it on each foreground change (`Rotation.kt`). After the service
(re)connects, nothing is known about the app in front until the next window change.

## Keeping the panel on (verified 2026-10-19)

- **Reboot:** Android binds the accessibility service again by itself ~2 s after boot, and Moaan doesn't kill it
  afterwards (unlike BOOX).
- **Force-stop** (`am force-stop`, Moaan's **Clear Memory** tile): Android switches the service off as crashed.
  Clear Memory (SystemUI `EinkFunction.clearMemory`, broadcast `com.mogu.clear_mem`) force-stops every running
  package except `com.android*`, `com.google*`, `android` and the JSON list in `Settings.System.APPS_WHITE_LIST_KEY`.
  Apps can't write that key; shell can (`settings put system APPS_WHITE_LIST_KEY '<json>'`).
- Even after a force-stop, BOOT_COMPLETED still reached InkPalm+ at the next boot (`stopped=false` again).
- `Survival`: whitelist entry (Shizuku/adb), and with WRITE_SECURE_SETTINGS (granted once) it switches the service
  back on at boot and whenever the app opens, unless the user switched it off (`onUnbind` clears `panelWanted`).
- Careful from zsh: `"$L:com…"` is read as a `:c` modifier. Quote the colon separately when editing
  `enabled_accessibility_services` by hand; it once merged Key Mapper's and InkPalm+'s entries.

## Edge strip and gesture navigation

Gesture navigation is on (`navigation_mode=2`). The system's back gesture still works when the swipe starts on the
quick panel's strip (an accessibility overlay): verified with an inward swipe over the strip on a nested Settings
screen. The strip therefore only reacts to swipes up or down along it.

## Other system hooks

- `EinkManager` (`android.os`, service `eink`, `IEinkManager.setProperty(key, value)` = binder 5): `setMode` → `sys.eink.mode`,
  `setRefreshType` → `persist.sys.mogu.refresh_type`, `setGrayMode` → `persist.sys.mogu.256gray`,
  `sendOneFullFrame` → bumps `sys.eink.one_full_mode_timeline` (one full refresh).
  `adb shell setprop` can set these `sys.*`/`persist.sys.*` keys.
- SystemUI broadcast receiver (`EinkFunction`): `com.mogu.back_key`, `com.mogu.clear_mem`, `com.mogu.toggle_panel`,
  `com.mogu.screen_shot`, `com.mogu.lock_screen`, `com.mogu.show_brightness_dialog`, `com.mogu.send_keyevent` (extra `keycode`), `com.mogu.shut_down`.
- E-ink controller: `/dev/ebc` (world rw), `/sys/devices/platform/ebc-dev/` (`waveform_version`, `pmic_temp`, …).
- Front light: `/proc/aw99703/led_brightness` + `led_current` (cold), `/proc/aw99703_sec/…` (warm); settings `mogu_cold_led_value`, `mogu_warm_led_value`.
- `Settings.System.APPS_WHITE_LIST_KEY`: JSON list of apps Clear Memory leaves alone.

## Gotchas
- `content query` on this Android has no `--arg`; `einksettingsupdate` queries with a `--where` throw. Untested from an app with selectionArgs.
- `uiautomator dump` leaves out views behind the keyboard; hide it (Back) before dumping.

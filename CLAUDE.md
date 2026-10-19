# InkPalm+

Per-app e-ink tuning (DPI, refresh waveform, full-refresh interval, contrast, Moaan's bleach options) for the
Moaan InkPalm Plus, by editing the firmware's own hidden per-app table. Sister project of SuperBoox
(`~/Github Repos/superboox`), which it borrows its UI helpers and test harness from.

## Read first
- `docs/firmware.md`: everything known about the firmware. **Check it before decompiling anything**; add to it
  whenever something new is learned, so it's never worked out twice.
- `docs/device/`: raw `getprop`, packages, services and `dumpsys display` from the device.

## Code (`app/src/main/java/com/inkpalmplus/`)
- `EinkDb`: the model (`Profile`, `Refresh`), reading/writing Moaan's table (directly, Shizuku fallback), `setAppDpiToMap`.
- `MainActivity`: access status, whole-device tweaks (animations, text size), app list.
- `AppActivity`: one app's settings page.
- `QuickPanelService` (accessibility service: edge strip, foreground app, debug test hooks), `QuickPanel` (the overlay),
  `EdgeHandleView` (from SuperBoox), `Live` (applies settings to the open app: eink service, font-scale nudge),
  `Rotation` (manual rotation through an overlay window, remembered per app), `RefreshHelpers` (Speed while
  scrolling, clean screen on open; whole-device switches with per-app overrides), `Survival` (Clear Memory whitelist,
  turning the service back on at boot / app open), `Prefs`.
- The quick panel is a bottom sheet in portrait and a half-width side sheet (strip side) in landscape; the service
  rebuilds the strip and panel when the screen turns.
- `Shell`: Shizuku shell commands and a background helper. `Ui`: e-ink view helpers copied from SuperBoox.
- Views are built in code, black on white, no animations or ripples (see `Ui.kt` header).

## Build and test
- Build: `./gradlew -q assembleDebug` (SDK at `~/Library/Android/sdk`, set in the untracked `local.properties`).
- **Device checks: `tools/device-test.sh run all`** (or a feature name; `list` shows them). It installs the
  debug build, drives the app by node text, checks Moaan's table and the firmware's `performCreate.dpi` log, restores
  the target app's row, and prints a short summary. Read the summary; open screenshots/dumps only for failures.
  Add checks in `tools/device-checks/<feature>.sh` (`check_*` functions; helpers in `tools/README.md`).
- Quick panel checks (`quick-panel`) drive it through debug test hooks, not `uiautomator`, which would unbind
  the accessibility service. They need the service on and "Modify system settings" allowed.
- Prefer the harness over ad-hoc taps and screenshots. When a screenshot is needed, downscale it
  (`sips -Z 700`) before reading it.
- Installing and testing debug builds on the connected InkPalm is fine. Don't change other apps' rows in Moaan's
  table except through the harness (which restores them) or when the user asks.

## Mockups
- UI changes get mockups first: `docs/mockups/build.py` (boards as HTML, `panel.css`) and `docs/mockups/render.sh`
  (renders PNGs with macOS Quick Look; no Playwright needed). Quick Look draws about 1200×1200 CSS px, so boards are two rows.

## Product direction
- Keep e-ink settings few and plain: the user worries that too many confuse people. New behaviour should default
  to something good, live on the home screen as one switch, and allow per-app overrides only on the app's own page.
  The quick panel stays at five lines (plus ⚙ to the home screen).

## Working agreements
- Subagents run on Sonnet (`model: "sonnet"`).

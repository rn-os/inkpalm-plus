# InkPalm+

A side panel of recent apps for the Moaan InkPalm Plus (swipe along the edge strip), plus secondary per-app e-ink
tuning (DPI, refresh, contrast, presets, font) through the firmware's own hidden per-app table. Sister project of
SuperBoox (`~/Github Repos/superboox`), which it borrows its UI helpers, test harness and CI from.

## Read first
- `docs/HANDOFF.md`: the user's direction, working agreements, current state and open items. Keep it current.
- `docs/firmware.md`: everything known about the firmware. **Check it before decompiling anything**; add to it
  whenever something new is learned, so it's never worked out twice.
- `docs/device/`: raw `getprop` (serial numbers redacted), services and `dumpsys display` from the device. The installed-app list is deliberately not kept (personal).

## Code (`app/src/main/java/com/inkpalmplus/`)
- `EinkDb`: the model (`Profile`, `Refresh`), reading/writing Moaan's table (directly, Shizuku fallback), `setAppDpiToMap`.
- `MainActivity`: home screen: a list of categories opening sub-screens. `SectionActivity` (exported in debug;
  `--es section panel|eink|screen|light|sleep|aod|agenda|reading|touch|about`, `keep_on` = panel): the sub-screens; `eink`
  holds the e-ink settings, "Search apps" and the per-app list; `aod` is the sleep-screen picker, `agenda` the Agenda face's style picker.
- `Aod`, `AodFaces`, `Agenda`: the always-on sleep screen (faces drawn into the HWC's standby.png, Live updates via an alarm
  and the bridge's `redraw=1`; `Agenda`: the Agenda face's styles and their pure layout parts); see docs/HANDOFF.md.
- `AppActivity`: one app's settings page.
- `QuickPanelService` (accessibility service: edge strip, foreground app, debug test hooks). The strip opens `SidePanel`
  (recent apps, paged; a Last read card from `Readers` (NeoReader's provider, KOReader's history.lua) on top; its footer button "E-ink · this app" opens `QuickPanel`, the e-ink overlay). `RecentApps` records
  each app that comes to the front (own file `recent_apps.xml`),
  `EdgeHandleView` (from SuperBoox), `Live` (applies settings to the open app: eink service, font-scale nudge),
  `Rotation` (manual rotation through an overlay window, remembered per app), `RefreshHelpers` (Speed while
  scrolling, clean screen on open; whole-device switches with per-app overrides), `Survival` (Clear Memory whitelist,
  turning the service back on at boot / app open), `Presets` (Reading/Browsing/Comics/Video/System; one panel line),
  `Fonts` (system font, live), `Prefs`.
- The quick panel is a bottom sheet in portrait and a half-width side sheet (strip side) in landscape; the service
  rebuilds the strip and panel when the screen turns.
- KompaktOS (InkControl merged in, mockup 19): `Eink` (backends: stock binder / direct / root bridge request
  files), `ScreenModes` (`ScreenMode` presets, per-app / KompaktOS / everywhere, repaint, contrast, `KompaktSync`),
  `ScreenPanel` (the other edge's strip: modes, Full refresh, contrast, `LightBlock`), `Light` (LEDs via the bridge),
  `AutoOff`, `SleepImages` (sleep and power-off screens), `Tiles` (QS tiles, `TextIcon`). The service also does edge
  Back (inward swipe) and touch-button hold = full refresh (key filter). `App` gives `Eink` its files dir.
- `Shell`: Shizuku shell commands and a background helper. `Ui`: e-ink view helpers copied from SuperBoox.
- Views are built in code, black on white, no animations or ripples (see `Ui.kt` header).

## Build and test
- Build: `./gradlew -q assembleDebug` (SDK at `~/Library/Android/sdk`, set in the untracked `local.properties`).
  Unit tests (pure logic): `./gradlew -q testDebugUnitTest`.
- **Device checks: `tools/device-test.sh run all`** (or a feature name; `list` shows them). It installs the
  debug build, drives the app by node text, checks Moaan's table and the firmware's `performCreate.dpi` log, restores
  the target app's row, and prints a short summary. Read the summary; open screenshots/dumps only for failures.
  Add checks in `tools/device-checks/<feature>.sh` (`check_*` functions; helpers in `tools/README.md`).
- Quick panel and side panel checks (`quick-panel`, `side-panel`) drive them through debug test hooks, not `uiautomator`, which would unbind
  the accessibility service. They need the service on and "Modify system settings" allowed.
- Prefer the harness over ad-hoc taps and screenshots. When a screenshot is needed, downscale it
  (`sips -Z 700`) before reading it.
- Installing and testing debug builds on the connected InkPalm is fine. Don't change other apps' rows in Moaan's
  table except through the harness (which restores them) or when the user asks.

## Speed (side panel, measured 2026-10-21; logcat tag InkPalmPlus, lines "timing: ...")
- Swipe → panel drawn: started at ~265 ms; now ~26–94 ms. The panel is built, measured and laid out ahead
  (`SidePanel.prepare`, 1.5 s after each app switch or close) and reused while `fresh()` (same app in front, same
  `RecentApps.version`, edge and width, < 60 s old). App names/icons are cached (`RecentApps.Info`).
- Tap → app in front: ~250–400 ms, Android's own task switch.
- One full refresh per switch: the panel doesn't flash on close when the target gets a clean-on-open refresh.

## Mockups
- UI changes get mockups first: `docs/mockups/build.py` (boards as HTML, `panel.css`) and `docs/mockups/render.sh`
  (renders PNGs with macOS Quick Look; no Playwright needed). Quick Look draws about 1200×1200 CSS px, so boards are two rows.

## Product direction
- Keep e-ink settings few and plain: the user worries that too many confuse people. New behaviour should default
  to something good, live on the home screen as one switch, and allow per-app overrides only on the app's own page.
  The quick panel has four lines: Preset, Size, Contrast, Rotate (plus ⚙ to the home screen). On KompaktOS the
  e-ink and light controls live in the screen panel instead (stock behaviour unchanged). New apps start on
  the System preset; no suggestions by app type.

## Working agreements
- Subagents run on Sonnet (`model: "sonnet"`).

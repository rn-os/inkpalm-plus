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
- `MainActivity`: settings (mockups docs/mockups/settings-reorg/ and settings-tabs/): home = six rows with summaries
  (Sleep screen, Screen & light, Side panel, Power off, Buttons & reading, About), each opening a screen; at most two
  levels (Sleep screen › Weather/Countdowns/Trash day; Screen & light › Advanced; Side panel › Advanced). **Named tabs,
  no pager**: `tabs(Tabs.sleep(..))` under the title (segmented, current filled black, 52 dp, no animation; a tap
  re-renders), Sleep screen = Face · Updates · Home face (only with the Home face), Screen & light = Screen · Light;
  rarely used settings behind an "Advanced ›" row `pin()`ned at the bottom. The last tab per screen is remembered for
  the process (`lastTab`). Screens are built as groups (`group()`: a setting with its notes); `show()` lays them out,
  two columns in landscape (`Columns.split`); a tab that still doesn't fit scrolls and logs "settings: <screen>/<tab>/
  <orientation> doesn't fit". Pure parts in `SettingsTabs.kt` (`Sections.resolve`, `Tabs`, `Columns`, `Summaries`;
  tested). Rows ≥ 56 dp, explanations as `note()`s. `SectionActivity` (exported in debug; `--es section
  sleep|weather|countdowns|trash|screen|screen_advanced|panel|panel_advanced|power|buttons|about|eink`, optional
  `--es tab face|updates|home|screen|light`; older/extra names: `aod` → sleep Face, `updates`/`home_face` → sleep's
  tab, `light` → screen Light, `keep_on` → panel_advanced, `touch`/`reading` → buttons): `eink` is the stock
  firmware's Screen & light (e-ink settings, "Search apps", the per-app list; scrolls).
- `FacePicker` (pure: the picker's pages from `Aod.Face` × styles, the one in use, "All" groups, setup lines) and
  `FacePickerActivity` (exported in debug): the sleep-screen picker, one face and style a page, true-shape previews
  (`FacePreview`), "Use this" (`Aod.choose`), "All" grid; landscape = preview left, controls right (mockup
  docs/mockups/aod/face-picker/, option 6). Root's `am broadcast -n com.inkpalmplus/.Aod\$Alarm --es dump faces`
  writes every page in both orientations to files/faces/.
- `Aod`, `AodFaces`, `Agenda`, `Home`, `Weather`, `Dither`: the always-on sleep screen (faces, in the picker's order: Clock, Clock + date, Analog, Home, Agenda, Reading, Photo, Picture, Minimal, Default; drawn into the HWC's standby.png, Live updates via an alarm
  and the bridge's `redraw=1`; drawn in the phone's physical orientation (`Aod.Gravity`, accelerometer while the screen is on), else the display's; `Agenda`: the Agenda face's styles and their pure layout parts; `Home`: the Home face's styles (Dashboard, Calm) and its pure parts: Open-Meteo JSON, rain summary, staleness, countdown picking, trash day; `Weather`: the Home face's Open-Meteo fetch and cache, city search; `Dither`: the Photo styles); see docs/HANDOFF.md.
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
  `ScreenPanel` (the other edge's strip: Wi-Fi/Bluetooth/Airplane/Rotate icon toggles (`Radios`, `RotationLock`; hold = that Android settings screen, `ScreenPanel.Hold`; Material Symbols vectors in res/drawable, see NOTICE), modes, Full refresh, contrast, `LightBlock`), `Light` (LEDs via the bridge),
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

## Permissions
- INTERNET (and ACCESS_NETWORK_STATE) exist **only for the Home face's weather** (`Weather`: Open-Meteo forecast for
  the user's city coordinates, and its geocoding search; and the IP city estimate when the user taps it). Fetch as "Weather updates" says (`Home.weatherGap`: Each update = each Live update, 4 min floor asleep, 5 min awake; or 15/30/60 min), only when a network is already
  up, ~8 s limit, no retries; never turn radios on. Don't add other network use without the user asking: the app's
  privacy promise is "nothing else leaves the phone" (README, About).
- ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION (asked together; the user may give either) only for Weather location
  → "Use my location": one fix when tapped (`Weather.locate`: network, fused, GPS at once, API 31+ a single
  high-accuracy `getCurrentLocation` request when precise is given, else a minTime-0 listener removed on the first
  fix; 45 s, else a last known fix < 1 h), rounded to 0.01°, stored as the city. FINE is there because Android limits
  coarse-only requests to ~1 per 10 min, so microG postpones its Wi-Fi scan past the wait. Never a listener left
  running, no background location. Logs "home: locate: …".
- `NetCheck`: online / offline / blocked (the phone has a connected network but InkPalm+'s `activeNetwork` is null,
  e.g. LineageOS's `restricted_networking_mode`=1 → blockedReasons=8 until App info → Mobile data & Wi‑Fi → Network
  access). `Weather.online` uses it; blocked shows a box on Weather location / Home face page and the face says
  `Home.BLOCKED`. Logs "home: weather: online|offline|blocked by restricted networking" once per change.
- "Fix microG location" (`Weather.fixMicrog`, `Home.microgNextStep`): offered on Weather location when microG
  (com.google.android.gms) lacks ACCESS_FINE_LOCATION or ACCESS_BACKGROUND_LOCATION; after a confirm, grants those two
  and appops FINE_LOCATION to microG only: the root bridge's `files/perm.req` (`microg_location=1`, handled by the
  KompaktOS repo's eink-apply.sh from the next system image; re-checked after 2 s), then Shizuku (`pm grant`/`appops
  set`), else microG's app permission page. Logs "home: microg fix: …".
- "Estimate from internet connection" (`Weather.estimateFromIp`, ipinfo.io/json over HTTPS, no key): only on tap,
  after `Home.IP_NOTE` says it sends the public IP address; city + rounded coordinates stored like a searched city.

## Product direction
- Keep e-ink settings few and plain: the user worries that too many confuse people. New behaviour should default
  to something good, live on the home screen as one switch, and allow per-app overrides only on the app's own page.
  The quick panel has four lines: Preset, Size, Contrast, Rotate (plus ⚙ to the home screen). On KompaktOS the
  e-ink and light controls live in the screen panel instead (stock behaviour unchanged). New apps start on
  the System preset; no suggestions by app type.

## Working agreements
- Subagents run on Sonnet (`model: "sonnet"`).

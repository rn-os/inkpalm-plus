# InkPalm+ handoff

Where the project stands and where it's going, so a new session can pick it up. Code layout, build and test
commands live in `CLAUDE.md`; everything learned about the firmware lives in `docs/firmware.md`.

## Direction (from the user)
- **The side panel of recent apps is the product.** Swipe along the edge strip → recent apps, newest first;
  efficiency of switching is the point. It's a port of SuperBoox's idea (sister project, `~/Github Repos/superboox`)
  to a phone-sized, non-note-taking device.
- **E-ink tuning is secondary.** The user found they don't use the per-app e-ink features, so they live behind
  "E-ink settings" on the home screen and the side panel's "E-ink · this app" button. Don't grow them.
- **Few, plain settings.** New behaviour defaults to something good, appears as one switch, and per-app overrides
  live only on an app's own page.
- **Design language:** black on white, rounded outlined buttons and cards (the user likens it to the Mudita
  Kompakt's e-ink OS), no animations, no gray fills. The previous app is marked like SuperBoox's "Previous app"
  (← badge on the icon).
- **Speed matters.** SuperBoox is the benchmark (no recorded numbers; different hardware). Current: swipe → panel
  ~26–94 ms, tap → app ~250–400 ms (Android's task switch), one full refresh per switch.
- More SuperBoox features may be ported later; the user will say which.

## Working agreements
- **Mockups before any UI change** (`docs/mockups/build.py` + `render.sh`), and a mockup for anything that needs
  the user's decision.
- **Delegate minor work to Sonnet subagents** (`model: "sonnet"`) to save tokens; review their output before
  committing.
- Commit locally as work lands; push to GitHub only when the user says so.
- On the device: never force-stop `com.inkpalmplus` (Android then switches its accessibility service off); never
  rewrite `enabled_accessibility_services` by hand (it once merged Key Mapper's entry with ours; zsh `$L:c…`
  is a modifier); the harness snapshots and restores what it changes.

## State (2026-10-21)
- Public repo: https://github.com/rn-os/inkpalm-plus (`main`). Personal data (installed-app list, serial
  numbers) was scrubbed from history before the first push.
- Built and on the user's InkPalm (debug build): side panel with recent apps (paging by swipe, ✕ per app,
  Clear all, hold menu, landscape two columns), home screen with Side panel settings and sub-screens
  (Touch button, E-ink settings, Keeping the panel on), the e-ink panel (Preset, Size, Contrast, Rotate),
  refresh helpers, font, touch button, keep-on (Clear Memory whitelist, self-repair).
- README and Reddit launch draft (`docs/launch/reddit-post.md`, images in `docs/launch/reddit/`) are pushed.
  **Don't post until a release APK exists.**

## Release setup
- **Lockdown:** InkPalm+'s broadcasts (open/close panel, strip, clear recents) need the signature permission
  `com.inkpalmplus.permission.PANEL`; test hooks are registered only in debuggable builds; `SectionActivity` is
  exported only in debug builds (`manifestPlaceholders["sectionExported"]`).
- **Signing:** release builds use a private key at `~/.inkpalm-plus/release.jks`, credentials in
  `~/.inkpalm-plus/release.env` (both mode 600, never in the repo). **Back them up: losing the key means
  Obtainium users can't update.** Gradle reads `IPP_KEYSTORE`, `IPP_KEYSTORE_PASSWORD`, `IPP_KEY_ALIAS`,
  `IPP_KEY_PASSWORD`; without them the release APK is unsigned. Release cert SHA-256:
  `782af53e90cdf97532fd88cf5ac0c771a4a58f1a76a63e87745e018b2a2d1d7a`.
- **CI** (`.github/workflows/build.yml`, modeled on SuperBoox's): tag `v1.2.3` → stable release; push to `main`
  → pre-release `NEXT_VERSION-dev.N` (Obtainium: "Include prereleases"); other branches/PRs → signed test APK as a
  workflow artifact. The key comes from repo secrets `IPP_KEYSTORE_B64`, `IPP_KEYSTORE_PASSWORD`, `IPP_KEY_ALIAS`.
- **First pre-release:** `1.0.0-dev.2` (2026-10-22), APK verified: release key, not debuggable. No stable
  release yet; for a stable one add `docs/releases/v1.0.0.md` and push tag `v1.0.0`.
- The user's device runs **debug** builds (test key). Moving it to release builds needs one uninstall (different
  signature), which loses InkPalm+'s settings and switches its accessibility service off; ask first.

## Reader app (2026-10-22)
- Side panel Last read card + "Your books" + Browse files (mockups 13, 14). Reader picked on first start
  (home screen question, only installed readers) and in a Reader app row: NeoReader, KOReader or None.
- NeoReader: its exported provider. KOReader: `/sdcard/koreader/history.lua` + `<book>.sdr/metadata.*.lua` titles,
  needs All files access (MANAGE_EXTERNAL_STORAGE); the card asks for it until granted. Other readers left out.
- Newest app setting (mockup 15): Bottom by default (list reads upward, reader card below every app), or Top.
- adb taps don't register on DocumentsUI's file list; use keyboard focus (TAB, ENTER) in tests.

## KompaktOS (2026-10-23)
- The user's InkPalm now runs KompaktOS 1.2 (Android 16). InkPalm+ crashed there on start (receivers need
  RECEIVER_EXPORTED/NOT_EXPORTED on Android 14+); fixed. Moaan's e-ink table doesn't exist there, so the
  E-ink features do nothing on KompaktOS.
- Front light: `Light.kt` uses InkControl's API (org.neoport.inkcontrol, repo ~/Github Repos/InkPalm-KompaktOS).
  Built per the user's direction rather than mockup 16: sliders with − and + at the top, no On/Off (0% = off).
- Rows have one measured height (a real row, at least 60/50 dp); apps per page = what fits, rest on the next page.
- Landscape (mockup 17 A): a 72% panel in two columns when there's a light or a reader: light and latest books on
  the left (as many as fit, hold one for all), recent apps on the right. Without either, the old half-width layout.
- Permissions granted on the user's device by adb (2026-10-23): WRITE_SECURE_SETTINGS, WRITE_SETTINGS, All files
  access, battery-optimization exemption. Reader app set to KOReader.
- **Backburner (user):** a first-open tour that walks through all of these grants (accessibility, Modify system
  settings, All files access, keep-on, reader app).

## InkControl merged in (branch `merge-inkcontrol`, 2026-10-23)
- Decided by the user: InkPalm+ absorbs InkControl (retired after). Right edge = recents, other edge = **Screen &
  light** panel (mockup 19, built as drawn; not yet seen by the user). The light moved out of the recents panel.
- Built and unit-tested locally only (device busy with a system_b flash): backends (`Eink`), screen modes +
  repaint, contrast, front light via the bridge (sensor, follows brightness, off at screen-off), edge Back (on by
  default on KompaktOS), touch-button hold = full refresh (key filter, `canRequestFilterKeyEvents`), QS tiles,
  auto power-off (USE_EXACT_ALARM), sleep screen, power-off screen (Moaan's / Panda / picture), KompaktSync parser.
- Behaviour change on KompaktOS: full refreshes now work there (`eink.full=1`), so **Clean screen on open** (on by
  default) and closing a panel flash once, as on stock.
- **Unverified on the device:** everything above. Specific doubts: reading `/vendor/media/poweroff.png` from an
  app (SELinux). Power-off screen: the image's `inkpalm-poweroff.sh` draws /data/misc/poweroff.png at shutdown
  (verified by the KompaktOS session, also used by auto power-off); one picture for normal and low battery (a
  separate one needs a second bridge request); whether adding `canRequestFilterKeyEvents` keeps the service enabled across the
  update; Back strips eating taps on the last 10 dp of each edge; tiles' look on KompaktOS QS.
- Device checks: `tools/device-test.sh run screen-panel` (KompaktOS mode added to the harness).
- **Verified on the device (2026-10-23, KompaktOS 1.2):** `run all` 10/10 (side panel + screen panel: every mode
  reaches sys.eink.mode, per-app mode follows the app, Full refresh bumps the timeline, contrast, edge Back); the
  service stayed enabled across the update adding key filtering; a real swipe on the left strip opens the panel;
  touch-button hold (KEY_HOMEPAGE via sendevent) = full refresh, short = Home; InkControl's light levels imported.
  Panda: apps can't read /vendor/media (avc vendor_file) → app asks the bridge for `standby.req export=1`
  (files/vendor_poweroff{,_nopower}.png). Done in InkPalm-KompaktOS main, baked into the next image; verified end to
  end with it. Older images: Panda says a newer image is needed.
- Power key sometimes doesn't wake (user report): reproduced as a system issue (also with InkPalm+'s key filter
  off, also with `input keyevent 26`); handed to the KompaktOS session.
- User (2026-10-23): the white repaint flash after a mode change shows. E-ink QS tiles not checked: the user
  uses the side panels instead (tiles stay, unverified). InkControl to be uninstalled.

## Always-on sleep screen and settings categories (branch `aod-mockups`, 2026-10-26)
- Mockups 20 and 21 approved and built. `Aod` (settings, install, Live alarm, redraw bookkeeping) and `AodFaces`
  (drawing). Faces: Clock, Clock + date, Analog, Agenda, Reading, Photo, Minimal, plus Picture (the picked picture
  as it is) and Default (Moaan's). Update: Frozen or Live every 5 (default) / 15 / 30 / 60 min, with the estimated
  cost on the settings screen (4 / 1.3 / 0.7 / 0.3 % a day; estimates, not measured).
- How it works: the HWC takes /data/misc/standby.png AT the power-off transition, so the face is installed ahead:
  redrawn once a minute while the screen is on (only when the minute, battery, events or book changed), and when the
  screen turns. Live: exact allow-while-idle ELAPSED_REALTIME_WAKEUP alarm on the wall-clock boundary (KompaktOS
  defaults v4 exempt InkPalm+ from Doze) → draw → `standby.req apply=1 redraw=1`; the bridge wakes with the LEDs held
  at 0 and sleeps again (~5 s). That wake's SCREEN_ON/OFF are swallowed (`Aod.redrawWake/redrawSleep`; `Light.apply`
  also holds off while `Aod.redrawing()`), so no LED flash and auto power-off keeps counting. Updates continue while
  charging (plugging in while asleep restarts them); below 10% on battery the face becomes the BATTERY box, drawn
  once, and updates stop.
- Landscape: the HWC logs `mogu_rotation :0` and draws the 720x1440 PNG into its 1440x720 panel itself, so faces
  are laid out at 1440x720 and pre-rotated (ROTATION_90 → +90° clockwise, Android's definition). The picked picture
  is also kept whole (`files/sleep_picture_src.png`, long side ≤ 1440) so the landscape Photo face crops it to its
  own shape; `files/sleep_picture.png` is the portrait crop (Picture face).
- Settings: home is now categories (Side panel incl. Keeping the panel on, Screen & e-ink, Front light, Sleep &
  power → Sleep screen, Reading, Buttons, About); old section names still open. Uses Ui.kt's Radius/Type/Space.
- Debug hooks: `aod_face`, `aod_interval`, `aod_render`, `aod_soon <s>`, `aod_fire`, `aod_arrow [rotation]`,
  `aod_pick <file>`, `aod_state`.
- **Verified on the device:** every face renders (portrait and landscape); a real Live alarm redrew the panel while
  asleep with the LEDs at 0 throughout, ebc_thread alive, system_server unchanged; low-battery face (faked level);
  landscape Photo crop from the whole picture; all categories open.
- **Unverified:** the landscape face's direction by eye (check with `aod_arrow`: the arrow should point up); real
  Doze timing (deep idle can't be forced on this build; device was on USB); Agenda event cards with real events
  (no calendar on the device); the battery cost estimates.
- Backburner: a focal point (top / centre / bottom) for portrait photos shown in landscape.

## Invisible Live updates (branch `aod-invisible-update`, 2026-10-26)
- `WakeCover`: during the bridge's redraw wake (and auto power-off's predraw wake) a full-screen accessibility
  overlay shows the picture the HWC is about to draw (the new face / files/poweroff.png), added while still asleep,
  turned for the display's rotation (`turnFor`), holding the orientation of the user's sleep (`orientationFor`) so the
  display turns while asleep, not during the wake (Android hides overlays mid-turn). Removed 1 s after the wake's
  SCREEN_OFF, at once on a user wake, after 15 s in any case. A redraw wake still on after 6 s is taken as the user's.
- Auto power-off: `Aod.poweringOff` (predraw wake is InkPalm+'s, Live alarm cancelled and not re-armed).
- Release builds have no test hooks: as root, `am broadcast -n com.inkpalmplus/.Aod\$Alarm` is one Live update now;
  `--es cover poweroff` is the power-off cover and wake bookkeeping without the power-off (then run
  `inkpalm-poweroff.sh predraw` and clear `persist.sys.mogu.wallpaper`).
- Verified on the device (release-signed branch build): portrait and landscape updates and the power-off dry run,
  LEDs 0 in every sample, screenshots during the wake pixel-identical to /data/misc/standby.png (poweroff.png).

## AOD follows the phone turned while asleep (branch `aod-reorient`, 2026-10-28)
- Asked: sleep in portrait, turn to landscape while asleep, and the next Live update shows the landscape face.
- Asleep the window manager reports rotation 0 whatever the phone does, so `Aod.reorient` (in `update`, before
  drawing, under the alarm's wake lock) reads TYPE_ACCELEROMETER directly: up to 600 ms or until 4 steady samples
  (`steady`: each axis within 1.5 m/s² of the mean), then `rotationFromGravity`: |x| vs |y|, decided only when the
  dominant axis is ≥ 4.5 m/s² (screen tilted up ≥ ~27°; a phone leaning back on a stand counts) and beats the other by ≥ 2.5; near-flat, ~45° or moving keeps the held one. (Requiring it to beat |z| too kept a propped phone at ~45° from turning, 2026-10-29.)
  Mapping is Android's (x up → ROTATION_90, -x up → 270, -y up → 180), as WindowOrientationListener.
- `Orientation.turned(r)` moves the held (frozen) rotation; the user's wake still clears it. The face and
  `WakeCover` (orientationFor + turnFor) both read the same held rotation, so the cover and panel buffer agree.
- ~~Only with `accelerometer_rotation` = 1~~: since `panel-icons` (2026-11-16) also with rotation lock on (see below).
- Log line per update: `aod: orient: <sensor> n=… in … ms xyz=… → <rotation|keep>, held A → B`.

## Agenda styles (branch `agenda-styles`, from `aod-reorient`, 2026-10-29)
- Mockups: docs/mockups/aod/agenda-variants/ (sheet.png; Current, A big clock, B day rail, C next up, D today /
  tomorrow, E week strip). All five built next to Classic (the default): `Agenda.Style`, drawn in `AodFaces`
  (portrait and landscape; the mockups' CSS px are panel px). Sleep screen → Agenda style (shown when Agenda is the
  face): thumbnails with today's events, a tap installs it (pref `aod_agenda_style`).
- The calendar read now covers the start of this week (or today) to a week ahead / the week's end (≤ 200 events);
  each style picks from it (`Agenda.upcoming`, `onDay`, `perDay`). Pure layout parts in `Agenda` (rail hours,
  hour-label step, label stacking, longest free stretch, row sharing, "+2 more") with AgendaLogicTest.
- Debug hooks: `aod_agenda <STYLE>`, `aod_agenda_dump` (every style, both orientations, to files/agenda_*.png).
  Dense / empty / no-access / 24 h layouts were checked that way on an emulator with test events.
- Verified on the device (1.1.0-branch.42): every style picked through the UI in landscape and portrait, the
  installed files/standby.png pulled for each; a Live update asleep (Week) matched. Left on Agenda · Classic.
- Known: Day rail pushes labels apart when events crowd, so a label can sit below its block. 12 h times have no AM/PM (as Classic).

## Photo style (branch `agenda-styles`, 2026-10-29)
- The panel bands photos into grey steps. Mockups compared plain 16-grey, Floyd–Steinberg 16-grey / 1-bit, Atkinson
  1-bit, Bayer and a boosted 16-grey (the dither mockups' scratch dir, `dither.py`); on the device the user picked
  Atkinson 1-bit.
- `Dither`: Crisp = Atkinson 1-bit (default), Smooth = Floyd–Steinberg to the 16 levels (0, 17, …, 255), Plain = as
  before. Pure functions on grey IntArrays (Rec. 601 luma first) with DitherTest; `Dither.apply` wraps a Bitmap
  (getPixels/setPixels) and logs `dither: <STYLE> WxH in N ms`.
- Photo face: the picture is dithered right after it's scaled and cropped, in the face's own shape (1440×720 in
  landscape; `turn` is an exact quarter turn, so still one pixel per panel pixel), before the clock box is drawn.
  Picture face: `Aod.installPicture` dithers files/sleep_picture.png (already 720×1440) into files/standby.png; Plain
  copies it byte for byte as before.
- Settings: Sleep screen → Photo style (shown for Photo and Picture once a picture is picked): three previews of the
  face in use, dithered at the thumbnail's own size so the dots show (a scaled-down dither would blur back to grey);
  a tap installs it. One pref for both faces: `aod_photo_style`; debug hook `aod_photo <STYLE>`.
- Also in this round (user, 2026-10-29): Day rail starts at the first timed event's hour or 7 AM, whichever is
  earlier, and ends at 7 PM or the last event (no longer stretched to take in now, so an empty day at night isn't a
  rail from 12 AM); the now line only when now is on the rail. Every Agenda style now stops 40 px above the
  "Updated" footer (`limit`; the landscape Big clock divider and the rail's spill below its last hour included).

## Rotation lock toggle (branch `rotation-lock`, 2026-11-16)
- Screen & light panel, top row: [Wi-Fi] [Bluetooth] [Airplane] [Rotate] ⚙ (✕ in landscape). Mockup:
  `docs/mockups/rotation-lock/rotation-sheet.png`, section A (the icon-over-state variant there was dropped for the
  other toggles' "Name / State" text; no long press).
- `RotationLock`: tap toggles Settings.System.accelerometer_rotation (1 Auto ↔ 0 Locked, filled black); locking
  first sets user_rotation to the display's rotation, like Android's QS tile. WRITE_SETTINGS appop, Shizuku
  fallback. A ContentObserver keeps the toggle in sync while the panel is open.
- Per-app Rotate modes (`Rotation`'s overlay) still win over the lock. The sleep screen's reorient runs with the lock
  on too (since `panel-icons`, below).
- (Superseded by the icon toggles below: the shared text size / "BT" short name is gone.)

## Icon toggles (branch `panel-icons`, 2026-11-16)
- The top row's four toggles are icons now (Material Symbols Outlined, weight 600, filled; vector drawables
  `res/drawable/ic_wifi*.xml`, `ic_bluetooth*`, `ic_airplanemode_*`, `ic_screen_rotation`, `ic_screen_lock_rotation`;
  Apache 2.0, see NOTICE). Same 48 dp buttons, 2 dp outline, small radius; on/locked = filled black with a white icon,
  off = black icon on white. Icon: wifi / wifi_off, bluetooth / bluetooth_disabled, airplanemode_active / _inactive,
  screen_rotation (Auto) / screen_lock_rotation (Locked).
- Passing states (Turning on/off) show the icon of the target state plus a 7 dp dot in the top-right corner. No radio
  (`State.NONE`): dashed outline, disabled. contentDescription from `Radios.describe` / `RotationLock.describe`
  ("Wi-Fi, off", "Bluetooth, turning on", "Rotation, locked").
- `ToggleFit`, `Radios.label`, `Radio.short` and `RotationLock.label` were removed.

## Sleep screen re-orients with rotation lock on (branch `panel-icons`, 2026-11-16)
- Asked: turn the phone to landscape while asleep with rotation lock on and have the next Live update show the
  landscape face, without waking it and turning the lock off.
- `Aod.reorient` no longer checks `accelerometer_rotation`; same gravity rules (near-flat / ~45° / moving keeps the
  held orientation). It only moves `Orientation`'s held rotation; Settings (user_rotation, accelerometer_rotation)
  are never written. `WakeCover` still holds the display at the face's rotation for the redraw wake through its
  overlay window's `screenOrientation` (honored under the lock, as `Rotation`'s per-app modes are), and counter-turns
  the buffer for the rotation it actually draws at. The user's wake clears the held rotation and the display goes back
  to the locked user_rotation.

## Open items
- Run `tools/device-test.sh run all` on the locked-down debug build (the InkPalm was disconnected when the lockdown
  landed; the release APK itself was checked: signed with the release key, not debuggable, SectionActivity not
  exported, PANEL permission signature-level).
- Re-take the side panel screenshots with a fuller recent list (tests reset it to three apps).
- The device froze twice during long harness runs (SystemUI stopped answering); not seen in normal use; worth
  understanding before wide release.
- First-run setup tour (accessibility, Modify system settings, keep-on grants) and a "Copy debug report" like
  SuperBoox's were recommended for public users.
- Name: "InkPalm" is Moaan's product name; a trademark complaint is possible.
- Optional: same-device speed comparison with SuperBoox (needs the user to enable its accessibility service).

## Sleep face in the phone's physical orientation (branch `sleep-physical-orientation`, 2026-11-16)
- Asked: InkOS (launcher, locked portrait) held in landscape → the sleep face was portrait until the first Live
  update re-oriented it. The face now follows how the phone is held, not the display; InkOS stays portrait.
- Awake: `Aod.Gravity` follows TYPE_ACCELEROMETER at SENSOR_DELAY_UI (~15 Hz) only while the screen is on (the
  user's, not a redraw wake) and a drawn face is picked (`watchGravity` at the user's wake / service start / face
  pick, `stopGravity` at the sleep). Same rules as `reorient` (`steady` over the last 4 samples, `rotationFromGravity`).
  A decision that holds 500 ms (`SETTLE_MS`) becomes `Orientation.physical`; the pre-render (`collect` →
  `rotations.current`) draws `physical ?: display`, so the right face is usually installed before the press.
  Log: `aod: physical orient → 1, face 0 → 1 (display 0)`.
- At the user's SCREEN_OFF: `Gravity.atSleep` = the latest steady decision if it ended within 5 s (`STALE_MS`),
  else undecided (flat, ~45°, moving) → the display's rotation; frozen by `Orientation.sleep(atSleep)`. If the face
  the HWC just took (`installedRotation`, set when the bridge got it) was drawn for another rotation
  (`sleepNeedsRedraw`: turned < ~0.5 s before the press, or its render still queued), one immediate redraw
  (`fixAtSleep`: wake lock, `expectWake`, `install(redraw = true)` with the `WakeCover`, as a Live update; also with
  Frozen updates). Log: `aod: sleep orient: xyz=… N ms ago → 1|undecided → 1 (display 0), installed 0, redraw now`.
- Never writes user_rotation / accelerometer_rotation; works with the lock on or off. `Rotation`'s per-app overlay is
  untouched (the physical orientation wins for the face only). The user's wake clears the held and physical rotation;
  the cover (which holds the face's rotation during a redraw wake) is gone 1 s after that wake's sleep, so the display
  turns back while asleep and the user's wake shows the display's own orientation.
- Battery: the accelerometer only while the screen is on (with auto-rotate on Android runs it then anyway); ~15
  trivial callbacks a second on an awake CPU; a re-render when the phone is turned; rarely one redraw wake at a sleep.

## Home face (branch `home-face`, 2026-11-16)
- Asked: a "Home" always-on face from docs/mockups/aod/home-face/ (home-sheet.png A Dashboard and B Calm), v1 only:
  no shopping list, Home Assistant, message board or commute (the user has no smart home; uses Google Calendar via
  the phone's calendar, and Apple Reminders, which isn't integrated).
- `Aod.Face.HOME`; style `Home.Style` (DASHBOARD default, CALM), drawn in `AodFaces` (`homeDashboard`, `homeCalm`,
  portrait and landscape; the mockups' CSS px are panel px). Dashboard: clock, date, battery, NEXT 3 rows, then a
  grid: Weather, Trash (only when today/tomorrow; black), up to 2 countdowns (1–4 cards; see `homeGrid`). Calm: big
  clock, date, battery, "NEXT 09:00 Standup", rule, the weather line, then Trash and 1–2 countdowns as large cards
  (Trash black only on the day, outlined the day before as in mockup B), centred, the last ones dropped if they
  don't fit. Content stops above the "Updated" footer (portrait `limit`, landscape h−92).
- Weather (`Weather`, pure parts in `Home`): Open-Meteo /v1/forecast (current temperature + code, hourly
  precipitation probability, daily max/min/code, sunrise/sunset; `timezone=auto&timeformat=unixtime`, 2 days,
  always °C, converted for display; Units Auto/°C/°F, Auto = °F for US and the few other °F countries). City from
  Open-Meteo geocoding (Sleep screen → Weather location; name + lat/lon in prefs; no location permission). Fetched
  at most every 30 min (`Home.due` on the last ATTEMPT, so failures don't retry sooner), only when
  `ConnectivityManager` has an internet-capable network (never turns radios on): asleep in `Aod.update` before the
  redraw (own wake lock ≤ 9 s; `fetchIfDue` waits ≤ 8 s, 4 s connect/read timeouts), awake on the minute tick in a
  background thread then a forced redraw. Cache: files/weather.json + `home_weather_at`. Shown with "as of 6:40"
  when > 90 min old or offline; a cache without today in it shows "No recent weather (offline)". Rain summary: the
  first run of hours ≥ 50% from now on ("Rain 3–6 PM", "Rain until 6 PM", "No (more) rain today"); the Dashboard's
  strip is 8–19 h, filled bars from 50%.
- Battery: a fetch is one HTTPS GET of ~4 KB, ~1–2 s awake on Wi‑Fi, ≤ 48 a day (~0.4%/day est.); none in Airplane
  mode or with Wi‑Fi off, and none with no city set. The weather alone never wakes the phone: it rides the Live alarm.
- Countdowns: `Aod.countdownCandidates` queries Instances (visible calendars, `allDay=1 OR end−begin ≥ 4 h`) from
  today to +366 d, keeps ≤ 60 d unless pinned; cached 5 min. `Home.countdowns`: pinned first (by date), then nearest
  first, hidden never, same title+start shown once; "12 days to …", "Tomorrow: …", "Today: …". Pins/hides persist
  as "eventId|title" string sets (Sleep screen → Countdowns: Pin / Hide per candidate).
- Trash day (off by default): Sleep screen → Trash day, weekday chips for Recycling and Trash; "Recycling tomorrow"
  the day before, "Trash today" on the day, nothing otherwise (`Home.trashDay`).
- INTERNET + ACCESS_NETWORK_STATE are new, for the weather only (README, About and CLAUDE.md say so).
- "Use my location" (asked after the first build): next to the city search; ACCESS_COARSE_LOCATION asked on the
  first tap (once denied for good: app settings). `Weather.locate`: one `getCurrentLocation` per provider in
  `Home.providerOrder` (network, fused, gps; those on), 20 s budget overall, else the newest last known fix < 1 h
  (passive included); `Home.roundCoord` to 0.01° before geocoding/storing; named by `Geocoder` when present, else
  "Current location (lat, lon)". Stored exactly like a searched city. The device (KompaktOS GSI) has microG as
  user apps: com.google.android.gms provides the network and fused providers
  (org.microg.gms.location.provider.*), backend org.microg.nlp.backend.nominatim (a geocoder); a passive provider;
  no gps provider listed by `dumpsys location`; location_mode=3.
- "Use my location" round 2 (face-picker branch): on the device it found nothing: "providers on [passive, network,
  fused], asking [network, fused]", then microG logged "NetworkLocation: Schedule high-power scan in 94735ms": Android
  clamps coarse-only requests to ~10 min, so microG postponed the Wi-Fi scan past our 20 s. Now FINE is asked with
  COARSE (either may be given); precise → one QUALITY_HIGH_ACCURACY `getCurrentLocation` (LocationRequest,
  duration 45 s) on network and fused at once, a provider answering null gets a minTime-0 listener removed on the
  first fix; 45 s overall, "Finding your location…" shown. Failure box (`Home.locateMessage`): "No location from
  microG. Turn on Wi‑Fi location in microG Settings → Location." + "microG location settings" (VIEW
  x-gms-settings://location in com.google.android.gms → org.microg.gms.ui.MainSettingsActivity, microG's Location
  page; then org.microg.gms.ui.SettingsActivity; then ACTION_LOCATION_SOURCE_SETTINGS. The GOOGLE_LOCATION_SETTINGS
  action only forwards to Android's Location page),
  approximate-only → `Home.APPROXIMATE_NOTE` + "Allow precise (used once, rounded to ~1 km)", and "Estimate from
  internet connection" (also a small "Estimate…" under the button): explains first (`Home.IP_NOTE`), then one GET
  https://ipinfo.io/json (429 → "busy"), `Home.parseIpPlace` (city, else region; rounded; bogon/error → null).
  microG's Wi-Fi location (location_wifi_mls=1) must be on for any fix; the user turned it on.
- Device test (build 68, precise granted "Only this time"): microG now scans at once ("Schedule high-power scan
  now", "Start network scan for WorkSource{… com.inkpalmplus}") but still no fix in 45 s: microG Services itself
  has ACCESS_COARSE_LOCATION only (FINE not granted, appop FINE_LOCATION ignore, no background), so it can't read
  Wi-Fi scan results; it also logged "Started AskPermissionActivity" (its own permission request, not visible).
  The failure note now says so when `Weather.microgPrecise` is false (`Home.MICROG_PRECISE`) with a "microG
  Services permissions" button (its app info). The user has to grant microG precise location (all the time);
  InkPalm+ doesn't change other apps' permissions.
- Face previews (all pickers) keep the face's shape: a quarter of the face's size, FIT_CENTER; in landscape the
  settings show the landscape layouts (asked: "those preview screens look stretched").
- Checks: HomeLogicTest (JSON, forecast and geocoding parsing, rain spans/summaries, units, throttling/staleness,
  the view, countdown picking/texts incl. DST, trash day). Off-device pictures: root's
  `am broadcast -n com.inkpalmplus/.Aod\$Alarm --es dump home` (release builds too; debug: test hook
  `aod_home_dump`) writes files/home_{,stale_,real_}<STYLE>_<rotation>.png: sample weather + sample trash day,
  the same forecast 2 h old and offline, and the real settings; the calendar is real in all. Nothing is changed.

## Hold a panel toggle for its settings (branch `home-face`, 2026-11-16)
- Asked: holding Wi-Fi / Bluetooth / Airplane / Rotate in the Screen & light panel opens Android's matching screen
  (`ScreenPanel.Hold`: ACTION_WIFI_SETTINGS, ACTION_BLUETOOTH_SETTINGS, ACTION_AIRPLANE_MODE_SETTINGS else
  ACTION_WIRELESS_SETTINGS, android.settings.AUTO_ROTATE_SETTINGS on API 34+ else ACTION_DISPLAY_SETTINGS; the first
  that resolves, FLAG_ACTIVITY_NEW_TASK from the service), then the panel closes. Tap unchanged. The descriptions
  end "Hold: Wi-Fi settings", and the long-click accessibility action is labelled the same.

## Sleep screen picker (branch `face-picker`, 2026-11-16)
- Asked: a flip-through picker replacing the face grid and the per-face style screens; mockup
  docs/mockups/aod/face-picker/ (picker-sheet.png frames 1–6, `build.py`); landscape = option 6 (the face's
  landscape layout left, controls right; 6b was the rejected portrait-shaped alternative).
- `FacePicker` (pure, FacePickerTest): pages = `Aod.Face` order × styles (`Agenda.Style`, `Home.Style`,
  `Dither.Style` for Photo), 18 now. `Aod.Face` was reordered to the approved order (Clock, Clock + date, Analog,
  Home, Agenda, Reading, Photo, Picture, Minimal, Default; prefs store names, so nothing migrates) and got `group`
  (the All grid's headings, `Aod.Group`) and `detail`. `inUse`: face + its style (Picture whatever the Photo style);
  `need`: the line under the button and its setup (CALENDAR, WEATHER, PHOTO, READER).
- Picture/Photo mapping: "Photo · Crisp" = face PHOTO + `aod_photo_style` CRISP (`Aod.choose` saves the style, then
  `setFace` installs once). Picture is a single page drawn in the current Photo style (its line says which); while
  Picture is in use, the Sleep screen page has a "Photo style" chips row (Crisp / Smooth / Plain) for it.
- `FacePickerActivity` (exported in debug; opened from Sleep screen → "Sleep screen: Agenda · Week ▶"): previews
  are `Aod.thumbnail` at half size (`divisor` = 2; tiles 4) in the activity's orientation, on one background
  thread, the shown page then its neighbours, ≤ 8 pages kept; `FacePreview` fits and frames the bitmap at its own
  shape. Flip: ‹ ›, a horizontal swipe anywhere (the touch is cancelled for the views under it), a tap on the
  preview's left or right 30%; wraps around; no animation. "Use this" → `Aod.choose`, then "✓ In use"; when the
  face still needs setup it is picked and then the setup opens (Picture without a picture: the photo is picked
  first, then Picture). The setup line opens Weather location / Reading / the system picker / the calendar
  permission (once denied for good: app settings) without picking the face; a photo picked there is kept
  (`SleepImages.savePicture`) and reinstalled only if Photo or Picture is in use.
- Removed: the face grid (`faceTile`, `thumbTile`, `pickFace`) and the Agenda style, Home style and Photo style
  screens (sections `agenda`, `home`, `photo`). Sleep screen keeps: the picker row, Choose picture… (Photo/Picture),
  Photo style (Picture), Home's weather/units/countdowns/trash rows, the calendar box, Update and the rest.
- `--es dump faces` on `Aod$Alarm` (root) writes every page in both orientations to files/faces/ (as the picker
  draws them). All landscape layouts checked that way on the device: fine. Picture has no landscape layout (it's
  installed as the portrait picture), so its landscape preview is the portrait picture, upright; Day rail in
  landscape is narrow with wide margins (as before).
- "Show update time" (asked): Sleep screen → Update, a switch under the interval (on by default). Off, no face draws
  the "Updated HH:MM" footer (`AodFaces.Data.stamp`/`footer`: the bottom line and Photo's clock box); faces ending at
  `limit`/`homeBottom` get the room back. Home's stale-weather "as of" stays. Pref `aod_show_stamp`.

## Redraws wait for the screen to settle (branch `face-picker`, 2026-11-16)
- Incident: the phone froze (PowerManagerService blocked, display stuck) and was rebooted. Saved logs
  (scratchpad freeze-20261116/logd/) at 02:43:19: "Waking up from Dozing … POWER_BUTTON", the screen-off broadcast
  taking 5.7 s, InkPalm+ "aod: sleep orient: … installed 1, redraw now", then "Blocked in handler on
  PowerManagerService for 15s". The immediate sleep-orientation redraw (`fixAtSleep`: bridge `redraw=1` →
  KEYCODE_WAKEUP/SLEEP) is suspected of racing the user's power press.
- Rule now (`Aod.RedrawGate`, AodLogicTest): an invisible redraw goes only when the screen has been off for ≥ 6 s
  (`SETTLE_MS`) since the last SCREEN_ON/SCREEN_OFF/USER_PRESENT (`Aod.screenTransition`, from the service's power
  receiver, bumps `generation`), the display state is OFF/DOZE and not interactive, nothing changed since it was
  asked (sleep fix: same generation), no redraw is in flight (`redrawing()`), and the last bridge redraw was ≥ 10 s
  ago (`GAP_MS`). The sleep fix is scheduled 6 s after the screen-off under a short wake lock and dropped otherwise
  (the next Live update draws the right face); Live updates use the same gate (an alarm right after a power press is
  dropped and the next one armed). Logs: "aod: sleep orient: … redraw in 6 s if still asleep", "aod: sleep redraw:
  settled, …", "aod: sleep redraw skipped: woke …/recent transition/busy …", "aod: live update skipped: …".

## Settings reorganised (branch `settings-reorg`, on `face-picker`, 2026-11-16)
- Approved mockup: docs/mockups/settings-reorg/ (settings-sheet.png, frames 0–9; build.py imports
  ../aod/face-picker/build.py). Home: Sleep screen · Screen & light · Side panel · Power off · Buttons & reading ·
  About, each with a one-line summary (`Summaries`). Max two levels (Sleep screen › Weather / Countdowns / Trash day).
- Paging instead of scrolling: each screen is a list of groups; `MainActivity.show()` measures them at the page's
  width (any orientation, font scale) and `Paging.split`s them; ‹ › "1 / 2" at the bottom; forced breaks: Sleep
  screen's "Home face" page, Screen & light's Front light page, Side panel's Strip page. A page scrolls only when a
  single group is taller than it. The page shown survives a rotation (by its first group). Keyboard pans
  (SOFT_INPUT_ADJUST_PAN) so typing a city doesn't re-page.
- Where things went: Sleep & power → Sleep screen + Power off; Front light → Screen & light p.2; Screen & e-ink
  (KompaktOS) → Screen & light (new: screen mode everywhere list, Full refresh now, Contrast stepper — the same
  prefs as the screen panel); the e-ink status box only when there's no bridge or InkControl is still installed;
  Reading + Buttons → Buttons & reading (KompaktOS hold = Nothing / Full refresh chips on `holdRefresh`; note
  "Volume keys always turn pages the same way in every rotation", KompaktOS only); the "Show seconds" and
  "Frontlight" info rows became notes. Stock firmware: Screen & light opens the old e-ink screen (scrolls).
- Old section names still open the screen they moved to, at their page (`aod`, `light`, `keep_on`, `touch`,
  `reading`); FacePicker's setup links (weather, reading) and the screen panel's ⚙ (screen) unchanged.
- "Fix microG location" (asked; separate commit): on this phone "Use my location" only works once microG Services has
  FINE + BACKGROUND location and appops FINE_LOCATION allow; Android's page doesn't offer "all the time" for it. A
  row on Weather location (and a button in the failure box) when microG lacks either; confirm box; then bridge
  `perm.req` (`microg_location=1`; only the next KompaktOS image's eink-apply.sh handles it, so success is
  re-checked after 2 s), Shizuku `pm grant`/`appops set`, else microG's app settings with instructions.
  `Home.microgFixNeeded`/`microgNextStep` tested (MicrogFixTest). The user's phone was fixed by hand, so on the
  device the row is hidden; the grant path itself is untested on the device.
- Network blocked vs offline (asked; separate commit): LineageOS's restricted networking mode
  (`restricted_networking_mode`=1) blocked InkPalm+ (blockedReasons=8) while Wi‑Fi was up; `Weather.online` said
  offline, so nothing was fetched or logged. `NetCheck` (tested) tells blocked from offline (our activeNetwork vs the
  phone's networks, plus the setting); blocked + a city: a box "InkPalm+ has no network access…" with "Allow network
  access" (App info) on Weather location and the Home face page; the face says "Weather: no network access for
  InkPalm+" when it has no data (old data keeps "as of"). The user has since allowed it, so on the device the box is
  hidden; the blocked path is untested there.
- "Weather updates" (asked; separate commit): Sleep screen › Home face: Every refresh (default) / 15 / 30 / 60 min
  (pref `home_weather_every`). `Home.weatherGap`: Every refresh = 4 min floor asleep (so each Live update at 5 min+
  fetches) and 5 min awake (minute ticks); else the chosen minutes. Frozen: one fetch at the screen-off render when
  the gap allows. Notes: Open-Meteo's ~15 min current conditions, ~4 KB a fetch, requests a day (`weatherPerDay`:
  288 at 5 min). Still only with a network up, 8 s deadline, no retries, never wakes the phone itself.

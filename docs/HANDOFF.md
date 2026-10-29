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
- Only with `accelerometer_rotation` = 1; off, the sleep orientation is kept as before.
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
- Known: Day rail pushes labels apart when events crowd, so a label can sit below its block; at night the rail
  runs from midnight (it always takes in now). 12 h times have no AM/PM (as Classic).

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

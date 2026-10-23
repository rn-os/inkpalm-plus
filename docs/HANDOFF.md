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
  (files/vendor_poweroff.png; bridge change requested); compose checked with a hand-made copy.
- Power key sometimes doesn't wake (user report): reproduced as a system issue (also with InkPalm+'s key filter
  off, also with `input keyevent 26`); handed to the KompaktOS session.
- Still to do by hand: the white repaint flash and Fast vs Quality on a gradient; tiles in KompaktOS QS; the user
  should uninstall InkControl (both drive modes and light while it's installed).

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

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

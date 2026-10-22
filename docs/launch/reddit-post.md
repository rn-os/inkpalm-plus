**Suggested subreddits:** r/eink, r/dumbphones, r/minimalistphone

**Title:** I built a recent-apps side panel and per-app e-ink tuning for the Moaan InkPalm Plus (free, open source)

**Images (in order)** (in `docs/launch/reddit/`):

1. `1-side-panel.png`: the panel over an app
2. `2-hold-menu.png`: holding an app for its menu
3. `3-landscape.png`: the panel in landscape
4. `4-eink-panel.png`: the e-ink panel for the app in front
5. `5-eink-settings.png`: the E-ink settings screen
6. `6-home.png`: the home screen

---

After building SuperBoox for my BOOX, I wanted the same quick app switching on my InkPalm Plus, plus the kind of per-app e-ink tuning BOOX has with EinkWise. So I built it.

InkPalm+ is a small overlay app. Swipe up or down along the edge of the screen in any app and a panel slides in with:

- your recent apps, newest first
- the app you were just in, marked with a ← badge, one tap away
- Clear all, and a ✕ on each app
- a button for the e-ink settings of the app in front

A few things I wanted to get right:

- Pages you swipe instead of scrolling, so nothing smears on e-ink. It opens in about 30 ms and works in landscape.
- The e-ink panel sets Preset, Size (DPI, live), Contrast and Rotate for each app. The InkPalm has no rotation sensor, so Rotate is how you turn the screen.
- Pick a font, and change what the touch button does on tap and hold.
- Black and white, no animations.
- No internet permission. Everything stays on the phone.

I've only tested it on the InkPalm Plus (firmware MAS_EPD305_L81B804_T44_V09, Android 11). The e-ink features use Moaan's firmware, so they won't work on other phones.

**Install**

1. In Obtainium, add `https://github.com/rn-os/inkpalm-plus` (or grab the APK from Releases)
2. Open InkPalm+, tap Side panel and turn on the accessibility service
3. Optional: in "Keeping the panel on", add InkPalm+ to the Clear Memory whitelist so the panel isn't switched off (needs Shizuku or a one-time adb command; the app shows it)

GitHub: https://github.com/rn-os/inkpalm-plus

Built with Claude Code. Feedback and feature requests welcome.

# Quick panel over a running app: changes must apply live, without reopening the app.
# Driven through the debug test hooks (QuickPanelService.test): `uiautomator dump` would unbind the
# accessibility service, so the panel's own views aren't dumped. Needs the quick panel switched on in
# Accessibility and "Modify system settings" allowed (live DPI); the checks say so when they're not.

# hook <cmd> [value]: sends a test command; returns non-zero unless the service answered "ok".
hook() {
  adb_ logcat -c
  # adb drops empty arguments, so "none" stands for no value.
  adb_ shell am broadcast -a com.inkpalmplus.TEST -p $PKG --es cmd "$1" --es value "${2:-none}" >/dev/null
  local i line
  for i in 1 2 3 4 5 6; do
    line=$(adb_ logcat -d -s InkPalmPlus:I | grep " test $1 " | tail -1)
    [ -n "$line" ] && break
    sleep 0.5
  done
  HOOK_OUT="$line"
  case "$line" in *" ok "*) return 0 ;; *) return 1 ;; esac
}

# Opens the target app and the quick panel over it.
open_panel_over_target() {
  # uiautomator dumps (the per-app checks) unbind the service for a few seconds; wait for it to come back.
  local i up=""; for i in $(seq 1 20); do hook state && { up=1; break; }; sleep 1; done
  [ -n "$up" ] || { note_fail "quick panel service isn't running (switch InkPalm+ on in Accessibility)"; return 1; }
  adb_ shell am force-stop "$TARGET"
  adb_ shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  hook open || { note_fail "panel didn't open: $HOOK_OUT"; return 1; }
  case "$HOOK_OUT" in *"panel=$TARGET"*) note_ok "panel opened on $TARGET" ;; *) note_fail "panel opened on the wrong app: $HOOK_OUT"; return 1 ;; esac
  sleep 2   # the panel loads the app's row
}

prop() { adb_ shell getprop "$1" | tr -d '\r'; }

check_contrast_live() {
  open_panel_over_target || return
  hook contrast 0.5 || { note_fail "contrast hook: $HOOK_OUT"; return; }
  sleep 1.5
  [ "$(prop debug.sf.gamma.gamma)" = 0.5 ] && note_ok "screen gamma is 0.5 now" || note_fail "gamma is $(prop debug.sf.gamma.gamma), wanted 0.5"
  expect_row "saved to the row" app_contrast 0.5
  hook contrast none >/dev/null   # back to system contrast
  sleep 1.5
  [ "$(prop debug.sf.gamma.gamma)" != 0.5 ] && note_ok "system contrast restored ($(prop debug.sf.gamma.gamma))" || note_fail "gamma still 0.5 after System"
  manual "the panel and app visibly darken and lighten"
}

check_refresh_live() {
  open_panel_over_target || return
  hook refresh 12 || { note_fail "refresh hook: $HOOK_OUT"; return; }
  sleep 1.5
  [ "$(prop persist.sys.mogu.refresh_type)" = 12 ] && note_ok "refresh type is A2 (12) now" || note_fail "refresh_type is $(prop persist.sys.mogu.refresh_type), wanted 12"
  [ "$(prop sys.eink.mode)" = 7 ] && note_ok "waveform base 7, as SystemUI sets it" || note_fail "sys.eink.mode is $(prop sys.eink.mode), wanted 7"
  expect_row "saved to the row" refresh_mode 12
  hook full 20 >/dev/null; sleep 1.5
  [ "$(prop persist.vendor.fullmode_cnt)" = 20 ] && note_ok "full refresh every 20 now" || note_fail "fullmode_cnt is $(prop persist.vendor.fullmode_cnt), wanted 20"
  hook refresh -1 >/dev/null; hook full -1 >/dev/null; sleep 1.5
  local qs; qs=$(adb_ shell settings get global moan_eink_mode | tr -d '\r')
  [ "$(prop persist.sys.mogu.refresh_type)" = "$qs" ] && note_ok "System goes back to the Quick Settings mode ($qs)" || note_fail "refresh_type $(prop persist.sys.mogu.refresh_type) after System, Quick Settings has $qs"
  [ "$(prop persist.vendor.fullmode_cnt)" = 40 ] && note_ok "System full-refresh count is 40, as SystemUI's" || note_fail "fullmode_cnt $(prop persist.vendor.fullmode_cnt) after System, wanted 40"
  manual "page turns look like the chosen mode (A2 is fast with ghosting)"
}

check_dpi_live_without_reopening() {
  open_panel_over_target || return
  local pid; pid=$(adb_ shell pidof "$TARGET" | tr -d '\r')
  adb_ logcat -c
  hook dpi 240 || { note_fail "dpi hook: $HOOK_OUT"; return; }
  sleep 5   # the panel waits ~1 s for more taps, then the app recreates
  local d; d=$(adb_ logcat -d -s app_dpi:D | grep -o "performCreate.dpi=[-0-9]*,PackageName=$TARGET" | tail -1 | sed -E 's/performCreate.dpi=([-0-9]+).*/\1/')
  local pid2; pid2=$(adb_ shell pidof "$TARGET" | tr -d '\r')
  expect_row "saved to the row" app_dpi 240
  if [ "$d" = 240 ]; then note_ok "app redrew at 240 dpi"; else
    adb_ logcat -d -s InkPalmPlus:I | grep -q "nudge.*false" && note_fail "live DPI needs \"Modify system settings\" for InkPalm+" || note_fail "app didn't redraw at 240 (last: '${d:-none}')"
  fi
  [ -n "$pid" ] && [ "$pid" = "$pid2" ] && note_ok "same process (not reopened)" || note_fail "process changed ($pid -> $pid2)"
  hook dpi -1 >/dev/null; sleep 4
}

rotation() { adb_ shell dumpsys window displays | grep -m1 -oE "mRotation=[0-9]" | cut -d= -f2; }

check_rotation_kept_per_app() {
  open_panel_over_target || return
  hook rotate LANDSCAPE || { note_fail "rotate hook: $HOOK_OUT"; return; }
  sleep 2
  [ "$(rotation)" = 1 ] && note_ok "screen turned to landscape" || note_fail "rotation is $(rotation), wanted 1"
  adb_ shell input keyevent HOME; sleep 2.5
  [ "$(rotation)" = 0 ] && note_ok "home screen back in portrait" || note_fail "home rotation $(rotation), wanted 0"
  adb_ shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
  [ "$(rotation)" = 1 ] && note_ok "target turns landscape again when it reopens" || note_fail "target rotation $(rotation), wanted 1"
  hook rotate AUTO >/dev/null; sleep 2
  [ "$(rotation)" = 0 ] && note_ok "Auto gives the screen back (portrait)" || note_fail "rotation $(rotation) after Auto, wanted 0"
  manual "the panel is a side sheet in landscape and the app stays readable beside it"
}

check_value_list_and_arrows() {
  open_panel_over_target || return
  hook list REFRESH || { note_fail "list hook: $HOOK_OUT"; return; }
  hook pick 3 || { note_fail "pick hook: $HOOK_OUT"; return; }   # System, Normal, Auto, Fast…
  sleep 1.5
  expect_row "picking Fast from the list saves it (DU, 14)" refresh_mode 14
  hook arrow REFRESH:- || { note_fail "arrow hook: $HOOK_OUT"; return; }
  sleep 1.5
  expect_row "◀ steps back to Auto (0)" refresh_mode 0
  hook arrow REFRESH:- >/dev/null; hook arrow REFRESH:- >/dev/null; sleep 1.5
  expect_row "◀ twice more reaches System" is_refresh_setting 0
  manual "the panel fits in both orientations with nothing cut off (docs/mockups/02-arrows.png)"
}

# The strip's window frame "[l,t][r,b]" from dumpsys (the only InkPalm+ window ~35% of the screen tall).
strip_frame() { adb_ shell dumpsys window windows | grep -A30 "Window{.*com.inkpalmplus}" | grep -oE "mFrame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | sed -E 's/mFrame=//' | awk -F'[][,]' '{ if ($6 - $3 > 300 && $6 - $3 < 700) { print; exit } }'; }

check_strip_width_and_side() {
  local prefs w0 r0
  prefs=$(adb_ shell run-as $PKG cat shared_prefs/inkpalmplus.xml 2>/dev/null)
  w0=$(echo "$prefs" | grep -o 'strip_width" value="[0-9]*' | grep -o '[0-9]*$'); w0=${w0:-16}
  r0=$(echo "$prefs" | grep -o 'edge_right" value="[a-z]*' | grep -o '[a-z]*$'); r0=${r0:-true}
  local i; for i in $(seq 1 20); do hook state && break; sleep 1; done
  hook strip 24 || { note_fail "strip hook: $HOOK_OUT"; return; }; sleep 1.5
  local f; f=$(strip_frame)
  local l r; l=$(echo "$f" | awk -F'[][,]' '{print $2}'); r=$(echo "$f" | awk -F'[][,]' '{print $5}')
  [ $((r - l)) -ge 40 ] && [ $((r - l)) -le 42 ] && note_ok "24 dp strip is $((r - l)) px wide" || note_fail "24 dp strip is '$f'"
  [ "$r" = 720 ] && note_ok "on the right edge" || note_fail "strip not on the right edge: $f"
  hook side left >/dev/null; sleep 1.5
  f=$(strip_frame); l=$(echo "$f" | awk -F'[][,]' '{print $2}')
  [ "$l" = 0 ] && note_ok "Left puts it on the left edge" || note_fail "strip not on the left edge: $f"
  hook strip "$w0" >/dev/null; hook side "$([ "$r0" = true ] && echo right || echo left)" >/dev/null
  manual "a swipe in from the strip still goes Back (system gesture); up/down opens the panel"
}

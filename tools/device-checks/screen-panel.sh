# Screen & light panel (KompaktOS, InkControl merged in; mockup 19). Driven through the debug test hooks, like
# side-panel.sh. Needs KompaktOS with the root bridge, adb root (getprop of sys.eink.* and sys.gray.*), and
# InkPalm+ switched on in Accessibility. Puts the device-wide mode and contrast back at the end of each check.

# screen_state: "pkg=... forApp=... global=<key> mode=<key> code=N source=APP|KOMPAKT|EVERYWHERE contrast=N backend=..."
screen_state() {
  hook screen_state >/dev/null
  adb_ logcat -d -s InkPalmPlus:I | grep " screen: " | tail -1 | sed 's/.* screen: //' | tr -d '\r'
}
field() { echo "$1" | tr ' ' '\n' | grep "^$2=" | cut -d= -f2; }

screen_reset() {
  local i up=""; for i in $(seq 1 20); do hook state && { up=1; break; }; sleep 1; done
  [ -n "$up" ] || { note_fail "service isn't running (switch InkPalm+ on in Accessibility)"; return 1; }
  hook side_close >/dev/null; hook screen_close >/dev/null
  hook screen >/dev/null || { note_fail "screen panel didn't open: $HOOK_OUT"; return 1; }
  SCREEN_BEFORE=$(screen_state)
  echo "$SCREEN_BEFORE" | grep -q "backend=BRIDGE\|backend=DIRECT" || { note_fail "not KompaktOS: $SCREEN_BEFORE"; return 1; }
}

# Puts back the device-wide mode and contrast seen by screen_reset.
screen_restore() {
  hook screen >/dev/null
  hook screen_scope all >/dev/null
  hook screen_mode "$(field "$SCREEN_BEFORE" global)" >/dev/null
  hook contrast "$(field "$SCREEN_BEFORE" contrast)" >/dev/null
  hook screen_close >/dev/null
}

check_modes_everywhere() {
  screen_reset || return
  hook screen_scope all >/dev/null
  local kv key code got
  for kv in fast:12 soft:14 fastdither:13 text:8 quality:7 auto:22; do
    key=${kv%%:*}; code=${kv##*:}
    hook screen_mode "$key" >/dev/null; sleep 1
    got=$(prop sys.eink.mode)
    [ "$got" = "$code" ] && note_ok "$key → sys.eink.mode $code" || note_fail "$key: sys.eink.mode is '$got', wanted $code"
  done
  manual "Each pick flashed the screen white once (repaint) and Fast vs Quality look different on a gradient"
  screen_restore
}

check_mode_for_app() {
  screen_reset || return
  adb_ shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
  hook screen >/dev/null
  hook screen_scope app >/dev/null
  hook screen_mode text >/dev/null; sleep 1
  local s; s=$(screen_state)
  [ "$(field "$s" source)" = APP ] && note_ok "Text kept for $TARGET" || note_fail "source not APP: $s"
  [ "$(prop sys.eink.mode)" = 8 ] && note_ok "sys.eink.mode 8 in $TARGET" || note_fail "sys.eink.mode $(prop sys.eink.mode) in $TARGET"
  hook screen_close >/dev/null
  go_home; sleep 2
  local g; g=$(field "$SCREEN_BEFORE" global)
  [ "$(prop sys.eink.mode)" != 8 ] || [ "$g" = text ] && note_ok "home screen back on the device-wide mode ($(prop sys.eink.mode))" || note_fail "home screen still on 8"
  adb_ shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
  [ "$(prop sys.eink.mode)" = 8 ] && note_ok "$TARGET gets 8 again on return" || note_fail "on return: $(prop sys.eink.mode)"
  hook screen >/dev/null
  hook screen_scope all >/dev/null; sleep 1
  s=$(screen_state)
  [ "$(field "$s" source)" = EVERYWHERE ] && note_ok "Use everywhere clears the app's own" || note_fail "after Use everywhere: $s"
  screen_restore; go_home
}

check_full_refresh() {
  screen_reset || return
  local t0 t1; t0=$(prop sys.eink.one_full_mode_timeline)
  hook screen_refresh >/dev/null; sleep 1
  t1=$(prop sys.eink.one_full_mode_timeline)
  [ "${t1:-0}" -gt "${t0:-0}" ] 2>/dev/null && note_ok "one_full_mode_timeline $t0 → $t1" || note_fail "timeline didn't move: '$t0' → '$t1'"
  screen_restore
}

check_contrast() {
  screen_reset || return
  hook contrast 50 >/dev/null; sleep 1
  [ "$(prop sys.gray.gammalevel)" = 50 ] && note_ok "sys.gray.gammalevel 50" || note_fail "sys.gray.gammalevel '$(prop sys.gray.gammalevel)'"
  screen_restore; sleep 1
  [ "$(prop sys.gray.gammalevel)" = "$(field "$SCREEN_BEFORE" contrast)" ] && note_ok "contrast restored" || note_fail "contrast not restored"
}

check_edge_back() {
  screen_reset || return
  hook screen_close >/dev/null
  hook edge_back on >/dev/null
  adb_ shell am start -a android.settings.DISPLAY_SETTINGS >/dev/null; sleep 3
  local before after w h; before=$(top_package)
  w=$(adb_ shell wm size | tr -d '\r' | sed 's/.*: //;s/x.*//'); h=$(adb_ shell wm size | tr -d '\r' | sed 's/.*x//')
  # Inward from the left edge, in the Back-only part (20% down), then from the right edge mid-way (panel strip).
  adb_ shell input swipe 2 $((h / 5)) $((w / 3)) $((h / 5)) 200; sleep 2
  after=$(top_package)
  [ "$after" != com.android.settings ] && note_ok "left-edge inward swipe went back ($before → $after)" || note_fail "still in $after after the left swipe"
  manual "With Edge Back on, the strips still open their panels with a swipe along them"
  go_home
}

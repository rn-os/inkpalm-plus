# Side panel of recent apps (the edge strip's swipe). Driven through the debug test hooks, like quick-panel.sh
# (hook() is in device-test.sh): `uiautomator dump` would unbind the accessibility service. The recent list is
# snapshotted by device-test.sh and put back at the end. Needs InkPalm+ switched on in Accessibility.

# Waits for the service, closes the panel, empties the recent list.
side_reset() {
  local i up=""; for i in $(seq 1 20); do hook state && { up=1; break; }; sleep 1; done
  [ -n "$up" ] || { note_fail "service isn't running (switch InkPalm+ on in Accessibility)"; return 1; }
  hook side_close >/dev/null; hook recents_clear >/dev/null
}

# side_state: prints "pkg1,pkg2,... page=N/M" or "closed".
side_state() {
  hook side_state >/dev/null
  adb_ logcat -d -s InkPalmPlus:I | grep " side: " | tail -1 | sed 's/.* side: //' | tr -d '\r'
}

# A real app switch: target, home, then Android's Settings in front. Settings ends up first in the list, the target second.
real_switch() {
  go_home; sleep 2
  adb_ shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
  go_home; sleep 2
  adb_ shell am start -a android.settings.SETTINGS >/dev/null; sleep 3
}

# launchable_packages <n>: up to n installed launcher apps other than InkPalm+, the target and Settings.
launchable_packages() {
  adb_ shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    | tr -d '\r' | grep -E '^ +[A-Za-z0-9_.]+/' | sed -E 's/^ +([^/]+)\/.*/\1/' | sort -u \
    | grep -vxE "$PKG|$TARGET|com.android.settings" | head -n "$1"
}

check_records_app_switch() {
  side_reset || return
  real_switch
  hook side || { note_fail "side panel didn't open: $HOOK_OUT"; return; }
  local s; s=$(side_state); local list=${s% page=*}
  echo "side: $s" > "$OUT/$CHECK-state.txt"
  [ "${list%%,*}" = com.android.settings ] && note_ok "Settings is first" || note_fail "first is not Settings: $s"
  local second; second=$(echo "$list" | cut -d, -f2)
  [ "$second" = "$TARGET" ] && note_ok "$TARGET is second" || note_fail "second is '$second', wanted $TARGET: $s"
  hook side_close >/dev/null; go_home
}

check_paging() {
  side_reset || return
  local p n=0; for p in $(launchable_packages 12); do hook remember "$p" >/dev/null; n=$((n + 1)); done
  [ $n -ge 10 ] || { note_fail "only $n launchable apps to seed, need 10 or more for two pages"; return; }
  go_home; sleep 2
  hook side || { note_fail "side panel didn't open: $HOOK_OUT"; return; }
  local s; s=$(side_state); echo "$s" | grep -q 'page=1/2$' && note_ok "two pages ($s)" || { note_fail "wanted page=1/2: $s"; return; }
  hook side_page next >/dev/null
  s=$(side_state); echo "$s" | grep -q 'page=2/2$' && note_ok "side_page next goes to page 2" || note_fail "after next: $s"
  hook side_page prev >/dev/null
  s=$(side_state); echo "$s" | grep -q 'page=1/2$' && note_ok "side_page prev goes back to page 1" || note_fail "after prev: $s"
  adb_ shell input swipe 450 900 450 400 250; sleep 1
  s=$(side_state); echo "$s" | grep -q 'page=2/2$' && note_ok "a real swipe up in the panel turns to page 2" || { note_fail "after swipe up: $s"; shot "fail-swipe"; }
  adb_ shell input swipe 450 400 450 900 250; sleep 1
  s=$(side_state); echo "$s" | grep -q 'page=1/2$' && note_ok "a real swipe down turns back to page 1" || note_fail "after swipe down: $s"
  hook side_close >/dev/null; go_home
}

check_tap_switches_app() {
  side_reset || return
  real_switch
  hook side || { note_fail "side panel didn't open: $HOOK_OUT"; return; }
  local list; list=$(side_state); list=${list% page=*}
  local idx=-1 i=0 p; for p in ${list//,/ }; do [ "$p" = "$TARGET" ] && idx=$i; i=$((i + 1)); done
  [ $idx -ge 0 ] || { note_fail "$TARGET not in the list: $list"; return; }
  hook side_tap $idx || { note_fail "side_tap $idx: $HOOK_OUT"; return; }
  sleep 3
  [ "$(top_package)" = "$TARGET" ] && note_ok "$TARGET is in front" || note_fail "top package is $(top_package), wanted $TARGET"
  [ "$(side_state)" = closed ] && note_ok "panel closed after the tap" || note_fail "panel still open: $(side_state)"
  go_home
}

check_close_app() {
  side_reset || return
  real_switch
  hook side || { note_fail "side panel didn't open: $HOOK_OUT"; return; }
  local list; list=$(side_state); list=${list% page=*}
  local idx=-1 i=0 p; for p in ${list//,/ }; do [ "$p" = "$TARGET" ] && idx=$i; i=$((i + 1)); done
  [ $idx -ge 0 ] || { note_fail "$TARGET not in the list: $list"; return; }
  hook side_close_app $idx || { note_fail "side_close_app $idx: $HOOK_OUT"; return; }
  adb_ logcat -d -s InkPalmPlus:I | grep -q "closed $TARGET" && note_ok "logged 'closed $TARGET'" || note_fail "no 'closed $TARGET' in the log"
  side_state | grep -q "$TARGET" && note_fail "$TARGET still listed" || note_ok "$TARGET is off the list"
  side_state | grep -q com.android.settings && note_ok "the app in front stays" || note_fail "Settings vanished from the list"
  hook side_close >/dev/null; go_home
}

check_clear_all() {
  side_reset || return
  real_switch
  local p; for p in $(launchable_packages 2); do hook remember "$p" >/dev/null; done
  # remembering put them in front of Settings in the list; the one in front is still Settings
  hook side || { note_fail "side panel didn't open: $HOOK_OUT"; return; }
  [ "$(side_state | sed "s/ page=.*//" | tr "," "\n" | grep -c .)" -ge 3 ] || note_fail "expected several apps before clearing: $(side_state)"
  hook side_clear_all || { note_fail "side_clear_all: $HOOK_OUT"; return; }
  adb_ logcat -d -s InkPalmPlus:I | grep -q "cleared all recent apps" && note_ok "logged 'cleared all recent apps'" || note_fail "no clear log"
  local s; s=$(side_state)
  [ "$s" = "com.android.settings page=1/1" ] && note_ok "only the app in front is left" || note_fail "after clear: $s"
  hook side_close >/dev/null; go_home
}

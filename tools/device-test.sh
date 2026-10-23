#!/usr/bin/env bash
# Scripted InkPalm checks over adb. Run from a Mac with the InkPalm connected.
# Ported from SuperBoox's tools/tablet-test.sh: text-only results, screenshots only on failure.
#
#   tools/device-test.sh [options] run <feature>...   run tools/device-checks/<feature>.sh
#   tools/device-test.sh list                         list features and their checks
#
#   --apk FILE      install this build first (default: app/build/outputs/apk/debug/app-debug.apk if newer than the installed one)
#   --target PKG    the app the checks tune (default de.szalkowski.activitylauncher)
#   --label NAME    its name in the app list (default "Activity Launcher")
#   --serial ID     adb serial (default: the only device)
#   --out DIR       results folder (default tools/device-results/<timestamp>)
#
# Safety rules, enforced here so a check can't break them:
#   - InkPalm+ is never force-stopped: that kills the quick panel service and Android switches it off.
#   - Only InkPalm+'s own screens are tapped, always by finding a node by text (never raw coordinates),
#     and only while InkPalm+ is in front.
#   - The target app's row in Moaan's e-ink table is snapshotted first and put back at the end (also on Ctrl-C),
#     together with its DPI in PackageManager. No other app's row is written.
#   - Global settings (animations, text size) and the live display properties (gamma, waveform,
#     full-refresh count) are snapshotted and put back.
# Result: <out>/summary.md (pass/fail table, notes and log tail for failures), results.tsv, failure screenshots.
set -u
cd "$(dirname "$0")/.."
ROOT=$(pwd)
PKG=com.inkpalmplus
URI=content://com.android.systemui.eink/einksettings
APK=""; SERIAL=""; OUT=""; TARGET=de.szalkowski.activitylauncher; TARGET_LABEL="Activity Launcher"

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --label) TARGET_LABEL="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) break ;;
  esac
done
CMD="${1:-}"; [ $# -gt 0 ] && shift

# Every adb call gets a time limit (ADB_TIMEOUT seconds, default 20): a stuck SystemUI must not hang the run.
adb_() {
  if [ -n "$SERIAL" ]; then set -- -s "$SERIAL" "$@"; fi
  perl -e 'alarm shift; exec @ARGV' "${ADB_TIMEOUT:-20}" adb "$@"
}

# Moaan's provider lives in SystemUI; if it stops answering, stop before touching anything else.
# KompaktOS (KOMPAKT=1, set below when the root bridge is in the image) has no such table: check the system answers.
healthy() {
  if [ -n "${KOMPAKT:-}" ]; then ADB_TIMEOUT=8 adb_ shell service check activity >/dev/null 2>&1; return; fi
  ADB_TIMEOUT=8 adb_ shell content query --uri $URI --projection id --where "\"package_name='android'\"" >/dev/null 2>&1
}

list_features() {
  for f in tools/device-checks/*.sh; do
    echo "$(basename "$f" .sh): $(grep -E '^check_[a-z0-9_]+\(\)' "$f" | sed -E 's/\(\).*//' | tr '\n' ' ')"
  done
}

if [ "$CMD" = "list" ]; then list_features; exit 0; fi
if [ "$CMD" != "run" ] || [ $# -eq 0 ]; then sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 2; fi
FEATURES=("$@")
for f in "${FEATURES[@]}"; do [ "$f" = all ] || [ -f "tools/device-checks/$f.sh" ] || { echo "no such feature: $f (try: list)"; exit 2; }; done

OUT="${OUT:-$ROOT/tools/device-results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
RESULTS="$OUT/results.tsv"; : > "$RESULTS"
SUMMARY="$OUT/summary.md"
CHECK=""; CHECK_FAILED=0; CHECK_NOTES=""; ABORTED=""

# ---- helpers available to check files ---------------------------------------------------------

log() { echo "[$(date +%H:%M:%S)] $*" >&2; }
abort() { ABORTED="$*"; log "ABORT: $*"; exit 3; }
shot() { adb_ exec-out screencap -p > "$OUT/$CHECK-$1.png" 2>/dev/null; }

top_package() {
  adb_ shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | head -1 \
    | sed -E 's/.* ([A-Za-z0-9_.]+)\/[^ ]*.*/\1/'
}

# The home screen, started directly: the Home key may be remapped by the touch button settings
# (Home acts as Back when Tap is set to Back), so the harness never presses it.
go_home() { adb_ shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; }

# Taps and typing only ever go to InkPalm+.
guard() { local top; top=$(top_package); [ "$top" = "$PKG" ] || abort "'$top' is in front during '$CHECK', not InkPalm+"; }

dump() {
  DUMP="$OUT/$CHECK-ui.xml"
  adb_ shell uiautomator dump /sdcard/ipp-ui.xml >/dev/null 2>&1
  adb_ exec-out cat /sdcard/ipp-ui.xml > "$DUMP" 2>/dev/null
  [ -s "$DUMP" ]
}
pyfind() { python3 -I "$ROOT/tools/find-node.py" "$DUMP" "$@"; }

# tap <kind> <value> [--first]: dump, guard, then tap the one matching node.
tap() {
  guard
  dump || { note_fail "tap $2: no UI dump"; return 1; }
  local pos; pos=$(pyfind find "$@") || { note_fail "tap: $1 '$2' not found (or ambiguous)"; shot "fail-tap"; return 1; }
  set -- $pos
  adb_ shell input tap "$1" "$2"
  sleep 1
}

# scroll_to <kind> <value>: swipes up (up to 5 times) until the node is on screen.
scroll_to() {
  local i; for i in 1 2 3 4 5 6; do
    dump && pyfind has "$@" && return 0
    guard; adb_ shell input swipe 360 1150 360 500 300; sleep 1
  done
  return 1
}

# expect <label> <kind> <value>: is there such a node on screen now?
expect() {
  local label="$1"; shift
  dump || { note_fail "$label: no UI dump"; return 1; }
  if pyfind has "$@"; then note_ok "$label"; else note_fail "$label (no $1 '$2' on screen)"; shot "fail"; return 1; fi
}

type_text() { guard; adb_ shell input text "$1"; sleep 1; }

# The target's row in Moaan's table, one "key=value" per line.
row() { adb_ shell content query --uri $URI --where "\"package_name='${1:-$TARGET}'\"" | tr -d '\r' | sed -E 's/^Row: [0-9]+ //' | tr ',' '\n' | sed 's/^ //'; }
# expect_row <label> <key> <value>
expect_row() {
  local got; got=$(row | grep "^$2=" | cut -d= -f2)
  [ "$got" = "$3" ] && note_ok "$1" || { note_fail "$1 ($2=$got, wanted $3)"; return 1; }
}

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

prop() { adb_ shell getprop "$1" | tr -d '\r'; }

# Opens InkPalm+ on the target app's page through the E-ink screen's app list (home screen > E-ink).
open_target() {
  # A fresh task, never `am start -S`: force-stopping InkPalm+ kills its accessibility service, and Android
  # then switches the service off as crashed.
  adb_ shell am start -n $PKG/.SectionActivity --es section eink -f 0x10008000 >/dev/null   # NEW_TASK | CLEAR_TASK
  sleep 3
  scroll_to text "Search apps"; tap text "Search apps" || return 1
  type_text "${TARGET_LABEL// /%s}"
  adb_ shell input keyevent 4   # Back hides the keyboard; uiautomator leaves out rows hidden behind it
  # The app list loads in the background; wait for the row (not the search box) to show up.
  local i; for i in 1 2 3 4 5 6 7 8; do dump && pyfind find text "$TARGET_LABEL" >/dev/null 2>&1 && break; sleep 1; done
  tap text "$TARGET_LABEL" || return 1
  sleep 1.5
}

# The DPI the target was last drawn at (from the firmware's own log line).
launch_target_dpi() {
  adb_ logcat -c
  adb_ shell am force-stop "$TARGET"
  adb_ shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  adb_ logcat -d -s app_dpi:D | grep -o "performCreate.dpi=[-0-9]*,PackageName=$TARGET" | tail -1 | sed -E 's/performCreate.dpi=([-0-9]+).*/\1/'
}

note_ok() { CHECK_NOTES="$CHECK_NOTES
  ok   $1"; }
note_fail() { CHECK_FAILED=1; CHECK_NOTES="$CHECK_NOTES
  FAIL $1"; }
manual() { CHECK_NOTES="$CHECK_NOTES
  manual (needs a person) $1"; }

# ---- snapshot / restore ---------------------------------------------------------------------

# Display properties the quick panel sets live; put back through Moaan's eink service (it sets any property).
PROPS="debug.sf.gamma.gamma sys.eink.mode persist.sys.mogu.refresh_type persist.vendor.fullmode_cnt"
# KompaktOS: what the screen panel sets (put back with setprop; needs adb root).
KOMPAKT_PROPS="sys.eink.mode sys.gray.gammalevel persist.sys.mogu.refresh_type persist.vendor.fullmode_cnt"
GLOBALS="global:window_animation_scale global:transition_animation_scale global:animator_duration_scale system:font_scale"

# The recent apps list from InkPalm+'s own file, newest first, one package per line.
recent_list() { adb_ shell run-as $PKG cat shared_prefs/recent_apps.xml 2>/dev/null | tr -d '\r' | grep -o 'name="apps">[^<]*' | sed 's/^name="apps">//; s/&#10;/\n/g' | awk 'NF { print $1 }'; }

snapshot() {
  if [ -n "${KOMPAKT:-}" ]; then
    recent_list | tail -r > "$OUT/recents-before.txt"
    : > "$OUT/props-before.txt"
    for k in $KOMPAKT_PROPS; do echo "$k=$(adb_ shell getprop $k | tr -d '\r')" >> "$OUT/props-before.txt"; done
    return
  fi
  row > "$OUT/target-row-before.txt"
  [ -s "$OUT/target-row-before.txt" ] || abort "no row for $TARGET in Moaan's table (open it once first)"
  : > "$OUT/globals-before.txt"
  for g in $GLOBALS; do echo "$g=$(adb_ shell settings get ${g%%:*} ${g#*:} | tr -d '\r')" >> "$OUT/globals-before.txt"; done
  # The side panel's recent list, oldest first (put back with the recents_clear / remember hooks).
  recent_list | tail -r > "$OUT/recents-before.txt"
  : > "$OUT/props-before.txt"
  for k in $PROPS; do echo "$k=$(adb_ shell getprop $k | tr -d '\r')" >> "$OUT/props-before.txt"; done
}

restore_all() {
  [ -n "${CLEANED:-}" ] && return 0
  CLEANED=1
  if [ -n "${KOMPAKT:-}" ]; then restore_kompakt; return; fi
  [ -s "$OUT/target-row-before.txt" ] || return 0
  log "restoring $TARGET's row and global settings"
  local binds="" k v dpi
  while IFS='=' read -r k v; do
    case "$k" in id|package_name|"") ;;
      app_contrast) binds="$binds --bind $k:f:$v" ;;
      *) binds="$binds --bind $k:i:$v" ;;
    esac
  done < "$OUT/target-row-before.txt"
  adb_ shell content update --uri $URI $binds --where "\"package_name='$TARGET'\""
  dpi=$(grep '^app_dpi=' "$OUT/target-row-before.txt" | cut -d= -f2)
  [ "$(grep '^is_dpi_setting=' "$OUT/target-row-before.txt" | cut -d= -f2)" = 1 ] || dpi=-1
  adb_ shell service call package 201 s16 "$TARGET" i32 "$dpi" >/dev/null
  while IFS='=' read -r g v; do
    [ "$v" = null ] && continue
    adb_ shell settings put "${g%%:*}" "${g#*:}" "$v"
  done < "$OUT/globals-before.txt"
  # Only properties that actually changed are written back.
  while IFS='=' read -r k v; do
    [ -n "$v" ] && [ "$(adb_ shell getprop "$k" | tr -d '\r')" != "$v" ] && adb_ shell service call eink 5 s16 "$k" s16 "$v" >/dev/null
  done < "$OUT/props-before.txt"
  row > "$OUT/target-row-after.txt"
  diff -q "$OUT/target-row-before.txt" "$OUT/target-row-after.txt" >/dev/null || log "WARNING: $TARGET's row differs from the snapshot; see $OUT"
  adb_ shell am force-stop "$TARGET"
  # Recent list: only rewritten if the checks changed it.
  recent_list > "$OUT/recents-after-run.txt"
  if [ "$(tail -r "$OUT/recents-before.txt")" != "$(cat "$OUT/recents-after-run.txt")" ] && hook state; then
    hook side_close >/dev/null; hook recents_clear >/dev/null
    while read -r p <&3; do hook remember "$p" >/dev/null; done 3< "$OUT/recents-before.txt"
    recent_list > "$OUT/recents-after.txt"
    tail -r "$OUT/recents-before.txt" | diff -q - "$OUT/recents-after.txt" >/dev/null || log "WARNING: recent apps list differs from the snapshot; see $OUT"
  fi
}

restore_kompakt() {
  [ -s "$OUT/props-before.txt" ] || return 0
  log "restoring e-ink properties and the recent list"
  local k v
  while IFS='=' read -r k v; do
    [ -n "$v" ] && [ "$(adb_ shell getprop "$k" | tr -d '\r')" != "$v" ] && adb_ shell setprop "$k" "$v"
  done < "$OUT/props-before.txt"
  recent_list > "$OUT/recents-after-run.txt"
  if [ "$(tail -r "$OUT/recents-before.txt")" != "$(cat "$OUT/recents-after-run.txt")" ] && hook state; then
    hook side_close >/dev/null; hook recents_clear >/dev/null
    while read -r p <&3; do hook remember "$p" >/dev/null; done 3< "$OUT/recents-before.txt"
  fi
}

write_summary() {
  local pass fail
  pass=$(grep -c "^PASS" "$RESULTS" 2>/dev/null); fail=$(grep -c "^FAIL" "$RESULTS" 2>/dev/null)
  {
    echo "# Device test: $(date '+%Y-%m-%d %H:%M')"
    echo "Build: ${BUILD_VERSION:-unknown} on ${DEVICE:-unknown}, target $TARGET. Passed: ${pass:-0}, failed: ${fail:-0}.${ABORTED:+ **ABORTED: $ABORTED**}"
    echo
    echo "| Check | Result |"
    echo "|---|---|"
    while IFS=$'\t' read -r r name; do echo "| $name | $r |"; done < "$RESULTS"
    if [ -s "$OUT/details.txt" ]; then echo; echo '## Details'; cat "$OUT/details.txt"; fi
    echo
    echo "Dumps, failure screenshots and logs: $OUT"
  } > "$SUMMARY"
}
finish() { local code=$?; restore_all; write_summary; cat "$SUMMARY"; exit $code; }
trap finish EXIT
trap 'ABORTED="interrupted"; exit 130' INT TERM HUP

# ---- run ------------------------------------------------------------------------------------

adb_ get-state >/dev/null 2>&1 || { ABORTED="no device"; exit 2; }
DEVICE="$(adb_ shell getprop ro.product.model | tr -d '\r') (Android $(adb_ shell getprop ro.build.version.release | tr -d '\r'))"
# KompaktOS: the root bridge is in the image. Only the side panel and the screen panel apply there; Moaan's
# table (per-app, quick-panel) is stock-only, and the screen panel is KompaktOS-only.
adb_ shell test -f /system/phh/inkpalm/eink-bridge.sh && KOMPAKT=1 && DEVICE="$DEVICE, KompaktOS"
if [ "${FEATURES[0]}" = all ]; then
  if [ -n "${KOMPAKT:-}" ]; then FEATURES=(side-panel screen-panel)
  else FEATURES=($(ls tools/device-checks | sed 's/\.sh$//' | grep -vx screen-panel)); fi
fi
[ -z "$APK" ] && [ -f app/build/outputs/apk/debug/app-debug.apk ] && APK=app/build/outputs/apk/debug/app-debug.apk
if [ -n "$APK" ]; then
  log "installing $APK"
  adb_ install -r -d "$APK" >/dev/null || { ABORTED="install failed"; exit 2; }
fi
BUILD_VERSION=$(adb_ shell dumpsys package $PKG | tr -d '\r' | grep -m1 versionName | sed 's/.*=//')
healthy || abort "SystemUI isn't answering (Moaan's provider timed out)"
snapshot
log "build $BUILD_VERSION on $DEVICE; snapshot saved"

for feature in "${FEATURES[@]}"; do
  # shellcheck disable=SC1090
  source "tools/device-checks/$feature.sh"
  for fn in $(grep -E '^check_[a-z0-9_]+\(\)' "tools/device-checks/$feature.sh" | sed -E 's/\(\).*//'); do
    CHECK="$feature.${fn#check_}"; CHECK_FAILED=0; CHECK_NOTES=""
    log "== $CHECK"
    healthy || abort "SystemUI isn't answering (Moaan's provider timed out) before $CHECK"
    adb_ logcat -c
    "$fn"
    if [ "$CHECK_FAILED" = 1 ]; then
      printf 'FAIL\t%s\n' "$CHECK" >> "$RESULTS"
      adb_ logcat -d -v time -s InkPalmPlus:I AndroidRuntime:E app_dpi:D 2>/dev/null | tail -40 > "$OUT/$CHECK-log.txt"
      { echo "### $CHECK (failed)"; echo '```'; echo "$CHECK_NOTES" | sed '/^$/d'; echo '```'; echo "Log tail:"; echo '```'; tail -10 "$OUT/$CHECK-log.txt"; echo '```'; } >> "$OUT/details.txt"
    else
      printf 'PASS\t%s\n' "$CHECK" >> "$RESULTS"
      echo "$CHECK_NOTES" | grep manual | sed "s/^/  $CHECK:/" >> "$OUT/manual.txt"
    fi
  done
done
[ -s "$OUT/manual.txt" ] && { { echo "### Still manual"; cat "$OUT/manual.txt"; } >> "$OUT/details.txt"; }
[ "$(grep -c '^FAIL' "$RESULTS")" = 0 ] || exit 1
exit 0

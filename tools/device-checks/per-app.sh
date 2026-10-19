# Per-app settings: each check edits the target app's page in InkPalm+, saves, and checks
# Moaan's table (and, for DPI, what the firmware actually draws the app at).
# The target's row is snapshotted before the run and restored after it.

scroll_down() { guard; adb_ shell input swipe 360 1150 360 450 300; sleep 1; }

check_dpi_saved_and_applied() {
  open_target || return
  tap text "240" || return
  tap text "Save" || return
  sleep 1
  expect_row "row has 240 dpi" app_dpi 240
  expect_row "row marks DPI as set" is_dpi_setting 1
  local d; d=$(launch_target_dpi)
  [ "$d" = 240 ] && note_ok "target opens at 240 dpi" || note_fail "target opened at '${d:-?}' dpi, wanted 240"
}

check_refresh_mode_saved() {
  open_target || return
  tap text "Fast" || return
  scroll_down
  tap text "Every 10" || return
  tap text "Save" || return
  sleep 1
  expect_row "refresh mode Fast (DU, 14)" refresh_mode 14
  expect_row "full refresh every 10" refresh_frequency 10
  expect_row "row marks refresh as set" is_refresh_setting 1
}

check_contrast_saved() {
  open_target || return
  scroll_down
  scroll_down
  tap text "Darker" || return
  tap text "Save" || return
  sleep 1
  expect_row "contrast one step darker than default (gamma 0.7)" app_contrast 0.7
  expect_row "row marks contrast as set" is_contrast_setting 1
  manual "whether the target looks darker on the panel"
}

check_reset_clears_everything() {
  open_target || return
  tap text "Reset" || return
  tap text "Save" || return
  sleep 1
  expect_row "DPI off" is_dpi_setting 0
  expect_row "refresh off" is_refresh_setting 0
  expect_row "contrast off" is_contrast_setting 0
  local d; d=$(launch_target_dpi)
  [ "$d" = 276 ] && note_ok "target opens at the screen's 276 dpi" || note_fail "target opened at '${d:-?}' dpi, wanted 276"
}

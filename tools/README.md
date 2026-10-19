# Device test tools

Scripted checks for the InkPalm Plus, run over adb from a Mac. Ported from SuperBoox's `tools/tablet-test.sh`.

```
tools/device-test.sh list
tools/device-test.sh run all
tools/device-test.sh --apk some.apk --target org.koreader.launcher.fdroid --label KOReader run per-app
```

Output: `tools/device-results/<time>/summary.md` (pass/fail table, notes and log tail for each failure), printed at the end.
Screenshots are taken only for failures; every tap leaves a `uiautomator` XML dump.

## What it will and won't do
- Taps only InkPalm+'s own screens, by finding a node by its text (never raw coordinates), and aborts if another
  app is in front.
- Snapshots the target app's row in Moaan's table and the global animation/text-size settings first, and restores
  them at the end (also on Ctrl-C), including the target's DPI in PackageManager. `target-row-before.txt` and
  `target-row-after.txt` in the results folder show that the restore worked.
- **Never force-stops InkPalm+** (`am force-stop`, `am start -S`): that kills the quick panel's accessibility service,
  and Android then switches it off as crashed until someone turns it back on in Accessibility.
- `uiautomator dump` unbinds the quick panel service for a few seconds; the quick panel checks wait for it to come
  back, and drive the panel through debug test hooks instead of dumps.

## Adding checks
Create `tools/device-checks/<feature>.sh` with functions named `check_<what>`. Helpers:

| Helper | Does |
|---|---|
| `open_target` | Opens InkPalm+ on the target app's page through the app list |
| `tap <kind> <value>` | Dump, then tap the one matching node (kinds: `text`, `desc`, `textprefix`, `label`, `labelprefix`) |
| `expect <label> <kind> <value>` | Passes if the node is on screen |
| `expect_row <label> <column> <value>` | Passes if the target's row in Moaan's table has that value |
| `launch_target_dpi` | Relaunches the target and prints the DPI the firmware drew it at |
| `row [pkg]` | The row as `key=value` lines |
| `scroll_to <kind> <value>` | Swipes up until the node is on screen |
| `type_text`, `shot <name>`, `manual "<what only a person can judge>"`, `note_ok`, `note_fail` | |

Screens scroll: define a local `scroll_down` (see `per-app.sh`) and remember the keyboard hides rows from the dump.

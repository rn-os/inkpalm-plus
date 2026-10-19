#!/usr/bin/env bash
# Builds the mockup boards and renders each to PNG with macOS Quick Look (no Playwright or Chrome needed).
# Quick Look draws about 1200x1200 CSS px into a square image, so boards are laid out to fit that.
#   docs/mockups/render.sh
set -eu
cd "$(dirname "$0")"
python3 -I build.py >/dev/null
for f in [0-9][0-9]-*.html; do
  qlmanage -t -s 2400 -o . "$f" >/dev/null 2>&1
  mv -f "$f.png" "${f%.html}.png"
  echo "${f%.html}.png"
done

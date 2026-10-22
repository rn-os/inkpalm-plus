#!/usr/bin/env bash
# Renders the launch images (build.py) to PNG with macOS Quick Look (2400 px wide; it draws ~2.34 px per CSS px),
# cropped with ffmpeg to each canvas (820 or 560 CSS px tall).
#   docs/launch/render.sh
set -eu
cd "$(dirname "$0")"
python3 -I build.py >/dev/null
for f in [0-9]-*.html; do
  qlmanage -t -s 2400 -o . "$f" >/dev/null 2>&1
  h=1930; grep -q "class=\"stage short\"" "$f" && h=1320
  ffmpeg -loglevel error -y -i "$f.png" -vf "crop=2400:$h:0:0" "${f%.html}.png"
  rm -f "$f.png"
  echo "${f%.html}.png"
done

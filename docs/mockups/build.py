#!/usr/bin/env python3
"""Builds the quick panel mockup boards (HTML) in this folder. Render them with render.sh.

Boards: 01-today (screenshots of the current panel), 02-arrows (option A), 03-tabs (option B).
Drawn at half the InkPalm's pixels: portrait 360×720, landscape 720×360 CSS px.
"""
from pathlib import Path

HERE = Path(__file__).parent

BOOK = """<h4>Chapter Three</h4>
<p>The ferry left at six, and by the time the fog lifted the harbour was only a grey line behind them.
Mara kept her notebook on her knee and wrote down everything: the colour of the water, the gulls that
followed the wake, the way the engine changed its note whenever the boat turned into the wind.</p>
<p>Her uncle had told her the island was smaller than it looked on the map. He had not said that the
map was forty years old, or that half the roads on it no longer went anywhere at all.</p>
<p>When the pier came into view she counted the houses along the shore. There were eleven, and only
three had smoke rising from their chimneys. On the hill above them stood the lighthouse, white and
patient, its lamp dark in the morning light.</p>
<p>"You'll want to walk," said the ferryman, tying off the rope. "Nothing on wheels gets far here."</p>
<p>She thanked him and stepped onto the boards, which moved a little under her feet, as if the pier
itself were still deciding whether to let her stay.</p>"""

STATUS = '<div class="status"><span>12:20 PM</span><span>Wi-Fi · 99%</span></div>'


def page(title, body):
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>{title}</title>
<link rel="stylesheet" href="panel.css"></head><body><div class="board">{body}</div></body></html>"""


def device(kind, sheet, extra=""):
    return f'<div class="dev {kind}">{STATUS}<div class="book">{BOOK}</div>{sheet}{extra}</div>'


def frame(cap, sub, dev):
    return f'<div class="col"><div class="cap">{cap}</div>{dev}<div class="sub">{sub}</div></div>'


def header(name="KOReader"):
    return f"""<div class="hd"><div class="t"><b>{name}</b><span>Changes apply right away</span></div>
<div class="btn">More</div><div class="btn sq">✕</div></div>"""


# ---- Option A: one line per setting -------------------------------------------------------------

def arrow_row(label, value, note="", sys=None):
    sysbtn = f'<div class="btn sys{" on" if sys else ""}">Sys</div>' if sys is not None else '<div class="sys"></div>'
    small = f"<small>{note}</small>" if note else ""
    return f"""<div class="rowA"><div class="lab">{label}</div><div class="btn sq">{"−" if label == "Size" else "◀"}</div>
<div class="val">{value}{small}</div><div class="btn sq">{"+" if label == "Size" else "▶"}</div>{sysbtn}</div>"""


ARROW_ROWS = (
    arrow_row("Size", "300 dpi", sys=False)
    + arrow_row("Contrast", "Darker +2", sys=False)
    + arrow_row("Refresh", "Fast", "black &amp; white, quick turns")
    + arrow_row("Full refresh", "Every 10")
    + arrow_row("Rotate", "Portrait", "kept for this app")
)
FOOT = '<div class="foot"><div class="btn">Full refresh now</div><div class="btn">Reset app</div></div>'


def arrows_bottom():
    return f'<div class="sheet bottom">{header()}{ARROW_ROWS}{FOOT}</div><div class="pct" style="top:22px">app visible ≈ 61%</div>'


def arrows_list():
    opts = [("System", "Follows Quick Settings", False), ("Normal", "Full grays, least ghosting", False),
            ("Auto", "The panel picks per update", False), ("Fast", "Black &amp; white, quick turns", True),
            ("Fast + 4 grays", "Quick, keeps some gray", False), ("Speed", "Scrolling and video, ghosts more", False),
            ("Speed + dither", "Speed with dithered grays", False)]
    rows = "".join(f'<div class="opt"><div class="dot{" on" if on else ""}"></div><div><b>{t}</b><i>{d}</i></div></div>' for t, d, on in opts)
    hd = '<div class="hd"><div class="btn sq">◀</div><div class="t"><b>Refresh</b><span>KOReader · tap one to pick it</span></div></div>'
    return f'<div class="sheet bottom list">{hd}{rows}<div style="height:6px"></div></div>'


def arrows_side():
    return f'<div class="sheet side" style="width:40%">{header()}{ARROW_ROWS}<div style="flex:1"></div>{FOOT}</div><div class="pct" style="top:22px">app visible ≈ 60%</div>'


# ---- Option B: tabs ----------------------------------------------------------------------------

def chips(items, on):
    return '<div class="wrap">' + "".join(f'<div class="btn{" on" if i == on else ""}">{i}</div>' for i in items) + "</div>"


def tab_display():
    return (
        '<div class="tabs"><div class="on">Display</div><div>Refresh</div><div>Rotate</div></div>'
        + arrow_row("Size", "300 dpi", sys=False)
        + arrow_row("Contrast", "Darker +2", sys=False)
    )


def tab_refresh():
    return (
        '<div class="tabs"><div>Display</div><div class="on">Refresh</div><div>Rotate</div></div>'
        + '<div class="lbl">Mode</div>' + chips(["System", "Normal", "Auto", "Fast", "Fast + 4 grays", "Speed", "Speed + dither"], "Fast")
        + '<div class="lbl">Full refresh</div>' + chips(["System", "Every 5", "Every 10", "Every 20", "Every 30", "Every 50"], "Every 10")
    )


def tab_rotate():
    return (
        '<div class="tabs"><div>Display</div><div>Refresh</div><div class="on">Rotate</div></div>'
        + '<div class="lbl">Kept for this app</div>' + chips(["Auto", "Portrait", "Landscape", "Portrait ↻", "Landscape ↻"], "Portrait")
    )


def tabs_bottom(content, pct):
    return f'<div class="sheet bottom">{header()}{content}{FOOT}</div><div class="pct" style="top:22px">app visible ≈ {pct}</div>'


def tabs_side(content):
    return f'<div class="sheet side" style="width:40%">{header()}{content}<div style="flex:1"></div>{FOOT}</div><div class="pct" style="top:22px">app visible ≈ 60%</div>'


# ---- Boards ------------------------------------------------------------------------------------
# Two rows each (portraits, then landscape + notes): Quick Look renders about 1200×1200 CSS px.

def board(title, notes, portraits, landscape):
    return page(title, f'<div class="row">{"".join(portraits)}</div><div class="row">{landscape}{notes}</div>')


today = board("Quick panel today", """<div class="notes"><h2>Today</h2>
<p>Screenshots of the panel as built.</p>
<h3>Problems</h3><ul>
<li>Rotate, Refresh and Full refresh rows run off the right edge. They scroll sideways, but nothing on e-ink says so.</li>
<li>Hidden: <b>Landscape ↻</b>, <b>Speed</b>, <b>Speed + dither</b>, <b>Every 30</b>, <b>Every 50</b>; <b>Fast + 4 grays</b> is cut.</li>
<li>Portrait sheet covers ≈ 56% of the screen.</li>
<li>Landscape: Full refresh and the bottom buttons are below the edge.</li></ul></div>""",
    [frame("Portrait", "Bottom sheet", '<div class="dev p"><img class="shot" src="today-portrait.png"></div>')],
    frame("Landscape", "Half-width side sheet", '<div class="dev l"><img class="shot" src="today-landscape.png"></div>'))

arrows = board("Option A: one line per setting", """<div class="notes"><h2>A · One line per setting</h2>
<p>Every setting is a single line: <b>◀ value ▶</b>. Tap the arrows to step, or tap the value for the full list.</p>
<h3>Good</h3><ul>
<li>Every setting and its current value visible at once; nothing scrolls.</li>
<li>Shortest panel that still shows everything: ≈ 39% of the height in portrait, 40% of the width in landscape.</li>
<li>Same layout in both orientations.</li></ul>
<h3>Less good</h3><ul>
<li>Reaching a far option takes several ▶ taps, or one tap on the value and one in the list.</li>
<li>You don't see every option until you open the list.</li></ul></div>""",
    [frame("Portrait", "Bottom sheet. The value under each name is the setting now.", device("p", arrows_bottom())),
     frame("Portrait · tapped “Fast”", "The list replaces the rows; picking one applies it and goes back.", device("p", arrows_list()))],
    frame("Landscape", "Side sheet, 40% wide, on the strip's side. The page column stays readable.", device("l", arrows_side())))

tabs = board("Option B: tabs", """<div class="notes"><h2>B · Tabs</h2>
<p>Three tabs: <b>Display</b> (size, contrast), <b>Refresh</b> (mode, full refresh), <b>Rotate</b>.
Each tab shows all its options as buttons, wrapping onto a second line.</p>
<h3>Good</h3><ul>
<li>Every option visible in its tab: one tap to pick.</li>
<li>Panel is short on some tabs: ≈ 27% (Display) to 42% (Refresh) of the height.</li></ul>
<h3>Less good</h3><ul>
<li>Only one group at a time: comparing contrast with refresh means switching tabs.</li>
<li>Panel height changes from tab to tab (a little redraw on e-ink).</li>
<li>The tab row takes a line of its own.</li></ul></div>""",
    [frame("Portrait · Display", "", device("p", tabs_bottom(tab_display(), "73%"))),
     frame("Portrait · Refresh", "", device("p", tabs_bottom(tab_refresh(), "58%")))],
    frame("Landscape · Rotate", "", device("l", tabs_side(tab_rotate()))))

for name, html in [("01-today", today), ("02-arrows", arrows), ("03-tabs", tabs)]:
    (HERE / f"{name}.html").write_text(html)
    print(HERE / f"{name}.html")

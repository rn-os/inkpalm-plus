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
    # ⚙ opens InkPalm+'s home screen; More opens this app's own page.
    return f"""<div class="hd"><div class="t"><b>{name}</b><span>Changes apply right away</span></div>
<div class="btn sq gear">⚙</div><div class="btn">More</div><div class="btn sq">✕</div></div>"""


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

# ---- Feature boards (04–07): ideas for using more of the panel ---------------------------------

def seg(on):
    return '<div class="seg">' + "".join(f'<div class="{"on" if t == on else ""}">{t}</div>' for t in ("Display", "Refresh")) + "</div>"


def header2(on, name="KOReader"):
    return header(name) + f'<div class="segrow">{seg(on)}</div>'


T = '<span class="test">TEST</span>'

PAGE_DISPLAY = (
    arrow_row("Size", "300 dpi", sys=False)
    + arrow_row("Contrast", "Darker +2", sys=False)
    + arrow_row("Rotate", "Portrait", "kept for this app")
    + arrow_row("Invert", "Off", "for dark-themed apps")
    + arrow_row("Light", "Warm 4 · 7", "per app · needs Shizuku")
)
PAGE_REFRESH = (
    arrow_row("Refresh", "Normal", "full grays, least ghosting")
    + arrow_row("Scrolling", "Speed (A2)", "back to Normal 0.5 s after")
    + arrow_row("Full refresh", "Every 10")
    + arrow_row("On open", "Full refresh", "clears the last app's ghost")
    + arrow_row("Grays", f"16{T}", "256 for photos and comics")
)


def panel2(on, rows, side=False):
    if side:
        return f'<div class="sheet side" style="width:42%">{header2(on)}{rows}<div style="flex:1"></div>{FOOT}</div>'
    return f'<div class="sheet bottom">{header2(on)}{rows}{FOOT}</div>'


panel_pages = board("Panel with more settings", """<div class="notes"><h2>04 · Panel: two pages</h2>
<p>Ten settings don't fit on one screen, so the panel gets two pages, switched under its header:
<b>Display</b> and <b>Refresh</b>. Each page keeps the one-line layout, so nothing scrolls.</p>
<p><b>⚙</b> in the header opens InkPalm+'s home screen (strip, whole-device settings, all apps);
<b>More</b> opens this app's own page.</p>
<h3>New lines</h3><ul>
<li><b>Invert</b> (05/06), <b>Light</b> (07) on Display.</li>
<li><b>Scrolling</b>, <b>On open</b> (05), <b>Grays</b> (07) on Refresh.</li></ul>
<h3>Choice to make</h3><ul>
<li>The panel opens on the page you used last, or always on Display.</li>
<li>Or instead of pages: pick which 5 lines the panel shows (like SuperBoox's Quick Controls).</li></ul></div>""",
    [frame("Portrait · Display", "", device("p", panel2("Display", PAGE_DISPLAY))),
     frame("Portrait · Refresh", "", device("p", panel2("Refresh", PAGE_REFRESH)))],
    frame("Landscape · Refresh", "Same pages in the side sheet.", device("l", panel2("Refresh", PAGE_REFRESH, side=True))))


def mini(cls, extra="", badge=""):
    b = f'<div class="badge">{badge}</div>' if badge else ""
    return f'<div class="dev m {cls}">{STATUS}<div class="book">{BOOK}</div>{extra}{b}</div>'


GHOST = f'<div class="ghost"><div class="book">{BOOK}</div></div>'

scroll_board = page("Fast while scrolling, clean on open", f"""
<div class="row">
{frame("1 · Reading", "Normal waveform: full grays, crisp.", mini("", badge="NORMAL"))}
<div class="arrow">→</div>
{frame("2 · Scrolling", "Switches to Speed (A2) the moment a scroll starts: quick, blocky, ghosts.", mini("dither", GHOST, "SPEED (A2)"))}
<div class="arrow">→</div>
{frame("3 · Stopped", "0.5 s after the last scroll: one full refresh, back to Normal.", mini("", '<div class="flash" style="opacity:.25"></div>', "FULL REFRESH"))}
<div class="arrow">→</div>
{frame("4 · Clean again", "Same as 1. No ghost left behind.", mini("", badge="NORMAL"))}
</div>
<div class="row">
<div class="card"><div class="ttl">KOREADER · REFRESH (per-app page, “More”)</div>
<div class="srow"><div class="n"><b>Refresh</b><span>Waveform while the page is still</span></div><div class="btn on">Normal</div></div>
<div class="srow"><div class="n"><b>While scrolling</b><span>Faster waveform only while a list or page moves</span></div><div class="btn">Off</div><div class="btn on">Speed (A2)</div><div class="btn">Fast (DU)</div></div>
<div class="srow"><div class="n"><b>Back to normal after</b><span>Time without scrolling, then one full refresh</span></div><div class="btn">0.3 s</div><div class="btn on">0.5 s</div><div class="btn">1 s</div></div>
<div class="srow"><div class="n"><b>Clean screen on open</b><span>One full refresh when this app comes to the front</span></div><div class="btn">Off</div><div class="btn on">On</div></div>
<div class="srow"><div class="n"><b>Default for new apps</b><span>Home → Whole device</span></div><div class="btn on">Clean on open</div></div>
</div>
<div class="notes"><h2>05 · Fast while scrolling, clean on open</h2>
<ul><li>Uses scroll events the panel service already receives (no screen content read), and the live waveform switch the panel uses today.</li>
<li>Scrolling apps (browser, feeds, Anki) get Speed only while moving; text is crisp at rest.</li>
<li>Clean on open: one flash when an app comes to the front clears the previous app's ghost.</li>
<li>Both are per app, with a default for new apps.</li>
<li>Risk: apps that keep scrolling slightly (animations, tickers) would stay in Speed; the 0.5 s timer and per-app Off handle that.</li></ul></div>
</div>""")


def gapp(cls, name="Store"):
    items = "".join(f'<div class="it"><div class="ic"></div><div class="tx"><b>{t}</b><span>{d}</span></div></div>'
                    for t, d in [("Field Notes", "Notebook · 4.6 ★"), ("Tide Tables", "Weather · Free"),
                                 ("Pocket Atlas", "Maps · 12 MB"), ("Night Sky", "Education · 4.8 ★"),
                                 ("Recipe Box", "Food · Free"), ("Word Garden", "Puzzle · 4.4 ★")])
    return f'<div class="gapp {cls}"><div class="bar">{name}</div><div class="bar" style="font-weight:400">Search apps…</div>{items}</div>'


def appdev(inner, badge=""):
    b = f'<div class="badge">{badge}</div>' if badge else ""
    return f'<div class="dev m">{STATUS}{inner}{b}</div>'


bleach_board = page("Bleach and invert", f"""
<div class="row">
{frame("Gray-heavy app", "Gray cards and icons turn to mush at 16 grays.", appdev(gapp("g0"), "AS IS"))}
<div class="arrow">→</div>
{frame("With bleach", "Backgrounds whitened, icons and borders pushed to black.", appdev(gapp("g1"), "BLEACH"))}
<div style="width:20px"></div>
{frame("Dark-themed app", "Light text on near-black: slow, ghosty on e-ink.", '<div class="dev m">' + STATUS + '<div class="dark"><div class="book">' + BOOK + '</div></div><div class="badge">AS IS</div></div>')}
<div class="arrow">→</div>
{frame("Inverted", "Black on white, only while this app is open.", mini("", badge="INVERT"))}
</div>
<div class="row">
<div class="card"><div class="ttl">STORE · MOAAN EXTRAS (per-app page, “More”)</div>
<div class="srow"><div class="n"><b>Bleach</b><span>Moaan's whitening, on or off</span></div><div class="btn">Off</div><div class="btn on">On</div></div>
<div class="srow"><div class="n"><b>Background</b><span>How far light grays are pushed to white</span></div><div class="slider"><i style="left:70%"></i></div><div class="sv">7</div></div>
<div class="srow"><div class="n"><b>Icons</b><span>How far icons are pushed to black</span></div><div class="slider"><i style="left:50%"></i></div><div class="sv">5</div></div>
<div class="srow"><div class="n"><b>Covers and pictures</b><span>Contrast of images</span></div><div class="slider"><i style="left:30%"></i></div><div class="sv">3</div></div>
<div class="srow"><div class="n"><b>Contrast boost</b><span>Moaan's contrast gain, on top of Contrast</span></div><div class="slider"><i style="left:40%"></i></div><div class="sv">4</div></div>
<div class="srow"><div class="n"><b>Bold text</b><span>Android's high-contrast text</span></div><div class="btn on">On</div></div>
<div class="srow"><div class="n"><b>Invert colors</b><span>For dark-themed apps</span></div><div class="btn on">Off</div><div class="btn">On</div></div>
</div>
<div class="notes"><h2>06 · Bleach and invert</h2>
<ul><li>Moaan's table already stores background, icon and cover levels and a contrast boost per app; today we only show Bleach on/off.</li>
<li>The slider ranges aren't known yet: a short test on the device first maps them (the mockup's 0–10 is a placeholder).</li>
<li>Invert uses Android's color inversion, switched on only while that app is in front (InkPalm+ already has the permission).</li>
<li>Bleach and Invert also go in the panel's Display page (04) as one-tap lines.</li></ul></div>
</div>""")


def reflist():
    opts = [("System", "Follows Quick Settings", False, ""), ("Normal", "Full grays, least ghosting", True, ""),
            ("Text (Regal)", "Least ghosting for reading", False, T), ("Text sharp (GL16)", "Crisper text, light grays dropped", False, T),
            ("Auto", "The panel picks per update", False, ""), ("Fast", "Black &amp; white, quick turns", False, ""),
            ("Fast + 4 grays", "Quick, keeps some gray", False, ""), ("Speed", "Scrolling and video, ghosts more", False, ""),
            ("Speed + dither", "Speed with dithered grays", False, "")]
    rows = "".join(f'<div class="opt"><div class="dot{" on" if on else ""}"></div><div><b>{t}</b>{tt}<i>{d}</i></div></div>' for t, d, on, tt in opts)
    hd = '<div class="hd"><div class="btn sq">◀</div><div class="t"><b>Refresh</b><span>KOReader · tap one to pick it</span></div></div>'
    return f'<div class="sheet bottom list">{hd}{rows}<div style="height:6px"></div></div>'


more_board = page("More modes, grays, strip, light", f"""
<div class="row">
{frame("Refresh list with more modes", "Modes the controller has that Moaan's dialog doesn't offer. TEST: works only if the firmware honors it per app.", device("p", reflist()))}
{frame("Panel · Display with Light", "Front light per app: warmer and dimmer for night reading, applied when the app opens.", device("p", panel2("Display", PAGE_DISPLAY)))}
</div>
<div class="row">
<div class="card"><div class="ttl">HOME · QUICK PANEL AND WHOLE DEVICE</div>
<div class="srow"><div class="n"><b>Double-tap the strip</b><span>Without opening the panel</span></div><div class="btn">Nothing</div><div class="btn on">Full refresh</div></div>
<div class="srow"><div class="n"><b>Grays {T}</b><span>16 is Moaan's default; 256 may help photos and comics</span></div><div class="btn on">16</div><div class="btn">256</div><div class="btn">Per app</div></div>
<div class="srow"><div class="n"><b>Front light per app</b><span>Needs Shizuku running to change the light</span></div><div class="btn">Off</div><div class="btn on">On</div></div>
<div class="srow"><div class="n"><b>Clean screen on open</b><span>Default for apps without their own setting (05)</span></div><div class="btn on">On</div></div>
</div>
<div class="notes"><h2>07 · More modes, grays, strip, light</h2>
<ul><li><b>More modes</b>: Text (Regal) and Text sharp (GL16) added to the Refresh list, marked TEST until they're verified on the device.</li>
<li><b>Grays</b>: Moaan's hidden 256-gray switch, global or per app; marked TEST.</li>
<li><b>Double-tap the strip</b>: full refresh without opening the panel.</li>
<li><b>Light per app</b>: Moaan's warm/cold LEDs; changing them needs Shizuku, so it says so.</li></ul></div>
</div>""")

# ---- Board 08: presets instead of refresh knobs --------------------------------------------------

PRESET_ROWS = (
    arrow_row("Preset", "Reading", "Normal · full refresh every 10")
    + arrow_row("Size", "300 dpi", sys=False)
    + arrow_row("Contrast", "Darker +2", sys=False)
    + arrow_row("Rotate", "Portrait", "kept for this app")
)


def presets_list():
    opts = [("System", "Moaan's own settings, nothing changed", False),
            ("Reading", "Normal refresh, full refresh every 10 pages, clean on open", True),
            ("Browsing", "Normal at rest, Speed while scrolling, full refresh every 20", False),
            ("Comics &amp; photos", "Full grays, full refresh every 5 to keep images clean", False),
            ("Video", "Speed with dithered grays, no automatic cleaning", False),
            ("Custom", "Set on this app's page (More)", False)]
    rows = "".join(f'<div class="opt"><div class="dot{" on" if on else ""}"></div><div><b>{t}</b><i>{d}</i></div></div>' for t, d, on in opts)
    hd = '<div class="hd"><div class="btn sq">◀</div><div class="t"><b>Preset</b><span>KOReader · tap one to pick it</span></div></div>'
    return f'<div class="sheet bottom list">{hd}{rows}<div style="height:6px"></div></div>'


presets_board = board("Presets", """<div class="notes"><h2>08 · Presets</h2>
<p>One <b>Preset</b> line replaces the panel's Refresh and Full refresh lines, so the panel gets <i>shorter</i> (4 lines).</p>
<h3>What a preset sets</h3><ul>
<li>Refresh mode and full-refresh interval (Moaan's table).</li>
<li>Fast while scrolling / typing and clean on open for that app.</li>
<li>It does <b>not</b> touch Size, Contrast or Rotate: those stay their own lines.</li></ul>
<h3>Custom</h3><ul>
<li>Any app whose values don't match a preset shows <b>Custom</b>; the detailed controls live on its page (More), as today.</li></ul>
<h3>Choice to make</h3><ul>
<li>New apps start on <b>System</b> (nothing changes until you pick), or InkPalm+ suggests one by app type
(readers → Reading, browsers → Browsing).</li></ul></div>""",
    [frame("Portrait · panel", "Four lines; Preset first.", device("p", f'<div class="sheet bottom">{header()}{PRESET_ROWS}{FOOT}</div>')),
     frame("Portrait · tapped “Reading”", "One line each, with what it does.", device("p", presets_list()))],
    frame("Landscape", "Side sheet; fits with room to spare.", device("l", f'<div class="sheet side" style="width:42%">{header()}{PRESET_ROWS}<div style="flex:1"></div>{FOOT}</div>')))


# ---- Board 09: system font -----------------------------------------------------------------------

def fontrow(name, css, on=False):
    return f'<div class="fontrow"><div class="dot{" on" if on else ""}"></div><div class="nm">{name}</div><div class="pv" style="{css}">The quick brown fox jumps</div></div>'


font_board = page("System font", f"""
<div class="row">
{frame("Today: your Moaan font", "Real screenshot (Activity Launcher).", '<div class="shotcrop"><img src="font-moaan.png"></div>')}
{frame("After: Roboto Black, switched live", "Real screenshot, no restart. Every app and the status bar change.", '<div class="shotcrop"><img src="font-roboto-black.png"></div>')}
</div>
<div class="row">
<div class="card"><div class="ttl">HOME · WHOLE DEVICE → FONT</div>
{fontrow("Moaan font (now)", "font-family: Helvetica Neue; font-weight: 300;", True)}
{fontrow("Roboto", "font-family: Helvetica Neue; font-weight: 400;")}
{fontrow("Roboto Medium", "font-family: Helvetica Neue; font-weight: 500;")}
{fontrow("Roboto Bold", "font-family: Helvetica Neue; font-weight: 700;")}
{fontrow("Roboto Black", "font-family: Helvetica Neue; font-weight: 900;")}
{fontrow("Noto Serif", "font-family: Georgia;")}
{fontrow("Noto Serif Bold", "font-family: Georgia; font-weight: 700;")}
</div>
<div class="notes"><h2>09 · Font</h2>
<ul><li>One <b>Font</b> row under Whole device opens this list; each line is drawn in its font (here approximated with Mac fonts).</li>
<li>Uses Android's built-in fonts, so nothing to download or copy; your current Moaan font stays first so you can go back.</li>
<li>Applies live (verified): apps redraw in the new font in place.</li>
<li>Heavier weights (Medium, Bold) are the e-ink win: thin strokes break up at 16 grays.</li>
<li>No Shizuku needed.</li></ul></div>
</div>""")

# ---- Board 10: front touch button ---------------------------------------------------------------

def chiprow(name, sub, opts, on):
    btns = "".join(f'<div class="btn{" on" if o == on else ""}">{o}</div>' for o in opts)
    return f'<div class="srow" style="flex-wrap:wrap"><div class="n" style="flex-basis:100%"><b>{name}</b><span>{sub}</span></div><div style="display:flex;gap:6px;flex-wrap:wrap">{btns}</div></div>'


touch_board = page("Touch button", f"""
<div class="row">
<div class="card"><div class="ttl">HOME · TOUCH BUTTON (the button below the screen)</div>
<div class="srow"><div class="n"><b>Touch button</b><span>Off: touching it does nothing</span></div><div class="btn">Off</div><div class="btn on">On</div></div>
{chiprow("Tap", "A short touch", ["Back", "Home", "Page down", "Volume down"], "Home")}
{chiprow("Hold", "Touch for about a second", ["Full refresh", "Screenshot", "Page up", "Volume up", "Front light on/off", "Home"], "Full refresh")}
<div class="srow"><div class="n"><span>Only these actions are built into the device. For anything else (open an app, switch the panel), pick Page up or
Volume up here and map that key in Key Mapper.</span></div></div>
</div>
<div class="notes"><h2>10 · Touch button</h2>
<ul><li>Moaan's own options for the button, which only its (now disabled) launcher could set. Same choices, same meaning.</li>
<li>Shown with your current setup: tap = Home, hold = Full refresh.</li>
<li>Changes apply on the next touch; nothing restarts.</li>
<li>Lives under Whole device on the home screen, not in the quick panel.</li>
<li>Uses the permission InkPalm+ already has for turning its panel back on; without it, the section explains how to grant it.</li>
<li>Home goes to your current home app (inkOS), not Moaan's launcher.</li></ul></div>
</div>""")

# ---- Board 11: side panel, recent apps first ----------------------------------------------------

RECENT = [("KOReader", "K", "Open now", "now"), ("Feeder", "F", "2 min ago", "prev"), ("AnkiDroid", "A", "8 min ago", ""),
          ("Instapaper", "I", "25 min ago", ""), ("AntennaPod", "P", "1 h ago", ""), ("Libby", "L", "3 h ago", ""),
          ("EinkBro", "E", "Yesterday", ""), ("Etar", "C", "Yesterday", ""), ("Aurora Store", "S", "2 days ago", "")]


def ra_rows(items, compact=False):
    out = ""
    for name, letter, when, kind in items:
        tag = '<div class="rtag">OPEN NOW</div>' if kind == "now" else '<div class="rtag">PREVIOUS</div>' if kind == "prev" else ""
        if compact and kind == "prev":
            tag, when = "", "Previous · " + when
        elif compact and kind == "now":
            tag = ""
        out += f'<div class="ra-row {kind}"><div class="ai">{letter}</div><div class="tx"><b>{name}</b><span>{when}</span></div>{tag}</div>'
    return out


def side_panel(width, items, pager, extra=""):
    return (f'<div class="dim2" style="right:{width}"></div><div class="side2" style="width:{width}">'
            f'<div class="ra-hd"><b>Recent apps</b><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
            f'{ra_rows(items)}<div class="pager2">{pager}</div>'
            f'<div class="ra-foot"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>{extra}')


MENU = '<div class="menu" style="right:30px;top:250px"><div>Remove from list</div><div>Open its e-ink settings</div><div>App info</div></div>'

side_board = board("Side panel: recent apps", """<div class="notes"><h2>11 · Side panel: recent apps</h2>
<ul><li>Swipe the strip: the panel slides in from that edge, ~78% wide; the app stays visible beside it (tap there to close).</li>
<li><b>Recent apps</b>, newest first. The app you came from is marked <b>Previous</b>, right under the one open now: one tap switches back.</li>
<li>Pages (◀ 1/2 ▶) instead of scrolling, as in SuperBoox: no smeared scroll on e-ink.</li>
<li>Hold a row: Remove from list, Open its e-ink settings, App info.</li>
<li>Footer: <b>E-ink · this app</b> opens today's e-ink panel as its own overlay; <b>Android recents</b> for anything older.</li>
<li>The list is InkPalm+'s own (apps can't read Android's); home screens and InkPalm+ are left out. It survives restarts.</li></ul>
<h3>Choices to make</h3><ul>
<li>Panel width (78% shown) and how many apps to keep (shown: 9 per page, 18 kept).</li>
<li>Show “2 min ago”, or names only.</li></ul></div>""",
    [frame("Portrait", "Previous app one tap away; 9 per page.", device("p", side_panel("78%", RECENT, "◀ &nbsp; 1 / 2 &nbsp; ▶"))),
     frame("Portrait · held “AnkiDroid”", "A small menu, no scrolling.", device("p", side_panel("78%", RECENT, "◀ &nbsp; 1 / 2 &nbsp; ▶", MENU)))],
    frame("Landscape", "Half the width, two columns so a page still shows 8.",
          device("l", f'<div class="dim2" style="right:50%"></div><div class="side2" style="width:50%"><div class="ra-hd"><b>Recent apps</b><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
                      f'<div class="ra-grid">{ra_rows(RECENT[:8], compact=True)}</div><div class="pager2">◀ &nbsp; 1 / 2 &nbsp; ▶</div>'
                      f'<div class="ra-foot"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')))


# ---- Board 12: home screen reorganized -----------------------------------------------------------

def hrow(name, sub, right):
    return f'<div class="srow"><div class="n"><b>{name}</b><span>{sub}</span></div>{right}</div>'


home_board = page("Home screen", f"""
<div class="row">
<div class="card" style="width:430px"><div class="ttl">INKPALM+ · HOME</div>
{hrow("Side panel", "On · swipe up or down along the right edge", '<div class="btn on">On</div>')}
{hrow("Strip side", "Where you swipe", '<div class="btn">Left</div><div class="btn on">Right</div>')}
{hrow("Strip width", "", '<div class="btn sq">−</div><b style="width:44px;text-align:center">18 dp</b><div class="btn sq">+</div>')}
{hrow("Show strip", "A thin black line where you swipe", '<div class="btn">On</div><div class="btn on">Off</div>')}
{hrow("Panel width", "How much of the screen the panel covers", '<div class="btn">66%</div><div class="btn on">78%</div><div class="btn">90%</div>')}
{hrow("Apps kept", "Recent apps the panel remembers", '<div class="btn">9</div><div class="btn on">18</div><div class="btn">27</div>')}
{hrow("Clear recent apps", "Start the list again", '<div class="btn">Clear</div>')}
{hrow("Touch button", "Tap: Home · Hold: Full refresh", '<div class="btn">Open</div>')}
{hrow("E-ink settings", "Per-app DPI, refresh, contrast, presets, font, refresh helpers", '<div class="btn">Open</div>')}
{hrow("Keeping the panel on", "Survives Clear Memory · turns itself back on", '<div class="btn">Open</div>')}
</div>
<div class="notes"><h2>12 · Home screen</h2>
<ul><li>The side panel and recent apps come first; that's what you use.</li>
<li>Everything e-ink moves to one <b>E-ink settings ▸</b> screen: the per-app list, presets, font, refresh helpers, animations, text size. Nothing is removed, just out of the way.</li>
<li>Touch button and the keep-on switches get their own small screens too.</li>
<li>The e-ink panel you had stays available from the side panel's <b>E-ink · this app</b> button, as its own overlay (unchanged).</li></ul></div>
</div>""")

# ---- Board 13: NeoReader's last-read document in the side panel ------------------------------------

LASTREAD = ('<div class="lastread"><div class="bk"></div><div class="tx"><i>LAST READ · NEOREADER</i><b>The Lean Startup</b>'
            '<span>2 h ago · tap to keep reading · hold for your books</span></div></div>')

BOOKS = [("The Lean Startup", "Eric Ries · EPUB", "2 h ago"), ("Recursive Language Models", "PDF", "Yesterday"),
         ("The Lighthouse Year", "EPUB", "3 days ago"), ("Walden", "EPUB", "Last week"), ("Field Notes 2026", "PDF", "Last week"),
         ("Deep Work", "Cal Newport · MOBI", "2 weeks ago"), ("A Pattern Language", "PDF", "3 weeks ago"), ("Kafka on the Shore", "EPUB", "Last month")]

def books_page(width):
    rows = "".join(f'<div class="ra-row"><div class="bk2"></div><div class="tx"><b>{t}</b><span>{d} · {w}</span></div></div>' for t, d, w in BOOKS)
    return (f'<div class="dim2" style="right:{width}"></div><div class="side2 modern" style="width:{width}">'
            f'<div class="ra-hd"><div class="btn sq">◀</div><b style="margin-left:6px">Your books</b><div class="btn sq">✕</div></div>'
            f'<div class="secl">NEOREADER · NEWEST FIRST · TAP TO OPEN</div>{rows}<div class="pager2">◀ &nbsp; 1 / 3 &nbsp; ▶</div>'
            f'<div class="ra-foot"><div class="btn">Back to recent apps</div></div></div>')

def ra_rows_modern(items, compact=False):
    """Rows as the app draws them now: rounded cards, ← badge on the previous app, ✕ on each app."""
    out = ""
    for name, letter, when, kind in items:
        badge = '<div class="bdg">←</div>' if kind == "prev" else ""
        if compact:
            tag = ""
            if kind == "prev": when = "Previous · " + when
        else:
            tag = '<div class="rtag">Open now</div>' if kind == "now" else '<div class="rtag">Previous</div>' if kind == "prev" else ""
        x = "" if kind == "now" else '<div class="xx">✕</div>'
        out += f'<div class="ra-row {kind}"><div class="ai">{letter}{badge}</div><div class="tx"><b>{name}</b><span>{when}</span></div>{tag}{x}</div>'
    return out


def side_lr(width, items, pager, extra=""):
    return (f'<div class="dim2" style="right:{width}"></div><div class="side2 modern" style="width:{width}">'
            f'<div class="ra-hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
            f'{LASTREAD}<div class="secl">RECENT APPS</div>'
            f'{ra_rows_modern(items)}<div class="pager2">{pager}</div>'
            f'<div class="ra-foot"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>{extra}')

LR_APPS = [a for a in RECENT if a[0] != "KOReader"][:7]
LR_APPS = [("Feeder", "F", "Open now", "now"), ("AnkiDroid", "A", "8 min ago", "prev")] + [a for a in RECENT[3:9]]

lastread_board = board("Side panel: last read", """<div class="notes"><h2>13 · Last read in NeoReader</h2>
<ul><li>A <b>Last read</b> card at the top of the side panel: the book you had open last in NeoReader.</li>
<li><b>Tap:</b> NeoReader opens on that book, at your page.</li>
<li><b>Hold:</b> <b>Your books</b>, newest first, in pages; tap one to open it. (NeoReader's own library can't be opened from outside.)</li>
<li>From NeoReader's own book list, as SuperBoox does on BOOX. Shown only once NeoReader has opened a book.</li></ul>
<h3>Choices to make</h3><ul>
<li>Keep NeoReader in the recent-apps list too, or leave it out since the card covers it.</li>
<li>Card on top (shown), or at the bottom above the footer.</li>
<li>Hold: your books in the panel (shown), or just open NeoReader on the last book and use its Doc button.</li></ul></div>""",
    [frame("Portrait", "The card sits above your recent apps.", device("p", side_lr("78%", LR_APPS[:8], "◀ &nbsp; 1 / 2 &nbsp; ▶"))),
     frame("Portrait · held the card", "Your NeoReader books, newest first. Tap one to open it.", device("p", books_page("78%")))],
    frame("Landscape", "Same card, full width of the panel.",
          device("l", f'<div class="dim2" style="right:50%"></div><div class="side2 modern" style="width:50%"><div class="ra-hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
                      f'{LASTREAD}<div class="ra-grid">{ra_rows_modern(LR_APPS[:4], compact=True)}</div><div class="pager2">◀ &nbsp; 1 / 2 &nbsp; ▶</div>'
                      f'<div class="ra-foot"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')))


# ---- Board 14: choosing your reader app (NeoReader, KOReader or none) --------------------------------

def lastread_card(app, title, sub):
    return (f'<div class="lastread"><div class="bk"></div><div class="tx"><i>LAST READ · {app}</i><b>{title}</b>'
            f'<span>{sub}</span></div></div>')

def side_reader(width, card):
    return (f'<div class="dim2" style="right:{width}"></div><div class="side2 modern" style="width:{width}">'
            f'<div class="ra-hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
            f'{card}<div class="secl">RECENT APPS</div>'
            f'{ra_rows_modern(LR_APPS[:8])}<div class="pager2">◀ &nbsp; 1 / 2 &nbsp; ▶</div>'
            f'<div class="ra-foot"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')

def btns(*labels, on=None):
    return "".join(f'<div class="btn{" on" if l == on else ""}">{l}</div>' for l in labels)

def pick(name, sub, on=False):
    return f'<div class="rpick{" on" if on else ""}"><div class="bk3"></div><div class="n"><b>{name}</b><span>{sub}</span></div><div class="dot"></div></div>'

FIRST = (f'<div class="dev p homep">{STATUS}<div class="hp-t">InkPalm+</div>'
         f'<div class="ask"><b>Which reader do you use?</b><span>The side panel keeps the last book you read in it one tap away. You can change this later.</span>'
         f'{pick("NeoReader", "Installed · works as is")}{pick("KOReader", "Installed · asks once for All files access")}'
         f'{pick("No reader", "No book card in the panel")}<div class="btn wide">Continue</div></div>'
         f'<div class="faded">{hrow("Side panel", "On · swipe up or down along the right edge", btns("On", on="On"))}'
         f'{hrow("Strip side", "Where you swipe", btns("Left", "Right", on="Right"))}'
         f'{hrow("Panel width", "How much of the screen the panel covers", btns("66%", "78%", on="78%"))}</div></div>')

KO_CARD = lastread_card("KOREADER", "Recursive Language Models", "1 h ago · tap to keep reading · hold for your books")
KO_ASK = ('<div class="lastread"><div class="bk"></div><div class="tx"><i>KOREADER · ONE MORE STEP</i><b>Allow All files access</b>'
          '<span>So InkPalm+ can read KOReader\'s history. Tap to open the setting.</span></div></div>')

SETTING = (f'<div class="card" style="width:430px"><div class="ttl">INKPALM+ · HOME</div>'
           f'{hrow("Side panel", "On · swipe up or down along the right edge", btns("On", on="On"))}'
           f'{hrow("Reader app", "Its last book sits at the top of the side panel", btns("NeoReader", "KOReader", "None", on="KOReader"))}'
           f'{hrow("Strip side", "Where you swipe", btns("Left", "Right", on="Right"))}'
           f'{hrow("Panel width", "How much of the screen the panel covers", btns("66%", "78%", "90%", on="78%"))}</div>')

reader_board = board("Reader app", """<div class="notes"><h2>14 · Your reader app</h2>
<ul><li><b>First start:</b> one question at the top of the home screen. It lists only the readers you have installed; with none installed it's skipped.</li>
<li><b>Later:</b> a <b>Reader app</b> row on the home screen: NeoReader · KOReader · None.</li>
<li><b>KOReader:</b> same card, same hold for your books (from KOReader's history), same Browse files. It needs Android's <b>All files access</b>, asked once; until then the card says so.</li>
<li>NeoReader needs nothing extra. InkPalm+ still has no internet permission.</li></ul>
<h3>Choices to make</h3><ul>
<li>KOReader access: All files access (shown, one switch) or through Shizuku (no new permission, but Shizuku must be running).</li>
<li>Other readers (Librera, Moon+…): leave out, or list them with tap-to-open only (no last book).</li></ul></div>""",
    [frame("First start", "Readers you have installed. Picked once, then it folds away.", FIRST),
     frame("KOReader picked", "Title from KOReader's book info, else the file name.", device("p", side_reader("78%", KO_CARD))),
     frame("KOReader · no access yet", "Tap opens the All files access screen.", device("p", side_reader("78%", KO_ASK)))],
    frame("Home screen · later", "The question becomes a row under Side panel.", SETTING))


# ---- Board 15: newest app at the bottom (a setting: Top or Bottom) ----------------------------------

BOTTOM_APPS = [("KOReader", "K", "Open now", "now"), ("Feeder", "F", "2 min ago", "prev")] + RECENT[2:8]

def side_bottom(width, items, pager, card=True):
    """Newest at the bottom: rows drawn oldest → newest, and pushed down so the newest sits by the pager."""
    return (f'<div class="dim2" style="right:{width}"></div><div class="side2 modern" style="width:{width}">'
            f'<div class="ra-hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
            f'{KO_CARD if card else ""}<div style="flex:1"></div><div class="secl">RECENT APPS · NEWEST AT THE BOTTOM</div>'
            f'{ra_rows_modern(list(reversed(items)))}<div class="pager2">{pager}</div>'
            f'<div class="ra-foot" style="margin-top:0"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')

ORDER_SETTING = (f'<div class="card" style="width:360px"><div class="ttl">INKPALM+ · HOME</div>'
                 f'{hrow("Reader app", "Its last book sits at the top of the side panel", btns("NeoReader", "KOReader", "None", on="KOReader"))}'
                 f'{hrow("Newest app", "Where the app you’re in sits in the list", btns("Top", "Bottom", on="Bottom"))}'
                 f'{hrow("Strip side", "Where you swipe", btns("Left", "Right", on="Right"))}</div>')

order_board = board("Newest at the bottom", """<div class="notes"><h2>15 · Newest at the bottom</h2>
<ul><li>A <b>Newest app</b> setting on the home screen: <b>Top</b> (today) or <b>Bottom</b>.</li>
<li><b>Bottom:</b> the list reads upward. The app you're in sits just above the pager, the previous app (←) right above it, under your thumb.</li>
<li>Fewer apps than a page: the rows sit at the bottom, the space is at the top.</li>
<li>Page 1 is still the newest apps; ▶ goes to older ones.</li>
<li>The Last read card stays at the top in both.</li></ul>
<h3>Choices to make</h3><ul>
<li>Default for new installs: Top (as now) or Bottom.</li>
<li>With Bottom, should the Last read card move to the bottom too (above the pager)?</li></ul></div>""",
    [frame("Bottom · full page", "Newest by the pager; read upward for older apps.", device("p", side_bottom("78%", BOTTOM_APPS, "◀ &nbsp; 1 / 2 &nbsp; ▶"))),
     frame("Bottom · a short list", "Four apps: they sit at the bottom.", device("p", side_bottom("78%", BOTTOM_APPS[:4], "1 / 1", card=False))),
     frame("Home screen", "One row, under Reader app.", ORDER_SETTING)],
    frame("Landscape · Bottom", "Two columns, newest in the bottom row.",
          device("l", f'<div class="dim2" style="right:50%"></div><div class="side2 modern" style="width:50%"><div class="ra-hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
                      f'{KO_CARD}<div style="flex:1"></div><div class="ra-grid">{ra_rows_modern(list(reversed(BOTTOM_APPS[:4])), compact=True)}</div><div class="pager2">◀ &nbsp; 1 / 2 &nbsp; ▶</div>'
                      f'<div class="ra-foot" style="margin-top:0"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')))


# ---- Board 16: front light in the side panel (KompaktOS + InkControl) ------------------------------

def light_rows(level="60%", warm="40%", on=True):
    if not on:
        return ('<div class="light"><div class="lt-hd"><b>LIGHT</b><div class="btn">Off</div></div>'
                '<div class="lt-off">Light is off · tap Off to turn it on at 60%</div></div>')
    return (f'<div class="light"><div class="lt-hd"><b>LIGHT</b><div class="btn on">On</div></div>'
            f'<div class="lt-row"><span>Brightness</span><div class="btn sq">−</div><b>{level}</b><div class="btn sq">+</div></div>'
            f'<div class="lt-row"><span>Warmth</span><div class="btn sq">−</div><b>{warm}</b><div class="btn sq">+</div></div></div>')

def side_light(width, items, pager, light):
    return (f'<div class="dim2" style="right:{width}"></div><div class="side2 modern" style="width:{width}">'
            f'<div class="ra-hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
            f'<div style="flex:1"></div>{ra_rows_modern(list(reversed(items)))}{KO_CARD}<div class="pager2">{pager}</div>'
            f'{light}<div class="ra-foot" style="margin-top:0"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')

light_board = board("Front light", """<div class="notes"><h2>16 · Front light</h2>
<ul><li>A <b>Light</b> block above the footer: <b>On/Off</b>, <b>Brightness</b> and <b>Warmth</b>, each <b>− value +</b> in steps of 10%.</li>
<li>Steps, not sliders: one change per tap, so e-ink redraws once and nothing ghosts while you drag.</li>
<li>Brightness is the overall level; Warmth is the balance of the cold and warm LEDs (0% all cold, 50% both full, 100% all warm).</li>
<li>Uses InkControl's light API (KompaktOS). With InkControl missing, or on stock firmware, the block isn't there.</li>
<li>Recent apps lose one row per page to make room (7 in portrait with the reader card).</li></ul>
<h3>Choices to make</h3><ul>
<li>Brightness: InkPalm+'s own level (shown) or Android's brightness slider.</li>
<li>Light block always shown, or behind a ☀ button in the header.</li></ul></div>""",
    [frame("Portrait", "Light block above the footer.", device("p", side_light("78%", BOTTOM_APPS[:7], "◀ &nbsp; 1 / 2 &nbsp; ▶", light_rows()))),
     frame("Light off", "One line; tap Off to turn it back on.", device("p", side_light("78%", BOTTOM_APPS[:8], "◀ &nbsp; 1 / 2 &nbsp; ▶", light_rows(on=False))))],
    frame("Landscape", "Same block, steppers side by side.",
          device("l", f'<div class="dim2" style="right:50%"></div><div class="side2 modern" style="width:50%"><div class="ra-hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
                      f'<div style="flex:1"></div><div class="ra-grid">{ra_rows_modern(list(reversed(BOTTOM_APPS[:2])), compact=True)}</div>{KO_CARD}<div class="pager2">◀ &nbsp; 1 / 4 &nbsp; ▶</div>'
                      f'<div class="light land"><div class="lt-hd"><b>LIGHT</b><div class="btn on">On</div></div><div class="lt-row"><span>Bright</span><div class="btn sq">−</div><b>60%</b><div class="btn sq">+</div><span>Warm</span><div class="btn sq">−</div><b>40%</b><div class="btn sq">+</div></div></div>'
                      f'<div class="ra-foot" style="margin-top:0"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')))


# ---- Board 17: books in landscape --------------------------------------------------------------------

def light_new(compact=False):
    def row(label, pct):
        return (f'<div class="lt2"><div class="btn sq">−</div><div class="mid"><b>{label}</b>'
                f'<div class="bar"><i style="width:{pct}"></i></div></div><div class="btn sq">+</div></div>')
    return f'<div class="light2">{row("Brightness 100%", "100%")}{row("Warmth 30%", "30%")}</div>'

BOOKS3 = [("Recursive Language Models", "PDF · 1 h ago"), ("Scaling Managed Agents", "EPUB · 16 h ago"), ("Tribe of Mentors", "EPUB · 20 h ago")]

def books_col(n):
    rows = "".join(f'<div class="ra-row"><div class="bk2"></div><div class="tx"><b>{t}</b><span>{d}</span></div></div>' for t, d in BOOKS3[:n])
    return f'<div class="secl">KOREADER · TAP TO OPEN · HOLD FOR ALL</div>{rows}'

HD = '<div class="ra-hd"><b>Recents</b><div class="btn">Clear all</div><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'
FOOT = '<div class="ra-foot" style="margin-top:0"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div>'

two_col = (f'<div class="dim2" style="right:72%"></div><div class="side2 modern" style="width:72%">{HD}'
           f'<div class="cols"><div class="lc">{light_new()}{books_col(2)}</div>'
           f'<div class="rc"><div style="flex:1"></div>{ra_rows_modern(list(reversed(BOTTOM_APPS[:3])), compact=True)}<div class="pager2">◀ &nbsp; 1 / 4 &nbsp; ▶</div></div></div>{FOOT}</div>')

compact_card = (f'<div class="dim2" style="right:50%"></div><div class="side2 modern" style="width:50%">{HD}{light_new()}'
                f'<div style="flex:1"></div><div class="ra-grid">{ra_rows_modern(list(reversed(BOTTOM_APPS[:2])), compact=True)}</div>'
                f'<div class="lastread slim"><div class="bk"></div><div class="tx"><i>LAST READ · KOREADER</i><b>Recursive Language Models</b></div></div>'
                f'<div class="pager2">◀ &nbsp; 1 / 7 &nbsp; ▶</div>{FOOT}</div>')

books_land = board("Books in landscape", """<div class="notes"><h2>17 · Books in landscape</h2>
<p>With the light sliders, landscape has room for only one row of apps, so the book card doesn't fit as it is.</p>
<h3>A · Two columns (top)</h3><ul>
<li>The panel is wider in landscape (72%). Left: light and your <b>2 latest books</b> (tap to open, hold for all). Right: recent apps, three per page.</li>
<li>Rows the same size as portrait.</li></ul>
<h3>B · Slim card (bottom)</h3><ul>
<li>Panel stays half the screen. A one-line <b>Last read</b> card under the apps; hold it for your books.</li>
<li>Two apps per page.</li></ul>
<p>Either way: pick <b>KOReader</b> under Reader app (it's on NeoReader, which has no books on KompaktOS) and allow All files access.</p></div>""",
    [], frame("A · Two columns", "Books and light on the left, apps on the right.", device("l", two_col)))
books_land = books_land.replace('<div class="row"></div>', '<div class="row">' + frame("B · Slim card", "Half-width panel, one-line card.", device("l", compact_card)) + '</div>')


# ---- Board 18: Auto brightness toggle in the light block ---------------------------------------------

def light_auto(auto, style="pill"):
    pill = f'<span class="pill{" on" if auto else ""}">Auto</span>'
    lab = "Brightness 100%" if not auto else "Brightness · max 100%"
    b = (f'<div class="lt2"><div class="btn sq">−</div><div class="mid"><div class="labl"><b>{lab}</b>{pill}</div>'
         f'<div class="bar"><i style="width:100%"></i></div></div><div class="btn sq">+</div></div>')
    w = ('<div class="lt2"><div class="btn sq">−</div><div class="mid"><div class="labl"><b>Warmth 30%</b></div>'
         '<div class="bar"><i style="width:30%"></i></div></div><div class="btn sq">+</div></div>')
    return f'<div class="light2">{b}{w}</div>'

def side_auto(auto):
    return (f'<div class="dim2" style="right:78%"></div><div class="side2 modern" style="width:78%">{HD}{light_auto(auto)}{KO_CARD}'
            f'{ra_rows_modern(BOTTOM_APPS[1:6])}<div style="flex:1"></div><div class="pager2">◀ &nbsp; 1 / 3 &nbsp; ▶</div>{FOOT}</div>')

auto_board = board("Auto brightness", """<div class="notes"><h2>18 · Auto brightness</h2>
<ul><li>A small <b>Auto</b> pill on the Brightness line: no new row, so no app rows lost.</li>
<li><b>Auto on</b> (filled): Android's adaptive brightness, from the InkPalm's light sensor, dims and brightens the light. The slider sets the most it goes to ("max 100%").</li>
<li><b>Auto off</b>: the slider is the level, as now.</li>
<li>Works because InkControl follows the system brightness. If that's switched off in InkControl, the pill isn't shown.</li></ul></div>""",
    [frame("Auto off", "Pill outlined; slider is the level.", device("p", side_auto(False))),
     frame("Auto on", "Pill filled; slider is the ceiling.", device("p", side_auto(True)))],
    frame("Landscape", "Same pill in the left column.",
          device("l", f'<div class="dim2" style="right:72%"></div><div class="side2 modern" style="width:72%">{HD}'
                      f'<div class="cols"><div class="lc">{light_auto(True)}{books_col(1)}</div>'
                      f'<div class="rc"><div style="flex:1"></div>{ra_rows_modern(list(reversed(BOTTOM_APPS[:3])), compact=True)}<div class="pager2">◀ &nbsp; 1 / 4 &nbsp; ▶</div></div></div>{FOOT}</div>')))


# ---- Board 19: e-ink + light panel on the left edge (KompaktOS, InkControl merged in) ---------------

MODES = [("Fast", "Black and white, quickest"), ("Soft", "Black and white, gentler"), ("Dither", "Fast, dotted grays for pictures"),
         ("Text", "Crisp text, fewer flashes"), ("Quality", "All 16 grays, cleanest"), ("Auto", "The screen picks for each update")]

def modes(on, grid=False):
    return (f'<div class="modes{" grid" if grid else ""}">' +
            "".join(f'<div class="mode{" on" if n == on else ""}"><b>{n}</b><span>{d}</span></div>' for n, d in MODES) + '</div>')

EHD = '<div class="ra-hd"><b>Screen &amp; light</b><div class="btn sq gear">⚙</div><div class="btn sq">✕</div></div>'

def scope(app_on):
    a = ' on' if app_on else ''
    e = '' if app_on else ' on'
    return f'<div class="scope"><div class="btn{a}">Use for KOReader</div><div class="btn{e}">Use everywhere</div></div>'

ACTS = ('<div class="acts"><div class="btn">Full refresh</div><div class="btn sq">−</div>'
        '<div class="ct">Contrast +1</div><div class="btn sq">+</div></div>')

def eink_side(width, on, app_on, src):
    return (f'<div class="dim2 r" style="left:{width}"></div><div class="side2 modern left" style="width:{width}">{EHD}'
            f'<div class="secl" style="margin-top:6px">SCREEN MODE</div>{modes(on)}{scope(app_on)}<div class="src">{src}</div>'
            f'{ACTS}<div style="flex:1"></div><div style="border-top:2px solid #000">{light_auto(False)}</div></div>')

eink_land = (f'<div class="dim2 r" style="left:72%"></div><div class="side2 modern left" style="width:72%">{EHD}'
             f'<div class="cols"><div class="lc"><div class="secl" style="margin-top:4px">SCREEN MODE</div>{modes("Text", grid=True)}</div>'
             f'<div class="rc">{scope(True)}<div class="src">Text · KOReader’s own</div>{ACTS}<div style="flex:1"></div>{light_auto(False)}</div></div></div>')

eink_board = board("Screen and light panel", """<div class="notes"><h2>19 · Left edge: screen &amp; light</h2>
<ul><li>Swipe up or down along the <b>left</b> edge (the middle, like the right one). The right edge stays recent apps.</li>
<li><b>Screen mode</b>: six big buttons, each with one line on what it's for (Fast 12, Soft 14, Dither 13, Text 8, Quality 7, Auto 22). The one in use is filled.</li>
<li><b>Use for KOReader / Use everywhere</b>: where the next tap goes. The line under it says where the mode in use comes from: this app's own, KompaktOS's per-app setting, or everywhere.</li>
<li>After a change the screen is redrawn once (a 150 ms white flash), since a new mode only shows on newly drawn pixels.</li>
<li><b>Full refresh</b> and <b>Contrast</b> (− / +, whole device) on one line.</li>
<li>The <b>light</b> moves here from the recent-apps panel (Brightness, Warmth, Auto pill), so recent apps get their rows back.</li>
<li>Landscape: 72% wide, modes on the left, the rest on the right.</li>
<li>Stock Moaan firmware: unchanged (no left panel; its own e-ink panel stays).</li></ul></div>""",
    [frame("Portrait · this app", "KOReader has its own mode.", device("p", eink_side("78%", "Text", True, "Text · KOReader’s own · tap Use everywhere to clear"))),
     frame("Portrait · everywhere", "No mode of its own: the device-wide one.", device("p", eink_side("78%", "Quality", False, "Quality · everywhere")))],
    frame("Landscape", "Two columns.", device("l", eink_land)))


# ---- Board 22: Boox-style Auto (ceiling slider, sensitivity, lux readout) ------------------------------

def light_boox(auto, sens="0"):
    pill = f'<span class="pill big{" on" if auto else ""}">Auto</span>'
    lab = "Maximum 100%" if auto else "Brightness 100%"
    b = (f'<div class="lt2"><div class="btn sq">−</div><div class="mid"><div class="labl"><b>{lab}</b>{pill}</div>'
         f'<div class="bar"><i style="width:100%"></i></div></div><div class="btn sq">+</div></div>')
    w = ('<div class="lt2"><div class="btn sq">−</div><div class="mid"><div class="labl"><b>Warmth 25%</b></div>'
         '<div class="bar"><i style="width:25%"></i></div></div><div class="btn sq">+</div></div>')
    s = (f'<div class="lt2 sens"><div class="sl"><b>Sensitivity</b><span>120 lux · 63%</span></div>'
         f'<div class="btn sq">−</div><b class="sv">{sens}</b><div class="btn sq">+</div></div>') if auto else ""
    return f'<div class="light2">{b}{w}{s}</div>'

def boox_side(auto, sens="0"):
    return (f'<div class="dim2 r" style="left:78%"></div><div class="side2 modern left" style="width:78%">{EHD}'
            f'<div class="secl" style="margin-top:6px">SCREEN MODE</div>{modes("Text")}{scope(False)}<div class="src">Text · everywhere</div>'
            f'{ACTS}<div style="flex:1"></div><div style="border-top:2px solid #000">{light_boox(auto, sens)}</div></div>')

boox_board = board("Auto brightness, Boox-style", """<div class="notes"><h2>22 · Auto brightness, Boox-style</h2>
<ul><li>Tapping <b>Auto</b> is the only way to turn it on or off. Dragging Brightness or Warmth never does.</li>
<li><b>Auto on</b>: the room's light sets the level through a log curve (about 0.4 lux dim, 1500 lux full). The Brightness slider is now the <b>Maximum</b> that curve is multiplied by. Warmth stays yours.</li>
<li><b>Sensitivity − / +</b> (−2 to +2), shown only with Auto on: each step doubles (or halves) the lux the curve sees, so + is brighter in the same room.</li>
<li>Under it, what the sensor sees and what it does: <b>120 lux · 63%</b> (63% of the maximum).</li>
<li>Calm on e-ink: the light follows a smoothed reading, waits 2 s (brighter) or 4 s (darker) before moving, then goes there in 3 small steps. Waking keeps the last level.</li>
<li>Targets 48 dp.</li></ul></div>""",
    [frame("Auto off", "As before: the slider is the level.", device("p", boox_side(False))),
     frame("Auto on", "Maximum, Sensitivity and the readout.", device("p", boox_side(True)))],
    frame("Landscape", "Same block, right column.",
          device("l", f'<div class="dim2 r" style="left:72%"></div><div class="side2 modern left" style="width:72%">{EHD}'
                      f'<div class="cols"><div class="lc"><div class="secl" style="margin-top:4px">SCREEN MODE</div>{modes("Text", grid=True)}</div>'
                      f'<div class="rc">{scope(False)}<div class="src">Text · everywhere</div><div style="flex:1"></div>{light_boox(True, "+1")}</div></div></div>')))


for name, html in [("01-today", today), ("02-arrows", arrows), ("03-tabs", tabs), ("04-panel-pages", panel_pages),
                   ("05-scrolling", scroll_board), ("06-bleach-invert", bleach_board), ("07-more", more_board),
                   ("08-presets", presets_board), ("09-font", font_board),
                   ("10-touch-button", touch_board), ("11-side-panel", side_board), ("12-home", home_board),
                   ("13-last-read", lastread_board), ("14-reader-app", reader_board), ("15-newest-bottom", order_board), ("16-front-light", light_board), ("17-books-landscape", books_land), ("18-auto-brightness", auto_board), ("19-eink-panel", eink_board), ("22-boox-auto", boox_board)]:
    (HERE / f"{name}.html").write_text(html)
    print(HERE / f"{name}.html")

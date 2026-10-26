#!/usr/bin/env python3
"""Launch images for the README and the Reddit post: the real UI, drawn cleanly in an InkPalm frame
over a book page, with callouts naming each feature (like SuperBoox's docs/launch images).

  python3 docs/launch/build.py && docs/launch/render.sh

Drawn at half the InkPalm's pixels (720×1440 → 360×720 CSS px). Keep it faithful to the app: rounded
outlined cards and buttons, black on white, the ← badge on the previous app (SidePanel.kt).
"""
from pathlib import Path

HERE = Path(__file__).parent

CSS = """
body { margin: 0; height: 1200px; background: #ececec; font-family: -apple-system, "Helvetica Neue", sans-serif; color: #000; }
/* Fixed 1200 CSS px canvas (Quick Look's width), content centred; render.sh crops the height. */
.stage { display: flex; align-items: center; justify-content: center; gap: 40px; width: 1200px; height: 820px; box-sizing: border-box; }
.stage.short { height: 560px; }
.phone { background: #222; border-radius: 34px; padding: 16px 12px 22px; box-shadow: 0 6px 20px rgba(0,0,0,.18); flex: none; }
.phone.l { padding: 12px 22px 12px 16px; }
.screen { position: relative; width: 360px; height: 720px; background: #fff; overflow: hidden; border-radius: 4px; }
.phone.l .screen { width: 720px; height: 360px; }
.status { height: 20px; display: flex; justify-content: space-between; align-items: center; padding: 0 10px; font-size: 10px; font-weight: 600; }
.book { padding: 10px 22px; font-family: Georgia, serif; font-size: 13px; line-height: 1.6; text-align: justify; }
.book h4 { text-align: center; font-size: 17px; margin: 8px 0 2px; font-weight: 400; }
.book .sub { text-align: center; font-size: 10px; letter-spacing: .2em; margin-bottom: 10px; }
.phone.l .book { columns: 2; column-gap: 28px; padding: 6px 26px; }

/* Side panel, as SidePanel.kt draws it */
.side { position: absolute; top: 20px; bottom: 0; right: 0; background: #fff; border: 2.5px solid #000; border-right: 0;
        border-radius: 14px 0 0 14px; display: flex; flex-direction: column; }
.hd { display: flex; align-items: center; gap: 5px; padding: 8px 7px 6px 11px; }
.hd b { flex: 1; font-size: 15.5px; }
.btn { border: 2px solid #000; border-radius: 7px; display: flex; align-items: center; justify-content: center;
       font-size: 11px; font-weight: 700; min-height: 28px; padding: 0 8px; box-sizing: border-box; background: #fff; white-space: nowrap; }
.btn.sq { width: 30px; padding: 0; font-size: 13px; }
.btn.on { background: #000; color: #fff; }
.list { flex: 1; display: flex; flex-direction: column; padding: 0 6px; gap: 5px; }
.row { flex: 1; display: flex; align-items: center; gap: 9px; border: 2px solid #000; border-radius: 10px; padding: 0 7px 0 8px; min-height: 0; }
.row.strong { border-width: 3px; }
.ic { width: 28px; height: 28px; border-radius: 7px; background: #000; color: #fff; font-weight: 700; font-size: 12px;
      display: flex; align-items: center; justify-content: center; flex: none; position: relative; }
.badge { position: absolute; left: -6px; top: -6px; width: 15px; height: 15px; border-radius: 50%; background: #fff; border: 1.7px solid #000;
         font-size: 10px; font-weight: 900; color: #000; display: flex; align-items: center; justify-content: center; line-height: 1; }
.tx { flex: 1; min-width: 0; } .tx b { display: block; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tx span { display: block; font-size: 9.5px; color: #333; }
.tag { font-size: 8.5px; font-weight: 700; border: 1.6px solid #000; border-radius: 5px; padding: 1px 4px; flex: none; }
.x { width: 22px; height: 22px; border: 2px solid #000; border-radius: 6px; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex: none; }
.pager { display: flex; justify-content: center; align-items: center; gap: 10px; padding: 6px 0 4px; font-size: 12px; font-weight: 700; }
.pager .btn { width: 40px; }
.foot { display: flex; gap: 5px; padding: 5px 7px 8px; } .foot .btn { flex: 1; font-size: 10.5px; }
.shade { position: absolute; left: 0; right: 0; top: 0; bottom: 0; }
.menu { position: absolute; left: 14px; right: 14px; top: 230px; background: #fff; border: 3px solid #000; border-radius: 12px; padding: 6px; }
.menu b { display: block; font-size: 13.5px; padding: 4px 8px 6px; }
.menu .btn { margin-top: 5px; min-height: 30px; font-size: 12px; }

/* E-ink panel (QuickPanel.kt): bottom sheet */
.sheet { position: absolute; left: 0; right: 0; bottom: 0; background: #fff; border-top: 3px solid #000; }
.sheet .hd2 { display: flex; align-items: center; gap: 5px; padding: 7px 7px 3px 11px; }
.sheet .hd2 .t { flex: 1; } .sheet .hd2 b { display: block; font-size: 14px; } .sheet .hd2 span { font-size: 9px; color: #333; }
.line { display: flex; align-items: center; gap: 5px; padding: 4px 8px 4px 11px; }
.line .lab { width: 54px; font-size: 11.5px; font-weight: 500; }
.line .val { flex: 1; text-align: center; font-size: 12.5px; font-weight: 700; border-bottom: 2px solid #000; padding: 2px 0; }
.line .val small { display: block; font-size: 8px; font-weight: 400; color: #333; }
.line .sys { width: 34px; }
.sheet .foot .btn { font-size: 10.5px; }

/* Home screen (MainActivity) */
.home { padding: 6px 0; }
.home h1 { font-size: 22px; margin: 6px 14px 0; } .home .subt { font-size: 10.5px; color: #333; margin: 2px 14px 8px; }
.sec { font-size: 10.5px; font-weight: 700; letter-spacing: .06em; margin: 8px 14px 0; padding-bottom: 3px; border-bottom: 2px solid #000; }
.hr { display: flex; align-items: center; gap: 6px; padding: 7px 14px; }
.hr .n { flex: 1; } .hr .n b { display: block; font-size: 13px; font-weight: 500; } .hr .n span { display: block; font-size: 9px; color: #333; }

/* Callouts */
.notes { display: flex; flex-direction: column; gap: 22px; width: 340px; }
.callout { font-size: 17px; line-height: 1.35; }
.callout b { display: block; font-size: 19px; margin-bottom: 2px; }
.num { display: inline-flex; width: 26px; height: 26px; border-radius: 50%; background: #000; color: #fff; font-size: 14px; font-weight: 700;
       align-items: center; justify-content: center; margin-right: 8px; vertical-align: 2px; }
.pin { position: absolute; width: 20px; height: 20px; border-radius: 50%; background: #000; color: #fff; font-size: 11px; font-weight: 700;
       display: flex; align-items: center; justify-content: center; box-shadow: 0 0 0 2.5px #fff; z-index: 5; }
.title { font-size: 30px; font-weight: 800; margin-bottom: 4px; }
.lead { font-size: 16px; color: #333; margin-bottom: 6px; }
"""

BOOK = """<h4>The Lighthouse Year</h4><div class="sub">CHAPTER THREE</div>
<p>The ferry left at six, and by the time the fog lifted the harbour was only a grey line behind them. Mara kept her
notebook on her knee and wrote down everything: the colour of the water, the gulls that followed the wake, the way the
engine changed its note whenever the boat turned into the wind.</p>
<p>Her uncle had told her the island was smaller than it looked on the map. He had not said that the map was forty
years old, or that half the roads on it no longer went anywhere at all.</p>
<p>When the pier came into view she counted the houses along the shore. There were eleven, and only three had smoke
rising from their chimneys. On the hill above them stood the lighthouse, white and patient, its lamp dark in the
morning light.</p>
<p>"You'll want to walk," said the ferryman, tying off the rope. "Nothing on wheels gets far here."</p>
<p>She thanked him and stepped onto the boards, which moved a little under her feet, as if the pier itself were still
deciding whether to let her stay.</p>"""

STATUS = '<div class="status"><span>9:41</span><span>Wi‑Fi · 86%</span></div>'

APPS = [("KOReader", "K", "Open now", "now"), ("Feeder", "F", "4 min ago", "prev"), ("AnkiDroid", "A", "12 min ago", ""),
        ("Instapaper", "I", "35 min ago", ""), ("AntennaPod", "P", "1 h ago", ""), ("Libby", "L", "2 h ago", ""),
        ("Calendar", "C", "3 h ago", ""), ("EinkBro", "E", "Yesterday", ""), ("Notes", "N", "Yesterday", "")]


def row(name, letter, when, kind, compact=False):
    badge = '<div class="badge">←</div>' if kind == "prev" else ""
    if compact:
        tag = ""
        if kind == "prev": when = "Previous · " + when
    else:
        tag = '<div class="tag">Open now</div>' if kind == "now" else '<div class="tag">Previous</div>' if kind == "prev" else ""
    x = "" if kind == "now" else '<div class="x">✕</div>'
    strong = " strong" if kind in ("now", "prev") else ""
    return (f'<div class="row{strong}"><div class="ic">{letter}{badge}</div><div class="tx"><b>{name}</b><span>{when}</span></div>'
            f'{tag}{x}</div>')


def side(width="78%", extra=""):
    rows = "".join(row(*a) for a in APPS)
    return (f'<div class="side" style="width:{width}"><div class="hd"><b>Recent apps</b><div class="btn">Clear all</div>'
            f'<div class="btn sq">⚙</div><div class="btn sq">✕</div></div><div class="list">{rows}</div>'
            f'<div class="pager"><div class="btn">◀</div>1 / 2<div class="btn">▶</div></div>'
            f'<div class="foot"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div>{extra}</div>')


def phone(content, landscape=False, pins=""):
    return f'<div class="phone{" l" if landscape else ""}"><div class="screen">{STATUS}<div class="book">{BOOK}</div>{content}{pins}</div></div>'


def callouts(title, lead, items):
    out = f'<div class="notes"><div><div class="title">{title}</div><div class="lead">{lead}</div></div>'
    for i, (head, text) in enumerate(items, 1):
        out += f'<div class="callout"><b><span class="num">{i}</span>{head}</b>{text}</div>'
    return out + "</div>"


def pin(n, x, y):
    return f'<div class="pin" style="left:{x}px;top:{y}px">{n}</div>'


def page(body, short=False):
    return f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body><div class="stage{" short" if short else ""}">{body}</div></body></html>'


# 1 · The panel
img1 = page(phone(side(), pins=pin(1, 58, 140) + pin(2, 104, 642) + pin(3, 172, 4) + pin(4, 58, 685)) + callouts(
    "Recent apps, one swipe away",
    "Swipe up or down along the edge of the screen in any app.",
    [("Previous app", "The app you came from has a ← badge. One tap and you're back."),
     ("Swipe to turn pages", "Pages instead of scrolling, so nothing smears on e-ink."),
     ("Close apps", "✕ on any app, or Clear all."),
     ("E-ink, per app", "Size, contrast, refresh and rotation for the app you're in.")]))

# 2 · Hold menu
MENU = ('<div class="shade"></div><div class="menu"><b>AnkiDroid</b><div class="btn">Remove from list</div>'
        '<div class="btn">Open its e-ink settings</div><div class="btn">App info</div><div class="btn">Cancel</div></div>')
img2 = page(phone(side(extra=MENU)) + callouts(
    "Hold an app for more",
    "Every row is a rounded card, black on white, no animations.",
    [("Remove from list", "Keep the list to the apps you care about."),
     ("Its e-ink settings", "Jump straight to that app's own page."),
     ("Opens in about 30 ms", "The panel is ready before you swipe.")]))

# 3 · E-ink panel
def line(label, value, note="", sys=None, arrows=("◀", "▶")):
    s = f'<div class="btn sys{" on" if sys else ""}">Sys</div>' if sys is not None else '<div class="sys"></div>'
    n = f"<small>{note}</small>" if note else ""
    return (f'<div class="line"><div class="lab">{label}</div><div class="btn sq">{arrows[0]}</div><div class="val">{value}{n}</div>'
            f'<div class="btn sq">{arrows[1]}</div>{s}</div>')

SHEET = ('<div class="sheet"><div class="hd2"><div class="t"><b>KOReader</b><span>Changes apply right away</span></div>'
         '<div class="btn sq">⚙</div><div class="btn">More</div><div class="btn sq">✕</div></div>'
         + line("Preset", "Reading", "Normal · full refresh every 10")
         + line("Size", "300 dpi", sys=False, arrows=("−", "+"))
         + line("Contrast", "Darker +2", sys=False)
         + line("Rotate", "Portrait", "kept for this app")
         + '<div class="foot"><div class="btn">Full refresh now</div><div class="btn">Reset app</div></div></div>')
img3 = page(phone(SHEET, pins=pin(1, 2, 524) + pin(2, 2, 560) + pin(3, 2, 596) + pin(4, 2, 633)) + callouts(
    "Tune each app for e‑ink",
    "Tap “E-ink · this app” in the panel. Changes show right away.",
    [("Presets", "Reading, Browsing, Comics & photos or Video, in one tap."),
     ("Size (DPI)", "Bigger or smaller, live, without reopening the app."),
     ("Contrast", "Lighter or darker, just for this app."),
     ("Rotate", "The InkPalm has no rotation sensor. This is how you turn the screen, per app.")]))

# 4 · Landscape
LROWS = "".join(f'<div style="display:flex;gap:5px;flex:1">{row(*APPS[i], compact=True)}{row(*APPS[i+1], compact=True)}</div>' for i in range(0, 8, 2))
LSIDE = (f'<div class="side" style="width:50%"><div class="hd"><b>Recent apps</b><div class="btn">Clear all</div><div class="btn sq">⚙</div>'
         f'<div class="btn sq">✕</div></div><div class="list">{LROWS}</div><div class="pager"><div class="btn">◀</div>1 / 2<div class="btn">▶</div></div>'
         f'<div class="foot"><div class="btn">E-ink · this app</div><div class="btn">Android recents</div></div></div>')
img4 = page(f'<div style="display:flex;flex-direction:column;gap:22px">{phone(LSIDE, landscape=True)}'
            '<div class="callout" style="width:720px"><b>Landscape too</b>Half the screen, two columns, the same taps. '
            'Each app can be set to always open in landscape.</div></div>', short=True)

# 5 · Home screen
def hrow(name, sub, right):
    return f'<div class="hr"><div class="n"><b>{name}</b><span>{sub}</span></div>{right}</div>'

# The real home screen, captured from the device (720x1440, shown at half size like the other mockups).
HOME = '<img src="home-screen.png" style="position:absolute;inset:0;width:360px;height:720px;display:block" alt="">'
img5 = page(phone(HOME) + callouts(
    "Simple to set up",
    "One switch to turn it on. Everything else is in short categories.",
    [("Side panel", "Your edge, its width, how many apps it keeps."),
     ("Screen &amp; e-ink · Front light", "Per-app refresh and contrast, and whether the light follows Android."),
     ("Sleep &amp; power", "Sleep screen, power-off screen and auto power-off."),
     ("Reading · Buttons · About", "Pick your reader app, set the touch button, and see the version. No internet permission.")]))

for name, html in [("1-panel", img1), ("2-hold", img2), ("3-eink", img3), ("4-landscape", img4), ("5-home", img5)]:
    (HERE / f"{name}.html").write_text(html)
    print(HERE / f"{name}.html")

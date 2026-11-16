#!/usr/bin/env python3
"""Reorganised InkPalm+ settings mockups. Reuses ../aod/face-picker/build.py helpers. Run: python3 build.py"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "aod", "face-picker"))
import build as P
from build import C, S, F, HEL, frame
from PIL import Image, ImageDraw, ImageFont
GREY = (95, 95, 95)
X0, X1 = 36, 684
W = X1 - X0

class Scr:
    def __init__(s, title, sub=None, back=True):
        s.c = C(720, 1440); frame(s.c); s.pager_h = 0
        c = s.c
        h = 64; y = 40
        c.rr((X0, y, X0+h, y+h), r=14, w=4); c.chev(X0+h/2+3, y+h/2, 15, "l", w=5)
        tx = X0+h+20
        while c.tw(title, 40) > X1-tx: title = title[:-1]
        c.text((tx, y+h/2 - (0 if not sub else 0)), title, 40, anchor="lm")
        s.y = y+h+10
        if sub:
            c.text((X0, s.y+8), sub, 26, bold=False, fill=GREY); s.y += 44
        s.y += 6
        c.line([(X0, s.y), (X1, s.y)], 3); s.y += 0
        s.limit = 1440-16-20
    def wrap(s, t, px, bold, width):
        words = t.split(); lines = [""]
        for w_ in words:
            cand = (lines[-1]+" "+w_).strip()
            if s.c.tw(cand, px, bold) <= width: lines[-1] = cand
            else: lines.append(w_)
        return lines
    def para(s, x, y, t, px, bold=False, width=W, fill=GREY, lh=1.32):
        for l in s.wrap(t, px, bold, width):
            s.c.text((x, y), l, px, bold=bold, fill=fill); y += px*lh
        return y
    def div(s): s.c.line([(X0, s.y), (X1, s.y)], 2)
    def section(s, label, gap=16):
        s.y += gap
        s.c.text((X0, s.y+2), label.upper(), 23); s.y += 36
    def textblock_h(s, title, sub, width, tpx=36, spx=25):
        h = tpx*1.25
        if sub: h += 4 + len(s.wrap(sub, spx, False, width))*spx*1.32
        return h
    def row(s, title, sub=None, chev=True, minh=112, play=False, tpx=36):
        c = s.c; width = W-(50 if chev else 0)
        th = s.textblock_h(title, sub, width, tpx)
        h = max(minh, th+36)
        y0 = s.y
        ty = y0 + (h-th)/2
        c.text((X0+4, ty), title, tpx, anchor="la")
        if sub: s.para(X0+4, ty+tpx*1.25+4, sub, 25, False, width)
        if chev: c.chev(X1-16, y0+h/2, 13, "r", w=6)
        s.y += h; s.div(); return y0
    def toggle(s, title, sub, on, minh=112):
        c = s.c; width = W-130
        th = s.textblock_h(title, sub, width); h = max(minh, th+36); y0 = s.y
        ty = y0+(h-th)/2
        c.text((X0+4, ty), title, 36)
        if sub: s.para(X0+4, ty+36*1.25+4, sub, 25, False, width)
        bx0, by0 = X1-108, y0+h/2-28
        if on:
            c.rr((bx0, by0, X1, by0+56), r=14, w=4, fill="black"); c.text((bx0+54, by0+28), "On", 30, anchor="mm", fill="white")
        else:
            c.rr((bx0, by0, X1, by0+56), r=14, w=4); c.text((bx0+54, by0+28), "Off", 30, anchor="mm")
        s.y += h; s.div()
    def chips(s, labels, cur, x0=X0, x1=X1, h=68, px=30):
        c = s.c; y = s.y; n = len(labels); w = (x1-x0)/n
        c.rr((x0, y, x1, y+h), r=14, w=4)
        for i, l in enumerate(labels):
            a, b = x0+i*w, x0+(i+1)*w
            if i == cur:
                # filled chip, inset rounded
                c.rr((a+1, y+1, b-1, y+h-1), r=12, w=3, fill="black")
            elif i > 0 and i-1 != cur: c.line([(a, y+4), (a, y+h-4)], 3)
            f = px
            while c.tw(l, f) > w-12: f -= 1
            c.text(((a+b)/2, y+h/2), l, f, anchor="mm", fill="white" if i == cur else "black")
        s.y += h
    def choice(s, title, sub, labels, cur, px=30):
        s.y += 14
        s.c.text((X0+4, s.y), title, 36); s.y += 46
        if sub: s.y = s.para(X0+4, s.y, sub, 25, False)+2
        s.y += 6; s.chips(labels, cur, px=px); s.y += 16; s.div()
    def stepper(s, title, sub, value, minh=112):
        c = s.c; th = s.textblock_h(title, sub, W-330); h = max(minh, th+36); y0 = s.y
        ty = y0+(h-th)/2
        c.text((X0+4, ty), title, 36)
        if sub: s.para(X0+4, ty+36*1.25+4, sub, 25, False, W-330)
        bw = 64; cy = y0+h/2
        c.rr((X1-250, cy-32, X1-250+bw, cy+32), r=14, w=4); c.line([(X1-250+18, cy), (X1-250+bw-18, cy)], 5)
        c.text((X1-250+bw+36, cy), value, 30, anchor="mm") if False else c.text((X1-250+bw+(250-2*bw)/2, cy), value, 30, anchor="mm")
        c.rr((X1-bw, cy-32, X1, cy+32), r=14, w=4); c.line([(X1-bw+18, cy), (X1-18, cy)], 5); c.line([(X1-bw/2, cy-18), (X1-bw/2, cy+18)], 5)
        s.y += h; s.div()
    def button(s, label, filled=False, x0=X0, x1=X1, h=84, px=34):
        c = s.c
        c.rr((x0, s.y, x1, s.y+h), r=16, w=4, fill="black" if filled else None)
        c.text(((x0+x1)/2, s.y+h/2), label, px, anchor="mm", fill="white" if filled else "black")
        s.y += h
    def note(s, t, px=24, gap=10, bold_lead=None):
        s.y += gap
        s.y = s.para(X0+4, s.y, t, px, False)+4
    def space(s, n): s.y += n
    def pager(s, i, n, label=None):
        c = s.c; h = 84; y = 1440-16-20-h; s.limit = y-10
        c.rr((X0, y, X0+170, y+h), r=16, w=4, outline="black" if i > 1 else "#bbbbbb")
        c.chev(X0+85-3, y+h/2, 19, "l", w=7, fill="black" if i > 1 else "#bbbbbb")
        c.rr((X1-170, y, X1, y+h), r=16, w=4, outline="black" if i < n else "#bbbbbb")
        c.chev(X1-85+3, y+h/2, 19, "r", w=7, fill="black" if i < n else "#bbbbbb")
        c.text((360, y+h/2-2), f"{i} / {n}", 38, anchor="mm")
        if label: c.text((360, y-22), label, 22, bold=False, anchor="mm", fill=GREY)
    def done(s):
        assert s.y <= s.limit, f"overflow: y={s.y} limit={s.limit}"
        return s.c.out()
    def left(s): return s.limit - s.y

def rowsum(s, title, sub):  # home row w/ icon-less layout
    s.row(title, sub, minh=150)

frames = []
def add(title, fn, img): frames.append((title, fn, img))

# 1 Home
s = Scr("InkPalm+", None)
s.y += 0
for t, sub in [("Sleep screen", "Agenda · Classic · every 5 min"),
               ("Screen & light", "Text mode everywhere · light follows Android"),
               ("Side panel", "Left edge · 9 apps"),
               ("Power off", "Picture: Panda · auto off after 24 h"),
               ("Buttons & reading", "Touch: Home / hold refresh · Reader: KOReader"),
               ("About", "Version 1.1.0 · internet only for weather")]:
    s.row(t, sub, minh=170)
s.note("Every row opens one screen. Nothing is more than two levels deep.", px=24, gap=22)
add("1  Home menu", "1-home.png", s.done())

# 2 Sleep screen p1 (Agenda)
s = Scr("Sleep screen")
c = s.c; y0 = s.y; h = 300
th = Image.open(P.M+"agenda-variants/current-portrait.png").convert("RGB").resize((136*S, 272*S), Image.LANCZOS)
c.rr((X0+2, y0+14, X0+2+140, y0+14+276), r=3, w=3); c.paste(th, (X0+4, y0+16))
tx = X0+176
c.text((tx, y0+60), "Sleep screen:", 28, bold=False, fill=GREY)
c.text((tx, y0+98), "Agenda · Classic", 40)
s.para(tx, y0+156, "Tap to flip through every screen, with your own day in it.", 25, False, W-176-50)
c.d.polygon([((X1-30)*S, (y0+h/2-14)*S), ((X1-30)*S, (y0+h/2+14)*S), ((X1-6)*S, (y0+h/2)*S)], fill="black")
c.rr((tx, y0+232, tx+196, y0+232+52), r=12, w=3); c.text((tx+98, y0+258), "Uses calendar", 24, anchor="mm", bold=False)
s.y += h; s.div()
s.section("Update", gap=12)
s.chips(["Frozen", "5 min", "15 min", "30 min", "60 min"], 1, px=28)
s.note("Frozen is free: drawn once, just before the screen goes off. The others wake the phone to redraw (about 3 s, one flash). Every 5 min is about 3.8% of battery a day (estimate).", gap=14)
s.space(10); s.div()
s.toggle("Show update time", "The small “Updated 10:34” line at the bottom", True)
s.note("Seconds are never shown: a panel refresh every second would drain the battery.", gap=14)
s.note("The front light stays off during updates, so nothing glows in your pocket.")
s.note("Below the low-battery level the face shows a battery box and updates stop. They carry on while charging. In landscape the face is drawn sideways to match.")
add("2  Sleep screen (Agenda)", "2-sleep-page1.png", s.done())

# 2b Sleep screen p1 with Home face selected -> page 1 / 2
s = Scr("Sleep screen")
c = s.c; y0 = s.y; h = 300
th = Image.open(P.M+"home-face/A-dashboard-portrait.png").convert("RGB").resize((136*S, 272*S), Image.LANCZOS)
c.rr((X0+2, y0+14, X0+2+140, y0+14+276), r=3, w=3); c.paste(th, (X0+4, y0+16))
c.text((tx, y0+60), "Sleep screen:", 28, bold=False, fill=GREY)
c.text((tx, y0+98), "Home · Dashboard", 40)
s.para(tx, y0+156, "Tap to flip through every screen, with your own day in it.", 25, False, W-176-50)
c.d.polygon([((X1-30)*S, (y0+h/2-14)*S), ((X1-30)*S, (y0+h/2+14)*S), ((X1-6)*S, (y0+h/2)*S)], fill="black")
s.y += h; s.div()
s.section("Update", gap=12)
s.chips(["Frozen", "5 min", "15 min", "30 min", "60 min"], 1, px=28)
s.note("Frozen is free. The others wake the phone to redraw (about 3 s, one flash). Every 5 min is about 3.8% of battery a day (estimate).", gap=14)
s.space(10); s.div()
s.toggle("Show update time", "The small “Updated 10:34” line at the bottom", True)
s.note("Seconds are never shown: a panel refresh every second would drain the battery.", gap=14)
s.note("The front light stays off during updates, so nothing glows in your pocket.")
s.pager(1, 2, "Home face options on the next page")
add("2b  Sleep, Home face p.1", "2b-sleep-page1-home.png", s.done())

# 3 Sleep page 2
s = Scr("Sleep screen \u203a Home face")
s.section("Home face", gap=8)
s.row("Weather location", "Amsterdam, Netherlands")
s.choice("Units", "Auto follows your region (now °C)", ["Auto", "°C", "°F"], 0)
s.row("Countdowns", "From your calendar · 2 pinned · 1 hidden")
s.row("Trash day", "Recycling Tue · Trash Fri")
s.note("Weather is fetched at most every 30 min during updates, only when Wi-Fi is already on. It costs about 0.4% of battery a day.", gap=14)
s.note("The Home face shows your events and countdowns, so it needs calendar access.")
s.pager(2, 2)
add("3  Sleep, Home face p.2", "3-sleep-page2.png", s.done())

# 4 Weather
s = Scr("Sleep screen › Weather")
c = s.c; y0 = s.y+14
c.text((X0+4, y0), "Amsterdam, Netherlands", 38); c.text((X0+4, y0+50), "Forecast from 10:15 · Open-Meteo", 25, bold=False, fill=GREY)
c.rr((X1-170, y0+4, X1, y0+4+64), r=14, w=4); c.text((X1-85, y0+36), "Remove", 28, anchor="mm")
s.y = y0+96; s.div()
s.section("Search", gap=6)
fy = s.y
c.rr((X0, fy, X1-190, fy+76), r=12, w=3); c.text((X0+18, fy+38), "Utrecht", 32, anchor="lm", bold=False)
c.rr((X1-170, fy, X1, fy+76), r=14, w=4, fill="black"); c.text((X1-85, fy+38), "Search", 30, anchor="mm", fill="white")
s.y += 90
for a, b in [("Utrecht", "Utrecht, Netherlands"), ("Utrecht", "Gelderland, Netherlands")]:
    s.row(a, b, chev=False, minh=112)
s.space(6)
s.button("Use my location", filled=False, h=84)
s.note("Finds your location once, when you tap it, to about 1 km. It is never tracked.", gap=8)
s.space(14)
s.c.line([(X0, s.y), (X1, s.y)], 2); s.y += 16
s.c.text((X0+4, s.y), "No location? Guess the city instead.", 25, bold=False, fill=GREY); s.y += 40
s.button("Estimate from internet connection", False, h=76, px=30)
s.note("Estimating sends your public IP address to ipinfo.io, once, only when you tap it. Searching sends the city name to Open-Meteo (open-meteo.com, no account). Nothing else leaves the phone.", gap=12)
add("4  Weather location (level 2)", "4-weather.png", s.done())

# 5 Screen & light p1
s = Scr("Screen & light")
s.section("Screen mode, everywhere", gap=8)
modes = [("Fast", "Quickest redraw, rougher grays"), ("Soft", "Smoother grays, a little slower"),
         ("Dither", "Dotted shading for photos"), ("Text", "Sharp black and white for reading"),
         ("Quality", "Best detail, slowest redraw"), ("Auto", "Picks a mode for what is on screen")]
cur = 3
for i, (n, d) in enumerate(modes):
    y0 = s.y; h = 100
    if i == cur:
        s.c.rr((X0, y0+4, X1, y0+h-4), r=14, w=4, fill="black")
        s.c.text((X0+22, y0+h/2-16), n, 34, anchor="lm", fill="white"); s.c.text((X0+22, y0+h/2+22), d, 24, anchor="lm", bold=False, fill="white")
        s.c.check(X1-40, y0+h/2, 14, w=7, fill="white")
    else:
        s.c.text((X0+22, y0+h/2-16), n, 34, anchor="lm"); s.c.text((X0+22, y0+h/2+22), d, 24, anchor="lm", bold=False, fill=GREY)
        s.c.line([(X0, y0+h), (X1, y0+h)], 2)
    s.y += h
s.note("A mode can also be set for one app, from the side panel's screen strip.", gap=10)
s.choice("Full refresh", "Clears ghosting; every so many page changes", ["Never", "5", "10", "20"], 1)
s.stepper("Contrast", "Text and lines", "3")
s.pager(1, 2)
add("5  Screen & light, page 1 of 2", "5-screen-light-p1.png", s.done())

s = Scr("Screen & light")
s.section("Front light", gap=8)
s.toggle("Follows Android's brightness", "The brightness slider and adaptive brightness dim the light too", True)
s.note("Brightness, warmth and Auto live in the side panel's screen strip: swipe along the right edge. The light goes off with the screen and comes back with it, and stays off while the sleep screen updates.", gap=12)
s.section("KompaktOS", gap=22)
s.toggle("KompaktOS per-app modes", "Apps you set in KompaktOS's settings use that mode, unless you pick one here", True)
s.toggle("Real refresh on Refresh", "Its own refresh tile only flashes black here; this adds a full refresh", True)
s.pager(2, 2)
add("5b  Screen & light, page 2 of 2", "5b-screen-light-p2.png", s.done())

# 6 Side panel p1
s = Scr("Side panel")
s.note("Swipe up or down along the screen edge in any app for your recent apps.", gap=10)
s.toggle("Side panel", "On", True)
s.choice("Edge", "Where you swipe for recent apps", ["Left", "Right"], 0)
s.choice("Panel width", "How much of the screen it covers", ["66%", "78%", "90%"], 0)
s.choice("Apps kept", "Recent apps the panel remembers", ["9", "18", "27"], 0)
s.choice("Newest app", "Where the app you're in sits in the list", ["Top", "Bottom"], 0)
s.y += 14
s.c.text((X0+4, s.y), "Clear recent apps", 36); s.c.text((X0+4, s.y+44), "Start the list again", 25, bold=False, fill=GREY)
s.c.rr((X1-150, s.y+8, X1, s.y+8+64), r=14, w=4); s.c.text((X1-75, s.y+40), "Clear", 28, anchor="mm")
s.y += 100; s.div()
s.pager(1, 2)
add("6  Side panel, page 1 of 2", "6-side-panel-p1.png", s.done())

s = Scr("Side panel", "Strip and keeping it on")
s.stepper("Strip width", "How wide the swipe area is", "12 dp")
s.toggle("Show strip", "A thin black line where you swipe", True)
s.toggle("Screen & light strip", "Swipe along the right edge for screen modes and the front light", True)
s.toggle("Edge Back", "Swipe in from either edge to go back (KompaktOS has no back gesture)", True)
s.section("Keeping the panel on", gap=14)
s.note("So the panel survives Clear Memory and restarts. Both are optional.", gap=0)
s.toggle("Survives Clear Memory", "InkPalm+ is on Moaan's Clear Memory whitelist", True)
s.toggle("Turns itself back on", "If the panel is ever switched off by mistake, InkPalm+ turns it back on", True)
s.pager(2, 2)
add("6b  Side panel, page 2 of 2", "6b-side-panel-p2.png", s.done())

# 7 Power off
s = Scr("Power off")
s.section("Picture when off", gap=8)
y0 = s.y
for i, (n, d) in enumerate([("Panda", "Moaan's panda, in English"), ("Moaan", "The Moaan logo"), ("Blank", "Plain white"), ("Your picture…", "Pick one from your photos")]):
    h = 100; on = (i == 0)
    if on:
        s.c.rr((X0, s.y+4, X1, s.y+h-4), r=14, w=4, fill="black")
        s.c.text((X0+22, s.y+h/2-16), n, 34, anchor="lm", fill="white"); s.c.text((X0+22, s.y+h/2+22), d, 24, anchor="lm", bold=False, fill="white"); s.c.check(X1-40, s.y+h/2, 14, w=7, fill="white")
    else:
        s.c.text((X0+22, s.y+h/2-16), n, 34, anchor="lm"); s.c.text((X0+22, s.y+h/2+22), d, 24, anchor="lm", bold=False, fill=GREY)
        s.c.line([(X0, s.y+h), (X1, s.y+h)], 2)
        if i == 3: s.c.chev(X1-16, s.y+h/2, 13, "r", w=6)
    s.y += h
s.note("What stays on the screen when the phone is off. It costs no battery.", gap=8)
s.choice("Auto power-off", "After this long asleep on battery, like the stock firmware", ["Never", "4 h", "8 h", "12 h", "24 h"], 4, px=28)
s.note("Auto power-off only counts time on battery; while charging the phone stays asleep, not off.", gap=4)
add("7  Power off", "7-power-off.png", s.done())

# 8 Buttons & reading
s = Scr("Buttons & reading")
s.section("Touch button", gap=8)
s.note("The button below the screen. On KompaktOS a short touch is always Home.", gap=0)
s.y += 8
s.row("Tap", "Home", chev=False, minh=96)
s.choice("Hold", "Touch for about a second. Needs the side panel on.", ["Nothing", "Full refresh"], 1)
s.note("Volume keys always turn pages, the same way in every rotation.", gap=8)
s.section("Reader app", gap=24)
s.choice("Reader", "Its last book sits in the side panel, one tap away", ["NeoReader", "KOReader", "None"], 1)
s.note("Covers on the Reading sleep screen come from the book file (EPUB). KOReader keeps its history in shared storage, so InkPalm+ needs All files access to read it.", gap=6)
s.y += 8
s.button("Allow All files access", filled=False, h=76, px=30)
add("8  Buttons & reading", "8-buttons-reading.png", s.done())

# 9 About
s = Scr("About")
s.y += 8
c = s.c; c.text((X0+4, s.y+6), "Version", 36); c.text((X1, s.y+6), "1.1.0", 36, anchor="ra"); s.y += 64; s.div()
s.note("Internet is used only for the Home sleep screen's weather. A city name is searched at Open-Meteo, and its coordinates are sent, at most every 30 min and only on a network that is already on. “Estimate from internet connection” sends your public IP address to ipinfo.io once, only when you tap it. Nothing else leaves the phone.", gap=14)
s.note("The side panel's accessibility service only learns which app is in front, and that a page scrolled, text was typed or the screen changed. It does not read what is on screen or what you type.", gap=14)
s.note("E-ink control: KompaktOS bridge connected.", gap=14)
s.note("Source and help: github.com/rn-os/inkpalm-plus. Not affiliated with Moaan.", gap=14)
add("9  About", "9-about.png", s.done())

# Before / after
def compare():
    c = C(1440, 1440); frame(c)
    c.text((720, 62), "Before and after", 44, anchor="mm")
    def col(x, title, rows, note):
        c.text((x, 130), title, 34)
        y = 190
        for t, sub, kids in rows:
            h = 54+len(kids)*40+ (24 if kids else 0)
            c.rr((x, y, x+620, y+h), r=14, w=3)
            c.text((x+18, y+28), t, 30, anchor="lm")
            if sub: c.text((x+602, y+28), sub, 22, anchor="rm", bold=False, fill=GREY)
            for k, kid in enumerate(kids): c.text((x+40, y+68+k*40-6), "› "+kid, 24, anchor="lm", bold=False)
            y += h+14
        c.text((x, y+10), note, 24, bold=False, fill=GREY)
    col(50, "Now: 7 rows, three levels in places", [
        ("Side panel", "", ["one long scrolling list of 11 settings"]),
        ("Screen & e-ink", "", ["status box, 2 toggles, then a swipe-strip note"]),
        ("Front light", "", ["one toggle on its own screen"]),
        ("Sleep & power", "", ["Sleep screen › (face, update, 2 fake rows)", "   › Weather / Countdowns / Trash", "Power-off screen, Auto power-off"]),
        ("Reading", "", ["reader app + two notes"]),
        ("Buttons", "", ["touch button tap/hold"]),
        ("About", "", []),
    ], "Notes shown as rows, long pages scroll.")
    col(750, "New: 6 rows, two levels, paged", [
        ("Sleep screen", "1–2 pages", ["Face, update, update time", "Home face options › Weather, Countdowns, Trash"]),
        ("Screen & light", "2 pages", ["Mode, refresh, contrast, front light"]),
        ("Side panel", "2 pages", ["Edge, widths, strip, keeping it on"]),
        ("Power off", "", ["Picture, auto power-off"]),
        ("Buttons & reading", "", ["Touch button, volume note, reader app"]),
        ("About", "", []),
    ], "Notes under their setting, ‹ › pages instead of scrolling.")
    return c.out()

for t, fn, im in frames: im.save(os.path.join(HERE, fn)); print(fn, im.size)
cmp_ = compare(); cmp_.save(os.path.join(HERE, "0-before-after.png"))

# sheet
pw, pad, gap, lab = 460, 40, 36, 64
fs = ImageFont.truetype(HEL, 26, index=1)
cols = 5
items = [(t, im.resize((pw, int(im.size[1]*pw/im.size[0])), Image.LANCZOS)) for t, fn, im in frames]
rows = [items[i:i+cols] for i in range(0, len(items), cols)]
ph = items[0][1].size[1]
Wd = pad*2+cols*pw+(cols-1)*gap
cw = Wd-2*pad; ch = int(cmp_.size[1]*cw/cmp_.size[0]) if False else None
Hh = pad+len(rows)*(lab+ph+gap)+lab+int(cmp_.size[1]*(2*pw+gap)/cmp_.size[0])+pad
sh = Image.new("RGB", (Wd, Hh), "white"); d = ImageDraw.Draw(sh)
y = pad
for r in rows:
    x = pad
    for t, im in r:
        d.text((x, y+8), t, font=fs, fill="black"); sh.paste(im, (x, y+lab))
        d.rectangle((x-2, y+lab-2, x+pw+1, y+lab+im.size[1]+1), outline="black", width=2); x += pw+gap
    y += lab+ph+gap
cm = cmp_.resize((2*pw+gap, int(cmp_.size[1]*(2*pw+gap)/cmp_.size[0])), Image.LANCZOS)
d.text((pad, y+8), "0  Before / after", font=fs, fill="black"); sh.paste(cm, (pad, y+lab))
d.rectangle((pad-2, y+lab-2, pad+cm.size[0]+1, y+lab+cm.size[1]+1), outline="black", width=2)
sh.save(os.path.join(HERE, "settings-sheet.png")); print("sheet", sh.size)

#!/usr/bin/env python3
"""InkPalm+ settings with named tabs + Advanced rows. Reuses ../settings-reorg/build.py (and ../aod/face-picker/build.py) helpers (Scr etc.). Run: python3 build.py"""
import os, sys, re
HERE = os.path.dirname(os.path.abspath(__file__))
SM = os.path.join(HERE, "..", "settings-reorg")
sys.path.insert(0, os.path.join(HERE, "..", "aod", "face-picker"))
import build as P
from build import C, S, F, HEL, frame
from PIL import Image, ImageDraw, ImageFont

src = open(os.path.join(SM, "build.py")).read().split("frames = []")[0]
EXT = '''
def _tabs(s, labels, cur, x0=None, x1=None, h=76):
    x0 = X0 if x0 is None else x0; x1 = X1 if x1 is None else x1
    s.y += 16; s.chips(labels, cur, x0=x0, x1=x1, h=h, px=32); s.y += 16
    s.c.line([(X0, s.y), (X1, s.y)], 2)
def _pin_row(s, title, sub):
    y = s.limit - 118
    assert s.y <= y, f"overflow before pinned row: y={s.y} pin={y}"
    s.y = y; s.c.line([(X0, y), (X1, y)], 2); s.row(title, sub, minh=118)
def _note(s, t, px=24, gap=10, bold_lead=None):
    s.y += gap; s.y = s.para(X0+4, s.y, t, px, False, width=W-8)+4
Scr.note = _note
Scr.tabs = _tabs; Scr.pin_row = _pin_row
'''
def make(landscape=False):
    t = src
    if landscape:
        t = t.replace("C(720, 1440)", "C(1440, 720)").replace("s.limit = 1440-16-20", "s.limit = 720-16-20").replace("X0, X1 = 36, 684", "X0, X1 = 36, 1404")
    ns = {"__file__": os.path.join(SM, "build.py"), "__name__": "sm"}
    exec(compile(t + EXT, "sm", "exec"), ns)
    return ns

N = make()
Scr = N["Scr"]
def thumb(c, path, x, y, w=136, h=272, landscape=False):
    th = Image.open(P.M + path).convert("RGB").resize((w*S, h*S), Image.LANCZOS)
    c.rr((x-2, y-2, x+w+2, y+h+2), r=3, w=3); c.paste(th, (x, y))
X0, X1 = 36, 684
def face_block(s, tpath, name, desc, calendar=False):
    c = s.c; y0 = s.y; h = 300
    ty = y0 + (h-272)/2 if not calendar else y0+14
    thumb(c, tpath, X0+4, y0+ 14)
    tx = X0+176
    c.text((tx, y0+60), "Sleep screen:", 28, bold=False, fill=GREY)
    base = y0
    c.text((tx, base+98), name, 40)
    s.para(tx, base+156, desc, 25, False, W-176-50)
    c.d.polygon([((X1-30)*S, (y0+h/2-14)*S), ((X1-30)*S, (y0+h/2+14)*S), ((X1-6)*S, (y0+h/2)*S)], fill="black")
    if calendar:
        c.rr((tx, y0+232, tx+196, y0+232+52), r=12, w=3); c.text((tx+98, y0+258), "Uses calendar", 24, anchor="mm", bold=False)
    s.y += h; s.div()
GREY = (95, 95, 95)
W = 648

frames = []
def add(title, fn, im): frames.append((title, fn, im))
T3 = ["Face", "Updates", "Home face"]

def sleep_screen(title="Sleep screen"):
    return Scr(title)

# 1 Face tab (Home face)
s = Scr("Sleep screen"); s.tabs(T3, 0)
face_block(s, "home-face/A-dashboard-portrait.png", "Home · Dashboard", "Tap to flip through every screen, with your own day in it.", calendar=True)
y0 = s.y+16; c = s.c
c.rr((X0, y0, X1, y0+216), r=14, w=3)
c.text((X0+22, y0+44), "Calendar access needed", 32, anchor="lm")
s.para(X0+22, y0+72, "The Home face shows your events and countdowns, so it needs calendar access.", 25, False, W-44)
c.rr((X0+22, y0+142, X0+22+130, y0+142+50), r=12, w=3, fill="black"); c.text((X0+22+65, y0+167), "Allow", 26, anchor="mm", fill="white")
s.y = y0+216+16
s.note("In landscape the face is drawn sideways to match. It's redrawn every minute while the screen is on, so it's ready when you press the power button.", gap=8)
add("1  Sleep screen · Face tab", "1-sleep-face.png", s.done())

# 1b Face tab with Picture face (variant: only two tabs)
s = Scr("Sleep screen"); s.tabs(["Face", "Updates"], 0)
face_block(s, "face-photo.png", "Picture", "Your own picture, shown in gray.", calendar=False)
s.y += 0
y0 = s.y; c = s.c; h = 112
c.text((X0+4, y0+24), "Your picture", 36); c.text((X0+4, y0+68), "Shown in gray", 25, bold=False, fill=GREY)
c.rr((X1-250, y0+h/2-32, X1, y0+h/2+32), r=14, w=4); c.text((X1-125, y0+h/2), "Choose picture…", 24, anchor="mm")
s.y += h; s.div()
s.choice("Photo style", "Black and white dots (Atkinson): sharp, no grey bands", ["Crisp", "Smooth", "Plain"], 0)
s.note("Updates apply to the drawn faces, not to Default or Picture, so this face has no Home face tab.", gap=12)
add("1b  Face tab with Picture face (variant)", "1b-sleep-face-picture.png", s.done())

# 2 Updates tab
s = Scr("Sleep screen"); s.tabs(T3, 1)
s.y += 14
s.chips(["Frozen", "5 min", "15 min", "30 min", "60 min"], 1, px=28)
s.note("About 4% of battery a day. 288 wakes a day, each about 3 s awake plus one screen flash. Estimate: ~3 s awake per update at ~400 mA, 2500 mAh battery. Stops below 10%.", px=25, gap=14)
s.note("Below 10% the face shows a battery box and updates stop. They carry on while charging.", px=25, gap=8)
s.space(10); s.div()
s.toggle("Show update time", "The small “Updated 10:34” line at the bottom", False)
s.note("Seconds are never shown: a panel refresh every second would drain the battery.", px=25, gap=14)
s.note("The front light stays off during updates, so nothing glows in your pocket.", px=25)
fr2 = s.done(); add("2  Sleep screen · Updates tab", "2-sleep-updates.png", fr2)

# 3 Home face tab
s = Scr("Sleep screen"); s.tabs(T3, 2)
s.row("Weather location", "Amsterdam, Netherlands")
s.choice("Weather updates", "How often the forecast is fetched", ["Every refresh", "15 min", "30 min", "60 min"], 2, px=28)
s.choice("Units", "Auto follows your region (now °C)", ["Auto", "°C", "°F"], 0)
s.row("Countdowns", "From your calendar · 2 pinned · 1 hidden")
s.row("Trash day", "Recycling Tue · Trash Fri")
s.note("Each fetch is one small request over Wi‑Fi, only when Wi‑Fi is already on. With updates every 5 min: about 48 requests a day.", px=25, gap=14)
add("3  Sleep screen · Home face tab", "3-sleep-homeface.png", s.done())

# 4 Screen & light: Screen tab
s = Scr("Screen & light"); s.tabs(["Screen", "Light"], 0)
modes = [("Fast", "Black and white, quickest"), ("Soft", "Black and white, gentler"), ("Dither", "Fast, dotted grays for pictures"),
         ("Text", "Crisp text, fewer flashes"), ("Quality", "All 16 grays, cleanest"), ("Auto", "The screen picks for each update")]
s.section("Screen mode, everywhere", gap=4)
for i, (n, d) in enumerate(modes):
    y0 = s.y; h = 90
    if i == 3:
        s.c.rr((X0, y0+4, X1, y0+h-4), r=14, w=4, fill="black")
        s.c.text((X0+22, y0+h/2-16), n, 34, anchor="lm", fill="white"); s.c.text((X0+22, y0+h/2+22), d, 24, anchor="lm", bold=False, fill="white")
        s.c.check(X1-40, y0+h/2, 14, w=7, fill="white")
    else:
        s.c.text((X0+22, y0+h/2-16), n, 34, anchor="lm"); s.c.text((X0+22, y0+h/2+22), d, 24, anchor="lm", bold=False, fill=GREY)
        s.c.line([(X0, y0+h), (X1, y0+h)], 2)
    s.y += h
s.note("A mode can also be set for one app, from the side panel's screen strip.", px=24, gap=8)
s.y += 6; s.div()
y0 = s.y; h = 112; c = s.c
c.text((X0+4, y0+20), "Full refresh", 36); c.text((X0+4, y0+64), "Clears ghosting from the whole screen", 25, bold=False, fill=GREY)
c.rr((X1-190, y0+h/2-32, X1, y0+h/2+32), r=14, w=4); c.text((X1-95, y0+h/2), "Refresh now", 26, anchor="mm")
s.y += h; s.div()
s.stepper("Contrast", "Text and lines; normal is the firmware's", "Normal")
s.pin_row("Advanced", "Per-app modes · Real refresh")
add("4  Screen & light · Screen tab", "4-screen-screen.png", s.done())

# 5 Light tab
s = Scr("Screen & light"); s.tabs(["Screen", "Light"], 1)
s.toggle("Follows Android's brightness", "The brightness slider and adaptive brightness dim the light too", True)
s.note("Brightness, warmth and Auto live in the screen panel: swipe along the right edge. The light goes off with the screen and comes back with it, and stays off while the sleep screen updates.", px=25, gap=14)
s.pin_row("Advanced", "Per-app modes · Real refresh")
add("5  Screen & light · Light tab", "5-screen-light.png", s.done())

# 6 Side panel
s = Scr("Side panel")
s.note("Swipe up or down along the screen edge in any app for your recent apps.", px=24, gap=8)
s.toggle("Side panel", "On", True, minh=96)
s.choice("Edge", "Where you swipe for recent apps", ["Left", "Right"], 0)
s.choice("Panel width", "How much of the screen it covers", ["66%", "78%", "90%"], 0)
s.choice("Apps kept", "Recent apps the panel remembers", ["9", "18", "27"], 0)
s.choice("Newest app", "Where the app you're in sits in the list", ["Top", "Bottom"], 0)
y0 = s.y; c = s.c; h = 104
c.text((X0+4, y0+h/2-24), "Clear recent apps", 36); c.text((X0+4, y0+h/2+16), "Start the list again", 25, bold=False, fill=GREY)
c.rr((X1-150, y0+h/2-32, X1, y0+h/2+32), r=14, w=4); c.text((X1-75, y0+h/2), "Clear", 28, anchor="mm")
s.y += h
s.pin_row("Advanced", "Strip, Edge Back, keeping it on")
add("6  Side panel (with Advanced row)", "6-side-panel.png", s.done())

# 7 Side panel > Advanced
s = Scr("Side panel › Advanced")
s.section("Strip", gap=6)
s.stepper("Strip width", "How wide the swipe area is", "12 dp")
s.toggle("Show strip", "A thin black line where you swipe", True)
s.toggle("Screen & light strip", "Swipe along the right edge for screen modes and the front light", True)
s.toggle("Edge Back", "Swipe in from either edge to go back (KompaktOS has no back gesture)", True)
s.section("Keeping the panel on", gap=14)
s.note("So the panel survives Clear Memory and restarts. Both are optional.", px=24, gap=0)
s.toggle("Survives Clear Memory", "InkPalm+ is on Moaan's Clear Memory whitelist", True)
s.toggle("Turns itself back on", "If the panel is ever switched off by mistake, InkPalm+ turns it back on", True)
add("7  Side panel › Advanced", "7-side-panel-advanced.png", s.done())

# 7b Screen & light > Advanced
s = Scr("Screen & light › Advanced")
s.section("KompaktOS", gap=6)
s.toggle("KompaktOS per-app modes", "Apps you set in KompaktOS's settings use that mode, unless you pick one here", True)
s.toggle("Real refresh on Refresh", "Its own refresh tile only flashes black here; this adds a full refresh", True)
add("7b  Screen & light › Advanced", "7b-screen-light-advanced.png", s.done())

# 8 landscape
L = make(landscape=True); LS = L["Scr"]
s = LS("Sleep screen"); c = s.c
L["X0"], L["X1"], L["W"] = 36, 700, 664
s.tabs(T3, 1, x0=36, x1=700)
s.c.line([(36, s.y), (1404, s.y)], 2)   # full-width rule under tabs
ytop = s.y
s.y += 16; s.chips(["Frozen", "5 min", "15 min", "30 min", "60 min"], 1, x0=36, x1=700, px=28)
s.note("About 4% of battery a day. 288 wakes a day, each about 3 s awake plus one screen flash. Estimate: ~3 s awake per update at ~400 mA, 2500 mAh battery. Stops below 10%.", px=24, gap=12)
s.note("Below 10% the face shows a battery box and updates stop. They carry on while charging.", px=24, gap=6)
yl = s.y
L["X0"], L["X1"], L["W"] = 740, 1404, 664
s.y = ytop; s.c.line([(720, ytop), (720, 700)], 2)
s.toggle("Show update time", "The small “Updated 10:34” line at the bottom", False)
s.note("Seconds are never shown: a panel refresh every second would drain the battery.", px=24, gap=12)
s.note("The front light stays off during updates, so nothing glows in your pocket.", px=24, gap=6)
s.note("In landscape the face is drawn sideways to match.", px=24, gap=6)
s.y = max(s.y, yl)
fr8 = s.done(); add("8  Landscape · Sleep screen · Updates tab", "8-landscape-sleep-updates.png", fr8)

# 9 compare
cur = Image.open(os.path.join(HERE, "ref-paged-sleep1.png")).convert("RGB")
def cmp9():
    w = 600; pad = 40; lab = 70
    a = cur.resize((w, int(cur.size[1]*w/cur.size[0])), Image.LANCZOS)
    b = fr2.resize((w, int(fr2.size[1]*w/fr2.size[0])), Image.LANCZOS)
    im = Image.new("RGB", (pad*3+2*w, pad+lab+max(a.size[1], b.size[1])+pad), "white"); d = ImageDraw.Draw(im)
    f = ImageFont.truetype(HEL, 30, index=1)
    d.text((pad, pad), "Now: paged, ‹ 1 / 4 › (device, branch.72)", font=f, fill="black")
    d.text((pad*2+w, pad), "Proposed: named tabs, no paging", font=f, fill="black")
    for x, i in ((pad, a), (pad*2+w, b)):
        im.paste(i, (x, pad+lab)); d.rectangle((x-2, pad+lab-2, x+w+1, pad+lab+i.size[1]+1), outline="black", width=2)
    return im
add("9  Current paged vs tabs (frame 2)", "9-compare-paged-vs-tabs.png", cmp9())

for t, fn, im in frames: im.save(os.path.join(HERE, fn)); print(fn, im.size)

# sheet
pw, pad, gap, lab = 460, 40, 36, 64
fs = ImageFont.truetype(HEL, 26, index=1)
port = [f for f in frames if f[2].size == (720, 1440)]
others = [f for f in frames if f[2].size != (720, 1440)]
cols = 5
items = [(t, im.resize((pw, int(im.size[1]*pw/im.size[0])), Image.LANCZOS)) for t, fn, im in port]
rows = [items[i:i+cols] for i in range(0, len(items), cols)]
ph = items[0][1].size[1]
lt, lfn, limg = others[0]; ct, cfn, cimg = others[1]
lw = 2*pw+gap; lim = limg.resize((lw, int(limg.size[1]*lw/limg.size[0])), Image.LANCZOS)
cw = 2*pw+gap; cim = cimg.resize((cw, int(cimg.size[1]*cw/cimg.size[0])), Image.LANCZOS)
Wd = pad*2+cols*pw+(cols-1)*gap
Hh = pad+len(rows)*(lab+ph+gap)+lab+lim.size[1]+gap+lab+cim.size[1]+pad
sh = Image.new("RGB", (Wd, Hh), "white"); d = ImageDraw.Draw(sh)
y = pad
for r in rows:
    x = pad
    for t, im in r:
        d.text((x, y+8), t, font=fs, fill="black"); sh.paste(im, (x, y+lab))
        d.rectangle((x-2, y+lab-2, x+pw+1, y+lab+im.size[1]+1), outline="black", width=2); x += pw+gap
    y += lab+ph+gap
for t, im in ((lt, lim), (ct, cim)):
    d.text((pad, y+8), t, font=fs, fill="black"); sh.paste(im, (pad, y+lab))
    d.rectangle((pad-2, y+lab-2, pad+im.size[0]+1, y+lab+im.size[1]+1), outline="black", width=2); y += lab+im.size[1]+gap
sh.save(os.path.join(HERE, "tabs-sheet.png")); print("sheet", sh.size)

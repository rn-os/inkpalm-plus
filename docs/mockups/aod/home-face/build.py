#!/usr/bin/env python3
"""Home sleep-face mockups (images only). Same pipeline as ../agenda-variants/build.py:
HTML at native 720x1440 / 1440x720 -> PNG via Quick Look -> Pillow sheets. Run: python3 build.py"""
import sys
from pathlib import Path
HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "agenda-variants"))
import build as V
from PIL import Image, ImageDraw, ImageFont

V.HERE = HERE  # render() writes next to us
T = "07:42"
DATE = "Monday 16 November"

def bat(fs=22): return f'<span class="bat" style="font-size:{fs}px"><i></i>76%</span>'

P = {
 "sun": '<circle cx="24" cy="24" r="8"/><path d="M24 4v6M24 38v6M4 24h6M38 24h6M10 10l4 4M34 34l4 4M38 10l-4 4M14 34l-4 4"/>',
 "cloud": '<path d="M13 36h22a8 8 0 0 0 0-16 11 11 0 0 0-21 3A7 7 0 0 0 13 36z"/>',
 "rain": '<path d="M13 28h22a7 7 0 0 0 0-14 10 10 0 0 0-19 3 6 6 0 0 0-3 11z"/><path d="M16 34l-3 7M25 34l-3 7M34 34l-3 7"/>',
 "bin": '<path d="M9 13h30M18 13V8h12v5M13 13l2 29h18l2-29M21 20v15M27 20v15"/>',
 "cart": '<path d="M3 8h7l5 22h22l5-17H12"/><circle cx="19" cy="39" r="3"/><circle cx="33" cy="39" r="3"/>',
 "lock": '<rect x="10" y="22" width="28" height="20" rx="3"/><path d="M16 22v-7a8 8 0 0 1 16 0v7"/><circle cx="24" cy="32" r="2.5" fill="#000"/>',
 "door": '<rect x="13" y="6" width="22" height="36"/><path d="M7 42h34"/><circle cx="29" cy="25" r="2" fill="#000"/>',
 "garage": '<path d="M5 21L24 7l19 14v21H5z"/><path d="M13 42V27h22v15M13 32h22M13 37h22"/>',
 "bulb": '<path d="M17 33c0-5-7-7-7-14a14 14 0 0 1 28 0c0 7-7 9-7 14z"/><path d="M17 39h14M20 44h8"/>',
 "train": '<rect x="11" y="5" width="26" height="30" rx="6"/><path d="M11 20h26M17 35l-5 8M31 35l5 8"/><circle cx="18" cy="28" r="1.5" fill="#000"/><circle cx="30" cy="28" r="1.5" fill="#000"/>',
 "msg": '<path d="M5 8h38v26H21l-10 9v-9H5z"/><path d="M13 18h22M13 25h14"/>',
 "cal": '<rect x="6" y="10" width="36" height="32" rx="3"/><path d="M6 20h36M15 5v9M33 5v9"/>',
 "therm": '<path d="M19 28V9a5 5 0 0 1 10 0v19a9 9 0 1 1-10 0z"/>',
 "drop": '<path d="M24 5C16 17 11 24 11 31a13 13 0 0 0 26 0c0-7-5-14-13-26z"/>',
 "check": '<path d="M9 25l10 10 20-22"/>',
 "grip": '<path d="M8 14h32M8 24h32M8 34h32"/>',
 "back": '<path d="M28 10L14 24l14 14" />',
 "down": '<path d="M24 6v28M12 24l12 12 12-12"/>',
 "up": '<path d="M24 36V8M12 20L24 8l12 12"/>',
}
def ic(n, s=48, sw=3.6, col="#000"):
    return f'<svg width="{s}" height="{s}" viewBox="0 0 48 48" fill="none" stroke="{col}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round" style="flex:none;display:block">{P[n]}</svg>'

def doc(o, body):
    H = 1440 if o == "p" else 720
    foot = f'<div class="abs c" style="left:0;right:0;top:{H-70}px;font-size:26px;font-weight:700">Updated {T}</div>'
    wrap = f'<div style="height:{H-84}px;display:flex;flex-direction:column;overflow:hidden">{body}</div>'
    return V.doc(o, wrap + foot)

def cap(s, fs=22, mb=8, extra=""): return f'<div class="cap" style="font-size:{fs}px;margin-bottom:{mb}px;{extra}">{s}</div>'
def card(inner, style="", inv=False, pad=22, border=4):
    c = "inv" if inv else ""
    return f'<div class="{c}" style="border:{border}px solid #000;border-radius:18px;padding:{pad}px;display:flex;flex-direction:column;{style}">{inner}</div>'
def head(icon, title, fs=22, col="#000"):
    return f'<div style="display:flex;align-items:center;gap:10px">{ic(icon,fs+14,col=col)}<div class="cap" style="font-size:{fs}px">{title}</div></div>'

EVS = [("09:00", "Standup"), ("11:30", "Dentist"), ("14:00", "Call with Sam")]
def ev_rows(fs=34, pad=14, w=130):
    return "".join(f'<div style="display:flex;align-items:baseline;gap:18px;padding:{pad}px 0;border-top:3px solid #000"><b style="font-size:{fs}px;width:{w}px;flex:none;font-weight:800">{t}</b><span style="font-size:{fs}px">{n}</span></div>' for t, n in EVS)

# hourly rain strip 08..19, rain 15-18 high
HRS = list(range(8, 20)); PR = [0,0,0,5,5,10,15,60,90,90,70,25]
def strip(h=64, bw=16, gap=8, fs=20):
    bars = "".join(f'<div style="width:{bw}px;height:{max(6,int(h*p/100))}px;background:{"#000" if p>=50 else "#fff"};border:3px solid #000;border-radius:3px"></div>' for p in PR)
    lab = "".join(f'<span>{x}</span>' for x in ("8","12","16","19"))
    return f'<div><div style="display:flex;align-items:flex-end;gap:{gap}px;height:{h+6}px">{bars}</div><div style="display:flex;justify-content:space-between;font-size:{fs}px;margin-top:4px" class="thin">{lab}</div></div>'

# ---- card contents -------------------------------------------------------------------------
def weather_small(w=0, big=96):
    return f'''{head("rain","Weather")}
<div style="display:flex;align-items:baseline;gap:14px;margin-top:8px"><span class="big" style="font-size:{big}px">9°</span><span style="font-size:28px">H 14 · L 6</span></div>
<div style="font-size:30px;margin:6px 0 10px">Rain 3–6 PM</div>{strip(58, 15, 6)}'''
def trash_small(today=False, fs=42, inv=False):
    c = "#fff" if inv else "#000"
    if today: return f'{head("bin","Trash",col="#fff")}<div style="font-size:{fs+14}px;line-height:1.05;margin-top:12px;font-weight:800">Trash today</div><div class="thin" style="font-size:26px;margin-top:8px">Out by 8 AM</div>'
    return f'{head("bin","Trash",col=c)}<div style="font-size:{fs}px;line-height:1.05;margin-top:14px;font-weight:800">Recycling<br>tomorrow</div><div class="thin" style="font-size:26px;margin-top:10px">Tue 17 Nov</div>'
ITEMS = ["Milk","Eggs","Coffee","Dish soap"]
def shop(fs=34, n=4, more=True, pad=8):
    r = "".join(f'<div style="display:flex;align-items:center;gap:16px;padding:{pad}px 0;font-size:{fs}px"><i style="width:{fs-6}px;height:{fs-6}px;border:4px solid #000;border-radius:6px;flex:none"></i>{x}</div>' for x in ITEMS[:n])
    m = f'<div class="thin" style="font-size:{fs-6}px;padding-top:6px">+ 2 more</div>' if more else ""
    return r + m
def ha_small():
    rows = [("therm","Inside 21° · 45%"),("lock","Front door locked"),("garage","Garage closed"),("bulb","2 lights on")]
    r = "".join(f'<div style="display:flex;align-items:center;gap:12px;font-size:24px;padding:7px 0">{ic(i,34)}<span>{t}</span></div>' for i,t in rows)
    return f'{head("therm","Home")}<div style="margin-top:6px">{r}</div><div class="thin" style="font-size:24px;margin-top:2px">Outside 9°</div>'

# ===== A Dashboard ==========================================================================
def A(o):
    if o == "p":
        top = f'''<div style="display:flex;justify-content:space-between;align-items:center;padding:50px 40px 0"><div class="big" style="font-size:158px;margin-left:-6px">{T}</div>
<div style="text-align:right"><div style="font-size:36px;line-height:1.15">Monday<br>16 November</div><div style="margin-top:12px">{bat()}</div></div></div>
<div style="padding:26px 40px 0">{cap("Next 3",22,6)}{ev_rows()}<div style="border-top:3px solid #000"></div></div>'''
        grid = f'''<div style="flex:1;display:grid;grid-template-columns:1fr 1fr;grid-template-rows:1fr 1fr;gap:18px;padding:26px 40px 0">
{card(weather_small(), "justify-content:space-between")}{card(trash_small(inv=True), "", True)}
{card(head("cart","Shopping")+'<div style="margin-top:4px">'+shop(34,4,True,8)+'</div>')}{card(ha_small())}</div>'''
        return doc(o, top + grid)
    left = f'''<div style="width:40%;padding:38px 0 0 48px;display:flex;flex-direction:column"><div class="big" style="font-size:170px;margin-left:-6px">{T}</div>
<div style="display:flex;align-items:center;gap:26px;margin-top:6px"><span style="font-size:36px">{DATE}</span>{bat()}</div>
<div style="margin-top:28px">{cap("Next 3",22,6)}{ev_rows(32,10,120)}<div style="border-top:3px solid #000"></div></div></div>'''
    grid = f'''<div style="flex:1;display:grid;grid-template-columns:1fr 1fr;grid-template-rows:1fr 1fr;gap:14px;padding:28px 40px 0 36px">
{card(weather_small(big=70).replace("margin:6px 0 10px","margin:2px 0 6px"), "justify-content:space-between", pad=16)}{card(trash_small(fs=36, inv=True), "", True, pad=16)}
{card(head("cart","Shopping")+'<div style="margin-top:2px">'+shop(26,4,True,3)+'</div>', pad=16)}{card(ha_small().replace("font-size:24px;padding:7px 0","font-size:25px;padding:4px 0"), pad=16)}</div>'''
    return doc(o, f'<div style="display:flex;height:100%">{left}{grid}</div>')

# ===== B Calm ===============================================================================
def calm_line(fs=32):
    return f'<div style="display:flex;align-items:center;justify-content:center;gap:14px;font-size:{fs}px">{ic("rain",fs+16)}<span><b style="font-weight:800">9°</b> · Rain 3–6 PM · H 14 L 6</span></div>'
def B(o):
    cw = card(head("bin","Trash") + '<div style="font-size:54px;margin-top:10px;font-weight:800">Recycling tomorrow</div><div class="thin" style="font-size:28px;margin-top:6px">Tue 17 Nov</div>', "", pad=28)
    cs = card(head("cart","Shopping") + '<div style="font-size:40px;margin-top:10px;font-weight:700">Milk, eggs, coffee, dish soap</div><div class="thin" style="font-size:28px;margin-top:6px">+ 2 more</div>', "", pad=28)
    cc = card(head("cal","Countdown") + '<div style="display:flex;align-items:baseline;gap:18px;margin-top:6px"><span class="big" style="font-size:96px">12</span><span style="font-size:42px;font-weight:800">days to Lisbon trip</span></div>', "", pad=28)
    nxt = '<div style="font-size:30px;text-align:center"><span class="cap" style="font-size:22px">Next</span>&nbsp;&nbsp;<b style="font-weight:800">09:00</b> Standup</div>'
    if o == "p":
        body = f'''<div class="c" style="margin-top:60px"><div class="big" style="font-size:236px">{T}</div><div style="font-size:46px;margin-top:10px">{DATE}</div><div style="margin-top:20px">{bat(24)}</div></div>
<div style="margin-top:34px">{nxt}</div><div style="margin:26px 120px 0;border-top:4px solid #000"></div><div style="margin-top:26px">{calm_line(32)}</div>
<div style="flex:1;display:flex;flex-direction:column;justify-content:center;gap:28px;padding:20px 48px 8px;min-height:0">{cw}{cs}{cc}</div>'''
        return doc(o, body)
    left = f'''<div class="c" style="width:48%;display:flex;flex-direction:column;justify-content:center;border-right:4px solid #000;margin:44px 0 20px"><div class="big" style="font-size:240px">{T}</div><div style="font-size:42px;margin-top:6px">{DATE}</div><div style="margin-top:14px">{bat(24)}</div><div style="margin-top:26px">{nxt}</div><div style="margin-top:20px">{calm_line(28)}</div></div>'''
    right = f'<div style="flex:1;display:flex;flex-direction:column;justify-content:center;gap:16px;padding:0 44px 14px 40px">{cw.replace("padding:28px","padding:18px 24px").replace("font-size:54px;margin-top:10px","font-size:44px;margin-top:2px")}{cs.replace("padding:28px","padding:18px 24px").replace("font-size:40px;margin-top:10px","font-size:32px;margin-top:2px")}{cc.replace("padding:28px","padding:16px 24px").replace("font-size:96px","font-size:72px")}</div>'
    return doc(o, f'<div style="display:flex;height:100%">{left}{right}</div>')

# ===== C Kitchen ============================================================================
def C(o):
    msg = card(f'''{head("msg","Message board",col="#fff")}<div style="font-size:{58 if o=="p" else 48}px;line-height:1.1;font-weight:800;margin-top:16px">Back at 6 — dinner's in the fridge</div>
<div class="thin" style="font-size:30px;margin-top:auto;padding-top:14px">Sam · 7:10</div>''', "flex:none;" + ("height:330px" if o=="p" else "flex:1"), True, 30)
    lst = card(head("cart","Shopping list") + '<div style="margin-top:8px">' + shop(44 if o=="p" else 36, 4, True, 11 if o=="p" else 8) + '</div>', "flex:1" if o=="p" else "flex:1", pad=28)
    wsm = card(f'{head("rain","Weather",20)}<div style="display:flex;align-items:baseline;gap:10px;margin-top:6px"><span class="big" style="font-size:64px">9°</span><span style="font-size:24px">H 14 · L 6</span></div><div style="font-size:26px;margin-top:2px">Rain 3–6 PM</div>', "flex:1", pad=18)
    tsm = card(f'{head("bin","Trash",20)}<div style="font-size:34px;font-weight:800;line-height:1.05;margin-top:8px">Recycling<br>tomorrow</div>', "flex:1", pad=18)
    nxt = '<div style="font-size:32px"><span class="cap" style="font-size:22px">Next</span>&nbsp;&nbsp;<b style="font-weight:800">09:00</b> Standup</div>'
    if o == "p":
        body = f'''<div style="display:flex;justify-content:space-between;align-items:center;padding:48px 40px 0"><div class="big" style="font-size:110px;margin-left:-4px">{T}</div><div style="text-align:right"><div style="font-size:30px">{DATE}</div><div style="margin-top:10px">{bat()}</div></div></div>
<div style="padding:12px 40px 0">{nxt}</div>
<div style="flex:1;display:flex;flex-direction:column;gap:20px;padding:22px 40px 10px;min-height:0">{msg}{lst}<div style="display:flex;gap:20px;height:190px;flex:none">{wsm}{tsm}</div></div>'''
        return doc(o, body)
    top = f'<div style="display:flex;align-items:center;gap:36px;padding:26px 48px 0"><div class="big" style="font-size:100px">{T}</div><div style="font-size:30px">{DATE}<div style="margin-top:8px">{bat()}</div></div><div style="margin-left:auto">{nxt}</div></div>'
    body = f'''{top}<div style="flex:1;display:flex;gap:20px;padding:20px 48px 4px;min-height:0"><div style="width:38%;display:flex">{msg}</div><div style="width:34%;display:flex">{lst}</div><div style="flex:1;display:flex;flex-direction:column;gap:20px">{wsm}{tsm}</div></div>'''
    return doc(o, body)

# ===== D Smart home =========================================================================
def tile(icon, label, val, sub="", inv=False, vfs=52):
    col = "#fff" if inv else "#000"
    return card(f'<div style="display:flex;align-items:center;gap:10px">{ic(icon,34,col=col)}<span class="cap" style="font-size:20px">{label}</span></div><div style="font-size:{vfs}px;font-weight:800;line-height:1.05;margin-top:auto;letter-spacing:-.02em">{val}</div>' + (f'<div class="thin" style="font-size:22px">{sub}</div>' if sub else ""), "justify-content:flex-start", inv, 16, 4)
def stat(icon, label, state, inv=False):
    col = "#fff" if inv else "#000"
    return card(f'{ic(icon,56,col=col)}<div class="cap" style="font-size:20px;margin-top:10px">{label}</div><div style="font-size:34px;font-weight:800">{state}</div>', "align-items:flex-start;flex:1", inv, 16)
def D(o):
    cal = f'<div class="box" style="border-width:3px;border-radius:14px;padding:10px 16px;font-size:{26 if o=="p" else 24}px;display:flex;gap:14px;flex-wrap:wrap;align-items:baseline"><span class="cap" style="font-size:20px">Today</span><span><b style="font-weight:800">09:00</b> Standup</span><span><b style="font-weight:800">11:30</b> Dentist</span><span><b style="font-weight:800">14:00</b> Call w/ Sam</span></div>'
    climate = lambda h: f'''<div style="display:grid;grid-template-columns:1fr 1fr;grid-auto-rows:{h}px;gap:14px">{tile("therm","Inside","21° · 45%","",True)}{tile("therm","Outside","9°")}{tile("therm","Living room","21°")}{tile("therm","Bedroom","19°")}{tile("therm","Kitchen","22°")}{tile("therm","Office","20°")}</div>'''
    doors = f'<div style="display:flex;gap:14px">{stat("lock","Front door","Locked",True)}{stat("garage","Garage","Closed")}{stat("door","Back door","Closed")}</div>'
    lights = card(f'<div style="display:flex;align-items:center;gap:14px">{ic("bulb",44)}<div style="font-size:32px;font-weight:800">2 lights on</div></div><div style="display:flex;gap:30px;font-size:30px;margin-top:10px;padding-left:58px"><span>• Kitchen</span><span>• Hall</span></div><div class="thin" style="font-size:22px;padding-left:58px;margin-top:2px">6 other lights off</div>', "", pad=18)
    secure = '<div class="cap" style="font-size:22px;display:flex;align-items:center;gap:10px">' + ic("check",30,4) + 'All secure</div>'
    if o == "p":
        body = f'''<div style="display:flex;justify-content:space-between;align-items:center;padding:48px 40px 0"><div class="big" style="font-size:110px;margin-left:-4px">{T}</div><div style="text-align:right"><div style="font-size:30px">{DATE}</div><div style="margin-top:10px">{bat()}</div></div></div>
<div style="padding:16px 40px 0">{cal}</div>
<div style="padding:26px 40px 0">{cap("Climate",22,8)}{climate(150)}</div>
<div style="padding:24px 40px 0"><div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">{cap("Doors & locks",22,0)}{secure}</div>{doors}</div>
<div style="padding:24px 40px 0">{lights}</div>'''
        return doc(o, body)
    left = f'''<div style="width:36%;padding:30px 0 0 48px;display:flex;flex-direction:column"><div class="big" style="font-size:136px;margin-left:-4px">{T}</div><div style="font-size:32px;margin-top:4px">{DATE}</div><div style="margin-top:12px">{bat()}</div>
<div style="margin-top:26px">{cap("Today",20,6)}{ev_rows(26,7,100)}<div style="border-top:3px solid #000"></div></div><div style="margin-top:22px">{secure}</div></div>'''
    right = f'''<div style="flex:1;padding:26px 44px 0 40px;display:flex;flex-direction:column;gap:12px">
<div style="display:grid;grid-template-columns:1fr 1fr 1fr;grid-auto-rows:140px;gap:12px">{tile("therm","Inside","21° · 45%","",True,44)}{tile("therm","Outside","9°","",False,40)}{tile("therm","Living","21°","",False,40)}{tile("therm","Bedroom","19°","",False,40)}{tile("therm","Kitchen","22°","",False,40)}{tile("therm","Office","20°","",False,40)}</div>
<div style="display:flex;gap:12px">{stat("lock","Front door","Locked",True).replace("padding:16px","padding:16px")}{stat("garage","Garage","Closed").replace("padding:16px","padding:10px 16px")}{stat("door","Back door","Closed").replace("padding:16px","padding:10px 16px")}</div>
<div style="display:flex;align-items:center;gap:14px;font-size:28px;border:4px solid #000;border-radius:18px;padding:10px 18px">{ic("bulb",40)}<b style="font-weight:800">2 lights on</b><span>Kitchen · Hall</span></div></div>'''
    return doc(o, f'<div style="display:flex;height:100%">{left}{right}</div>')

VARS = [("A","dashboard",A),("B","calm",B),("C","kitchen",C),("D","smart-home",D)]
DESC = {"A":"Dashboard","B":"Calm","C":"Kitchen","D":"Smart home"}

# ===== Settings =============================================================================
def settings():
    def row(on, icon, name, sub):
        if on: sub = ""
        box = f'<div style="width:36px;height:36px;border:4px solid #000;border-radius:7px;flex:none;{"background:#000;" if on else ""}display:flex;align-items:center;justify-content:center">{ic("check",28,6,"#fff") if on else ""}</div>'
        return f'<div style="display:flex;align-items:center;gap:14px;padding:{6 if on else 6}px 0;border-top:3px solid #000;">{box}{ic(icon,38)}<div style="flex:1;min-width:0"><div style="font-size:27px;line-height:1.1">{name}</div><div class="thin" style="font-size:21px;line-height:1.15">{sub}</div></div><div style="width:44px;flex:none">{ic("grip",36,4.5)}</div></div>'
    rows = "".join(row(*r) for r in [
        (1,"rain","Weather","Open-Meteo · current location"),
        (1,"bin","Trash day","Recycling Tue · Trash Fri"),
        (1,"cart","Shopping list","Google Tasks · Shopping"),
        (1,"therm","Home Assistant","homeassistant.local:8123 · 6 entities"),
        (0,"msg","Message board","Local note · no sync"),
        (0,"cal","Countdown","Lisbon trip · 28 Nov"),
        (0,"train","Commute","Next train · from Central")])
    field = lambda l, v, mono=False: f'<div style="display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:22px;padding:3px 0"><span class="thin" style="width:100px;flex:none">{l}</span><span style="flex:1;border:3px solid #000;border-radius:10px;padding:3px 12px;font-size:21px;white-space:nowrap;overflow:hidden">{v}</span></div>'
    seg = lambda items, sel, fs=22: '<div style="display:flex;border:4px solid #000;border-radius:12px;overflow:hidden">' + "".join(f'<div style="flex:1;text-align:center;padding:7px 0;font-size:{fs}px;{"background:#000;color:#fff;" if i==sel else ("border-left:3px solid #000;" if i else "")}">{x}</div>' for i,x in enumerate(items)) + '</div>'
    days = lambda sel: '<div style="display:flex;gap:6px">' + "".join(f'<div style="flex:1;text-align:center;padding:6px 0;font-size:22px;border:3px solid #000;border-radius:9px;{"background:#000;color:#fff;" if i in sel else ""}">{d}</div>' for i,d in enumerate("MTWTFSS")) + '</div>'
    src = lambda t, ico, inner: f'<div style="border:4px solid #000;border-radius:14px;padding:8px 16px;margin-top:8px"><div style="display:flex;align-items:center;gap:8px;margin-bottom:2px">{ic(ico,30)}<b style="font-size:23px">{t}</b></div>{inner}</div>'
    body = f'''<div style="margin:20px;border:4px solid #000;border-radius:16px;padding:18px 20px 0;height:1400px;overflow:hidden">
<div style="display:flex;align-items:center;gap:16px"><div style="width:62px;height:50px;border:4px solid #000;border-radius:10px;display:flex;align-items:center;justify-content:center">{ic("back",34,6)}</div><div><div style="font-size:30px;line-height:1.1">Home cards</div></div></div>
<div class="cap" style="font-size:21px;margin:14px 0 4px;opacity:.75">Cards <span class="thin" style="text-transform:none;letter-spacing:0">· tick to show, drag ≡ to reorder</span></div>
<div style="border-bottom:3px solid #000">{rows}</div>
<div class="cap" style="font-size:21px;margin:14px 0 0;opacity:.75">Sources</div>
{src("Weather","rain", field("Provider","Open-Meteo (free, no key)") + field("Location","Current location · network"))}
{src("Home Assistant","therm", field("URL","http://homeassistant.local:8123") + field("Token","•••••••••••••••••••• (long-lived)"))}
{src("Trash day","bin", '<div style="display:flex;align-items:center;gap:10px;margin:4px 0;font-size:21px"><span class="thin" style="width:106px;flex:none">Recycling</span><div style="flex:1">'+days({1})+'</div></div><div style="display:flex;align-items:center;gap:10px;font-size:21px"><span class="thin" style="width:106px;flex:none">Trash</span><div style="flex:1">'+days({4})+'</div></div>')}
{src("Shared list","cart", seg(["Google Tasks","Local list"],0) + field("List","Shopping ▾"))}
<div class="cap" style="font-size:21px;margin:14px 0 6px;opacity:.75">Update</div>
{seg(["Frozen","15 min","30 min","60 min"],1)}
<div style="border:4px solid #000;border-radius:14px;padding:10px 16px;margin-top:10px;font-size:22px;line-height:1.2"><b>Update every 15 min (uses Wi-Fi).</b><br><span class="thin">Wakes the phone, fetches the ticked cards, redraws once. Off Wi-Fi: last data, with its age.</span></div>
</div>'''
    return V.doc("p", body)

def render_html(name, html):
    f = HERE / name; f.write_text(html); return V.render(f)

def font(sz, bold=False):
    try: return ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", sz, index=1 if bold else 0)
    except Exception: return ImageFont.load_default()

if __name__ == "__main__":
    for L, n, fn in VARS:
        for o, suf in (("p","portrait"),("l","landscape")):
            print(render_html(f"{L}-{n}-{suf}.html", fn(o)))
    render_html("home-settings-portrait.html", settings()); import shutil; shutil.move(str(HERE/"home-settings-portrait.png"), str(HERE/"home-settings.png")); (HERE/"home-settings-portrait.html").rename(HERE/"home-settings.html")
    # trash states (extra)
    st = f'<div style="display:flex;gap:30px;padding:30px;background:#fff">{card(trash_small(False), "width:300px;height:260px")}{card(trash_small(True), "width:300px;height:260px", True)}</div>'
    # Home sheet
    sc = .5; pw, ph, lw, lh = 360, 720, 720, 360; m = 28
    W = m + 4*(pw+m) ; W = max(W, m + 2*(lw+m))
    top = 70; H = top + ph + 90 + 2*(lh+80)
    sheet = Image.new("RGB", (W, H), "#fff"); d = ImageDraw.Draw(sheet)
    d.text((m, 14), "InkPalm+ Home face  -  portrait 720x1440 at 50%", fill="#000", font=font(26, True))
    for i, (L, n, _) in enumerate(VARS):
        x = m + i*(pw+m)
        d.text((x, top-30), f"{L}  {DESC[L]}", fill="#000", font=font(22, True))
        im = Image.open(HERE/f"{L}-{n}-portrait.png").resize((pw, ph), Image.LANCZOS); sheet.paste(im, (x, top)); d.rectangle((x-1, top-1, x+pw, top+ph), outline="#000", width=2)
    y0 = top + ph + 50
    d.text((m, y0-34), "Landscape 1440x720 at 50%", fill="#000", font=font(26, True))
    for i, (L, n, _) in enumerate(VARS):
        x = m + (i % 2)*(lw+m); y = y0 + 40 + (i//2)*(lh+60)
        d.text((x, y-30), f"{L}  {DESC[L]}", fill="#000", font=font(22, True))
        im = Image.open(HERE/f"{L}-{n}-landscape.png").resize((lw, lh), Image.LANCZOS); sheet.paste(im, (x, y)); d.rectangle((x-1, y-1, x+lw, y+lh), outline="#000", width=2)
    sheet.save(HERE/"home-sheet.png"); print("home-sheet.png", sheet.size)
    s = Image.open(HERE/"home-settings.png"); print(s.size)

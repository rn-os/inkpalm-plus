#!/usr/bin/env python3
"""Builds the Agenda sleep-face variants (HTML, native 720x1440 and 1440x720), renders PNGs with
Quick Look, and composes sheet.png. Run: python3 build.py  (from this folder; needs Pillow for the sheet)."""
import subprocess, shutil, os
from pathlib import Path
HERE = Path(__file__).parent

CSS = """
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:#fff;color:#000;font-family:-apple-system,"Helvetica Neue",Helvetica,sans-serif;font-weight:700}
.s{position:relative;background:#fff;overflow:hidden;display:flex;flex-direction:column}
.p{width:720px;height:1440px}.l{width:1440px;height:720px}
html,body{margin:0}.s{zoom:.4254}  /* Quick Look draws 1200 CSS px at 2x; half zoom => 1 CSS px = 1 device px, then cropped */
.c{text-align:center}
.big{font-weight:800;letter-spacing:-.045em;line-height:1}
.cap{font-weight:800;letter-spacing:.08em;text-transform:uppercase}
.thin{font-weight:400}
.bat{display:inline-flex;align-items:center;gap:8px;font-weight:700}
.bat i{width:38px;height:18px;border:3px solid #000;border-radius:4px;position:relative;display:inline-block}
.bat i::after{content:"";position:absolute;left:2px;top:2px;bottom:2px;width:70%;background:#000}
.rule{height:4px;background:#000}
.box{border:4px solid #000;border-radius:16px}
.inv{background:#000;color:#fff}
.abs{position:absolute}
"""

def bat(fs=22): return f'<span class="bat" style="font-size:{fs}px"><i></i>82%</span>'

def doc(o, body, extra=""):
    return f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}{extra}</style></head><body><div class="s {o}">{body}</div></body></html>'

# ---- A: big clock + compact next-3 list (rows, no boxes) -----------------------------------
def A(o):
    rows = [("11:30","Dentist","until 12:00"),("14:00","Call with Sam","until 14:30"),("ALL DAY","Parcel","Pick up")]
    def row(t,n,s,last=False):
        tc = 'font-size:34px;width:170px' if t!="ALL DAY" else 'font-size:22px;width:170px;letter-spacing:.04em'
        return f'<div style="display:flex;align-items:center;padding:34px 0;border-top:4px solid #000;{"border-bottom:4px solid #000;" if last else ""}"><div style="{tc};flex:none;font-weight:800">{t}</div><div style="flex:1"><div style="font-size:40px;line-height:1.1">{n}</div><div class="thin" style="font-size:25px;margin-top:6px">{s}</div></div></div>'
    lst = "".join(row(*r, last=(i==2)) for i,r in enumerate(rows))
    if o=="p":
        body=f'''<div class="c" style="margin-top:64px"><div class="big" style="font-size:230px">10:34</div>
<div style="font-size:44px;margin-top:6px">Friday 24 October</div><div style="margin-top:18px">{bat()}</div></div>
<div style="margin:50px 40px 0"><div class="cap" style="font-size:24px;margin-bottom:14px">Next 3</div>{lst}</div>'''
    else:
        body=f'''<div style="display:flex;height:100%"><div class="c" style="width:48%;display:flex;flex-direction:column;justify-content:center;border-right:4px solid #000;margin:48px 0">
<div class="big" style="font-size:230px">10:34</div><div style="font-size:40px;margin-top:6px">Friday 24 October</div><div style="margin-top:16px">{bat()}</div></div>
<div style="flex:1;padding:0 50px;display:flex;flex-direction:column;justify-content:center"><div class="cap" style="font-size:24px;margin-bottom:14px">Next 3</div>{lst}</div></div>'''
    return doc(o,body)

# ---- B: day rail, event blocks by hour -----------------------------------------------------
def B(o):
    H0,H1=8,19
    evs=[(9.0,10.0,"09:00","Standup","Office"),(11.5,12.0,"11:30","Dentist","until 12:00"),(14.0,14.5,"14:00","Call with Sam","until 14:30"),(16.5,17.5,"16:30","Gym","until 17:30")]
    def rail(height, now=10.57):
        px=height/(H1-H0)
        y=lambda h:(h-H0)*px
        out=""
        for h in range(H0,H1+1):
            out+=f'<div class="abs" style="left:0;top:{y(h)-14}px;width:74px;text-align:right;font-size:22px;line-height:28px" >{h:02d}</div><div class="abs" style="left:84px;width:44px;top:{y(h)-1}px;height:3px;background:#000"></div>'
        # hour lines: use dotted via repeating gradient to stay black/white
        for s,e,t,n,sub in evs:
            hh=max((e-s)*px,34)
            out+=f'<div class="abs inv" style="left:92px;width:30px;top:{y(s)}px;height:{hh}px;border-radius:6px"></div>'
            out+=f'<div class="abs" style="left:140px;top:{y(s)-6}px;font-size:30px;line-height:1.15;background:#fff;padding:2px 10px 2px 0"><b>{t} {n}</b> <span class="thin" style="font-size:22px">{sub}</span></div>'
        out+=f'<div class="abs" style="left:74px;right:0;top:{y(now)-3}px;height:6px;background:#000"></div><div class="abs" style="left:64px;top:{y(now)-9}px;width:18px;height:18px;border-radius:9px;background:#000"></div>'
        out+=f'<div class="abs thin cap" style="right:0;top:{y(12.4)+0}px;font-size:20px;display:none"></div>'
        out+=f'<div class="abs cap" style="left:140px;top:{y(15.2)}px;font-size:22px;border:3px dashed #000;border-radius:10px;padding:6px 14px">Free 14:30 – 16:30</div>'
        return f'<div style="position:relative;height:{height}px;margin-left:0">{out}</div>'
    allday='<div class="box" style="padding:10px 18px;font-size:26px;display:inline-block"><b>ALL DAY</b> Parcel · Pick up</div>'
    if o=="p":
        body=f'''<div style="display:flex;align-items:flex-end;justify-content:space-between;padding:50px 40px 0"><div><div class="big" style="font-size:150px">10:34</div><div style="font-size:36px;margin-top:6px">Friday 24 October</div></div><div style="text-align:right;padding-bottom:12px">{bat()}</div></div>
<div style="padding:22px 40px 0">{allday}</div><div style="margin:34px 36px 0 30px">{rail(900)}</div>'''
    else:
        body=f'''<div style="display:flex;height:100%"><div style="width:36%;padding:44px 0 0 44px;display:flex;flex-direction:column"><div class="big" style="font-size:150px;margin-left:-6px">10:34</div><div style="font-size:34px;margin-top:6px">Friday 24 October</div><div style="margin-top:16px">{bat()}</div><div style="margin-top:auto;padding-bottom:44px">{allday.replace("font-size:26px","font-size:22px")}</div></div>
<div style="flex:1;padding:36px 44px 0 20px">{rail(640)}</div></div>'''
    return doc(o,body)

# ---- C: next-up hero card + rest small -----------------------------------------------------
def C(o):
    hero='''<div class="inv" style="border-radius:22px;padding:34px 36px"><div class="cap" style="font-size:24px">Next up · in 56 min</div>
<div class="big" style="font-size:130px;margin-top:14px;letter-spacing:-.03em">11:30</div>
<div style="font-size:72px;line-height:1.05;margin-top:6px">Dentist</div><div class="thin" style="font-size:28px;margin-top:10px">until 12:00 · Dr. Alvarez, 2nd floor</div></div>'''
    small=''.join(f'<div style="display:flex;justify-content:space-between;align-items:baseline;padding:24px 4px;border-bottom:3px solid #000"><span style="font-size:34px"><b style="font-weight:800">{t}</b>&nbsp;&nbsp;{n}</span><span class="thin" style="font-size:24px">{s}</span></div>' for t,n,s in [("14:00","Call with Sam","30 min"),("16:30","Gym","1 h"),("ALL DAY","Parcel","Pick up")])
    if o=="p":
        body=f'''<div style="display:flex;justify-content:space-between;align-items:center;padding:56px 40px 0"><div><div class="big" style="font-size:96px">10:34</div><div style="font-size:32px;margin-top:4px">Friday 24 October</div></div>{bat()}</div>
<div style="margin:44px 40px 0">{hero}</div><div style="margin:34px 40px 0"><div class="cap" style="font-size:22px;margin-bottom:6px">Later today</div><div style="border-top:3px solid #000">{small}</div></div>
<div class="box" style="margin:40px 40px 0;padding:18px 26px;border-width:3px;border-radius:14px;display:flex;justify-content:space-between;align-items:baseline"><span class="cap" style="font-size:22px">Tomorrow</span><span style="font-size:30px">09:00 Farmers market</span></div>'''
    else:
        body=f'''<div style="display:flex;height:100%;padding:0 44px;gap:44px;align-items:center"><div style="width:42%"><div class="big" style="font-size:150px;margin-left:-6px">10:34</div><div style="font-size:36px;margin-top:4px">Friday 24 October</div><div style="margin-top:16px">{bat()}</div></div>
<div style="flex:1">{hero.replace("font-size:130px","font-size:84px").replace("padding:34px 36px","padding:26px 32px").replace("font-size:72px","font-size:48px")}<div style="border-top:0;margin-top:14px">{small.replace("padding:24px 4px","padding:12px 4px").replace("font-size:34px","font-size:30px")}</div></div></div>'''
    return doc(o,body)

# ---- D: today / tomorrow split -------------------------------------------------------------
def D(o):
    today=[("11:30","Dentist"),("14:00","Call with Sam"),("16:30","Gym")]
    tom=[("09:00","Farmers market"),("13:00","Lunch with Ana"),("19:30","Dinner at Rosa's")]
    def col(title,sub,items,allday,fs=38):
        r=''.join(f'<div style="padding:16px 0;border-top:3px solid #000;font-size:{fs}px;line-height:1.15"><b style="font-weight:800">{t}</b><br>{n}</div>' for t,n in items)
        ad=f'<div class="box" style="padding:8px 14px;font-size:{fs-8}px;margin-bottom:12px;border-width:3px;border-radius:12px"><b>ALL DAY</b> {allday}</div>' if allday else ''
        return f'<div><div class="cap" style="font-size:30px">{title}</div><div class="thin" style="font-size:22px;margin:2px 0 14px">{sub}</div>{ad}{r}</div>'
    T=col("Today","Fri 24 Oct",today,"Parcel · Pick up",fs=38 if o=="p" else 32); M=col("Tomorrow","Sat 25 Oct",tom,"",fs=38 if o=="p" else 32)
    if o=="p":
        body=f'''<div style="display:flex;justify-content:space-between;align-items:center;padding:56px 40px 0"><div class="big" style="font-size:120px">10:34</div>{bat()}</div>
<div style="padding:30px 40px 0"><div class="rule"></div></div>
<div style="padding:34px 40px 0">{T}</div><div style="padding:34px 40px 0"><div class="rule" style="height:3px"></div></div><div style="padding:30px 40px 0">{M}</div>'''
    else:
        body=f'''<div style="display:flex;align-items:center;justify-content:space-between;padding:36px 56px 0"><div style="display:flex;align-items:baseline;gap:30px"><div class="big" style="font-size:120px">10:34</div><div style="font-size:34px">Friday 24 October</div></div>{bat()}</div>
<div style="padding:18px 56px 0"><div class="rule"></div></div>
<div style="display:flex;padding:26px 56px 0;gap:0"><div style="flex:1;padding-right:44px;border-right:4px solid #000">{T}</div><div style="flex:1;padding-left:44px">{M}</div></div>'''
    return doc(o,body)

# ---- E: week strip + today's events --------------------------------------------------------
def E(o):
    days=[("M",20,2),("T",21,1),("W",22,3),("T",23,0),("F",24,4),("S",25,3),("S",26,0)]
    cells=''
    for d,n,k in days:
        today = n==24
        dots=''.join('<i style="display:inline-block;width:12px;height:12px;border-radius:6px;background:%s;margin:0 3px"></i>'%("#fff" if today else "#000") for _ in range(min(k,4)))
        cells+=f'<div class="{"inv" if today else ""}" style="flex:1;text-align:center;padding:14px 0 16px;border-radius:14px;{"" if today else "border:3px solid #000;"}"><div style="font-size:22px">{d}</div><div style="font-size:40px;font-weight:800;line-height:1.15">{n}</div><div style="height:16px;margin-top:4px">{dots}</div></div>'
    strip=f'<div style="display:flex;gap:8px">{cells}</div>'
    evs=''.join(f'<div style="display:flex;align-items:baseline;gap:22px;padding:22px 0;border-bottom:3px solid #000"><b style="font-size:38px;font-weight:800;width:{140 if t!="ALL DAY" else 140}px;flex:none;{"font-size:22px;letter-spacing:.05em" if t=="ALL DAY" else ""}">{t}</b><span style="font-size:38px;flex:1">{n}</span><span class="thin" style="font-size:22px">{s}</span></div>' for t,n,s in [("ALL DAY","Parcel","Pick up"),("11:30","Dentist","30 min"),("14:00","Call with Sam","30 min"),("16:30","Gym","1 h")])
    if o=="p":
        body=f'''<div style="padding:44px 40px 0">{strip}</div>
<div class="c" style="margin-top:50px"><div class="big" style="font-size:200px">10:34</div><div style="font-size:40px;margin-top:6px">Friday 24 October</div><div style="margin-top:16px">{bat()}</div></div>
<div style="padding:44px 40px 0"><div class="cap" style="font-size:24px;margin-bottom:10px">Today</div><div style="border-top:3px solid #000">{evs}</div></div>'''
    else:
        body=f'''<div style="padding:30px 44px 0">{strip.replace("padding:14px 0 12px","padding:8px 0 14px")}</div>
<div style="display:flex;flex:1;padding:0 44px;gap:44px;align-items:center"><div class="c" style="width:46%"><div class="big" style="font-size:190px">10:34</div><div style="font-size:36px;margin-top:4px">Friday 24 October</div><div style="margin-top:14px">{bat()}</div></div>
<div style="flex:1"><div class="cap" style="font-size:22px;margin-bottom:6px">Today</div><div style="border-top:3px solid #000">{evs.replace("padding:16px 0","padding:12px 0").replace("font-size:34px","font-size:30px")}</div></div></div>'''
    return doc(o,body)

VARS=[("A","big-clock-list",A),("B","day-rail",B),("C","next-up-hero",C),("D","today-tomorrow",D),("E","week-strip",E)]
DESC={"A":"Big clock, compact next-3 rows","B":"Day rail by hour","C":"Next-up hero card","D":"Today / tomorrow split","E":"Week strip + today"}

def render(html):
    subprocess.run(["qlmanage","-t","-s","2400","-o",str(HERE),str(html)],capture_output=True)
    from PIL import Image
    out=HERE/(html.name+".png"); dst=html.with_suffix(".png"); shutil.move(out,dst)
    size=(720,1440) if "portrait" in html.name else (1440,720)
    Image.open(dst).convert("RGB").crop((0,0)+size).save(dst); return dst

if __name__=="__main__":
    from PIL import Image
    for L,name,fn in VARS:
        for o,suf in (("p","portrait"),("l","landscape")):
            f=HERE/f"{L}-{name}-{suf}.html"; f.write_text(fn(o)); print(render(f))

    # ---- Current face (landscape redrawn from AodFaces.kt's landscape layout; portrait is ../face-agenda.png) ----
    def ev(t,s): return f'<div class="box" style="padding:12px 20px;margin:8px 0;border-width:4px;border-radius:20px"><div style="font-size:34px">{t}</div><div class="thin" style="font-size:24px;font-weight:400">{s}</div></div>'
    cur=f'''<div style="display:flex;height:100%"><div class="c" style="width:50%;display:flex;flex-direction:column;justify-content:center"><div class="big" style="font-size:190px">10:34</div><div style="font-size:44px;margin-top:6px">Friday 24 October</div></div>
<div style="flex:1;padding:0 44px 0 0;display:flex;flex-direction:column;justify-content:center"><div class="c cap" style="font-size:30px;margin-bottom:6px">Next 3</div>{ev("11:30  Dentist","until 12:00")}{ev("14:00  Call with Sam","until 14:30")}{ev("All day  Parcel","Pick up")}</div></div>'''
    f=HERE/"current-landscape.html"; f.write_text(doc("l",cur)); render(f)
    shutil.copy(HERE.parent/"face-agenda.png",HERE/"current-portrait.png")

    # ---- contact sheet ----
    from PIL import ImageDraw, ImageFont
    names=[("Current","current")]+[(L,f"{L}-{n}") for L,n,_ in VARS]
    sc=0.5; pw,ph,lw,lh=int(720*sc),int(1440*sc),int(1440*sc*0.5),int(720*sc*0.5)
    colw=pw+24; W=colw*len(names)+24; top=70; gap=70
    # landscape row: each landscape is 720x360 scaled at 0.5 of 1440 => width = pw
    lw=pw; lh=int(720*pw/1440)
    H=top+ph+gap+lh+70
    sheet=Image.new("RGB",(W,H),"#fff"); d=ImageDraw.Draw(sheet)
    try: font=ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc",34,index=1); small=ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc",20)
    except Exception: font=small=ImageFont.load_default()
    for i,(lab,stem) in enumerate(names):
        x=24+i*colw
        d.text((x,14),lab+(" — "+DESC[lab] if lab in DESC else " (as shipped)"),fill="#000",font=small)
        p=Image.open(HERE/f"{stem}-portrait.png").resize((pw,ph),Image.LANCZOS); sheet.paste(p,(x,top)); d.rectangle((x-1,top-1,x+pw,top+ph),outline="#000",width=2)
        y=top+ph+gap-20
        l=Image.open(HERE/f"{stem}-landscape.png").resize((lw,lh),Image.LANCZOS); sheet.paste(l,(x,y+20)); d.rectangle((x-1,y+19,x+lw,y+20+lh),outline="#000",width=2)
    sheet.save(HERE/"sheet.png"); print("sheet.png",sheet.size)

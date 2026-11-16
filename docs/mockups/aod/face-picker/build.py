#!/usr/bin/env python3
"""Flip-through sleep-screen picker mockups. Pure Pillow, 2x supersampled. Run: python3 build.py"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageOps
M = os.path.expanduser("~/Github Repos/inkpalm+/docs/mockups/aod/")
HERE = os.path.dirname(os.path.abspath(__file__))
S = 2
HEL = "/System/Library/Fonts/Helvetica.ttc"
def F(px, bold=True): return ImageFont.truetype(HEL, int(px*S), index=1 if bold else 0)

# ---- entries: (group, name, desc, requirement, actionable, portrait img, landscape img)
A = M+"agenda-variants/"; H = M+"home-face/"
E = [
 ("Clock","Clock","Just the time, very large.","Nothing needed",0,M+"face-clock.png",None),
 ("Clock","Clock + date","The time, the date and the battery.","Nothing needed",0,M+"face-clock-date.png",None),
 ("Clock","Analog","A round clock face with the date.","Nothing needed",0,M+"face-analog.png",None),
 ("Agenda","Classic","The time and your next three events.","Uses your calendar",0,A+"current-portrait.png",A+"current-landscape.png"),
 ("Agenda","Big clock","A big clock with a short list of what is next.","Uses your calendar",0,A+"A-big-clock-list-portrait.png",A+"A-big-clock-list-landscape.png"),
 ("Agenda","Day rail","Today as an hour-by-hour timeline.","Uses your calendar",0,A+"B-day-rail-portrait.png",A+"B-day-rail-landscape.png"),
 ("Agenda","Next up","The next event in a big card, the rest below.","Uses your calendar",0,A+"C-next-up-hero-portrait.png",A+"C-next-up-hero-landscape.png"),
 ("Agenda","Two days","Today and tomorrow, one under the other.","Uses your calendar",0,A+"D-today-tomorrow-portrait.png",A+"D-today-tomorrow-landscape.png"),
 ("Agenda","Week","A week strip, with today's events listed.","Uses your calendar",0,A+"E-week-strip-portrait.png",A+"E-week-strip-landscape.png"),
 ("Home","Dashboard","Weather, trash day, shopping list and more.","Weather: set location",1,H+"A-dashboard-portrait.png",H+"A-dashboard-landscape.png"),
 ("Home","Calm","A quieter home summary in large type.","Weather: set location",1,H+"B-calm-portrait.png",H+"B-calm-landscape.png"),
 ("Reading","Reading","Your book cover and how far you are.","Uses your last-read book",0,M+"face-reading.png",None),
 ("Photo","Crisp","Your photo in sharp black and white, clock in the corner.","Pick a photo",1,"photo:crisp",None),
 ("Photo","Smooth","Your photo with soft dithered shading.","Pick a photo",1,"photo:smooth",None),
 ("Photo","Plain","Your photo as it is, clock in the corner.","Pick a photo",1,"photo:plain",None),
 ("Picture","Picture","A full-screen picture. No clock, no text.","Pick a photo",1,"photo:picture",None),
 ("Minimal","Minimal","Just the date, very large.","Nothing needed",0,M+"face-minimal.png",None),
 ("Default","Default","Moaan's own picture.","Nothing needed",0,"moaan",None),
]
N = len(E)
def label(i):
    g,n = E[i][0],E[i][1]
    return n if g==n or g in("Clock",) and False else (f"{g} · {n}" if g not in (n,) and g in("Agenda","Home","Photo") else n)
IDX = {label(i):i for i in range(N)}

_photo = None
def photo_parts():
    global _photo
    if _photo: return _photo
    full = Image.open(M+"face-photo.png").convert("L")
    chip = full.crop((416,1234,686,1396))
    p = full.load(); x0_,y0_=300,1100
    # plane through three clean background samples
    gx = (p[680,1100]-p[300,1100])/380.0; gy = (p[300,1420]-p[300,1100])/320.0
    base = full.copy(); q = base.load()
    for y in range(1225,1405):
        for x in range(405,695): q[x,y] = max(0,min(255,int(p[300,1100]+gx*(x-300)+gy*(y-1100))))
    _photo = (full, base, chip); return _photo

def face_img(i, size, land=False):
    """Return an L/RGB image of entry i at exactly size (w,h)."""
    src = E[i][6] if land and E[i][6] else E[i][5]
    w,h = size
    if src=="moaan":
        im = Image.new("RGB",size,"white"); d = ImageDraw.Draw(im)
        f = ImageFont.truetype(HEL, int(min(w,h)*0.2), index=1); d.text((w/2,h/2),"Moaan",font=f,fill="black",anchor="mm"); return im
    if src.startswith("photo:"):
        kind = src[6:]; full,base,chip = photo_parts()
        b = base.resize(size,Image.LANCZOS)
        if kind=="crisp":
            Bm = [[0,32,8,40,2,34,10,42],[48,16,56,24,50,18,58,26],[12,44,4,36,14,46,6,38],[60,28,52,20,62,30,54,22],[3,35,11,43,1,33,9,41],[51,19,59,27,49,17,57,25],[15,47,7,39,13,45,5,37],[63,31,55,23,61,29,53,21]]
            T = Image.new("L",(w,h)); T.putdata([int(Bm[y%8][x%8]/64*255) for y in range(h) for x in range(w)])
            from PIL import ImageChops
            b = ImageChops.subtract(ImageOps.autocontrast(b),T).point(lambda v:255 if v>0 else 0)
        elif kind=="smooth": b = b.convert("1").convert("L")
        if kind!="picture":
            sc = w/720; c = chip.resize((max(1,int(270*sc)),max(1,int(162*sc))),Image.LANCZOS)
            b.paste(c,(int(416*sc),int(1234*sc)))
        return b.convert("RGB")
    im = Image.open(src).convert("RGB")
    assert abs(im.size[0]/im.size[1] - w/h) < 0.01, (src, im.size, size)
    return im.resize(size,Image.LANCZOS)

# ---- drawing helpers (coordinates in 1x px, drawn at S x) ----
class C:
    def __init__(s,W,Hh):
        s.W,s.H = W,Hh; s.im = Image.new("RGB",(W*S,Hh*S),"white"); s.d = ImageDraw.Draw(s.im)
    def rr(s,box,r=14,w=3,fill=None,outline="black"):
        s.d.rounded_rectangle([v*S for v in box],radius=r*S,outline=outline,width=int(w*S),fill=fill)
    def line(s,pts,w=4,fill="black"):
        s.d.line([(x*S,y*S) for x,y in pts],fill=fill,width=int(w*S),joint="curve")
    def text(s,xy,t,px,bold=True,anchor="la",fill="black"):
        s.d.text((xy[0]*S,xy[1]*S),t,font=F(px,bold),fill=fill,anchor=anchor)
    def tw(s,t,px,bold=True): return s.d.textlength(t,font=F(px,bold))/S
    def paste(s,img,xy): s.im.paste(img,(int(xy[0]*S),int(xy[1]*S)))
    def chev(s,cx,cy,sz,dirn,w=6,fill="black"):
        k = -1 if dirn=="l" else 1
        s.line([(cx-k*sz*.45,cy-sz),(cx+k*sz*.45,cy),(cx-k*sz*.45,cy+sz)],w,fill)
    def check(s,cx,cy,sz,w=6,fill="black"):
        s.line([(cx-sz,cy),(cx-sz*.3,cy+sz*.7),(cx+sz,cy-sz*.8)],w,fill)
    def out(s): return s.im.resize((s.W,s.H),Image.LANCZOS)

def frame(c):
    c.rr((16,16,c.W-16,c.H-16),r=28,w=4)

def header(c,x0,x1,y,title,all_btn=True,back=True):
    h = 64
    c.rr((x0,y,x0+h,y+h),r=14,w=4); c.chev(x0+h/2+3,y+h/2,15,"l",w=5)
    c.text((x0+h+20,y+h/2),title,40,anchor="lm")
    if all_btn:
        bw = 132; bx = x1-bw
        c.rr((bx,y,x1,y+h),r=14,w=4)
        # 2x2 grid icon
        gx,gy,g,gap = bx+20,y+h/2-14,12,4
        for a in range(2):
            for b in range(2):
                c.d.rectangle([(gx+a*(g+gap))*S,(gy+b*(g+gap))*S,(gx+a*(g+gap)+g)*S,(gy+b*(g+gap)+g)*S],fill="black")
        c.text((bx+62,y+h/2),"All",34,anchor="lm")
    return y+h

def preview(c,x,y,w,h):
    """frame + true-aspect image placed at x,y with size w,h (border drawn just outside)."""
    c.rr((x-4,y-4,x+w+4,y+h+4),r=3,w=3)

def controls(c,x0,x1,y,i,cur):
    """nav row + use button + requirement. y = top of nav row. Returns bottom y."""
    nb_h, nb_w = 84, 170
    c.rr((x0,y,x0+nb_w,y+nb_h),r=16,w=4); c.chev(x0+nb_w/2-3,y+nb_h/2,19,"l",w=7)
    c.rr((x1-nb_w,y,x1,y+nb_h),r=16,w=4); c.chev(x1-nb_w/2+3,y+nb_h/2,19,"r",w=7)
    c.text(((x0+x1)/2,y+nb_h/2-2),f"{i+1} / {N}",38,anchor="mm")
    y += nb_h+14
    ub_h = 92
    if i==cur:
        c.rr((x0,y,x1,y+ub_h),r=16,w=5)
        tx = (x0+x1)/2; t="In use"; w_ = c.tw(t,38)+58
        c.check(tx-w_/2+20,y+ub_h/2,20,w=7); c.text((tx-w_/2+58,y+ub_h/2),t,38,anchor="lm")
    else:
        c.rr((x0,y,x1,y+ub_h),r=16,w=4,fill="black"); c.text(((x0+x1)/2,y+ub_h/2),"Use this",38,anchor="mm",fill="white")
    y += ub_h+14
    g,n,d,req,act,_,_ = E[i]
    rh = 60
    c.line([(x0,y),(x1,y)],3)
    if act:
        c.text((x0+4,y+rh/2+2),req,28,anchor="lm"); c.chev(x1-14,y+rh/2+2,10,"r",w=5)
        c.line([(x0+4,y+rh/2+2+19),(x0+4+c.tw(req,28),y+rh/2+2+19)],2)
    else:
        c.text((x0+4,y+rh/2+2),req,28,bold=False,anchor="lm")
    return y+rh

def page(i,cur,land=False,port_in_land=False):
    if not land:
        c = C(720,1440); frame(c); x0,x1 = 36,684
        y = header(c,x0,x1,40,"Sleep screen")
        g,n,d,*_ = E[i]; nm = label(i)
        c.text((x0,y+22),nm,44); assert c.tw(nm,44)<=648
        c.text((x0,y+76),d,25,bold=False); assert c.tw(d,25,False)<=648,(d,c.tw(d,25,False))
        top = y+116
        # bottom stack: nav 84 +14 + use 92 +14 + req 60 ; margin to frame 22 ; gap 16
        nav_y = c.H-16-22-(84+14+92+14+60)
        ph = nav_y-18-top; ph -= ph%2; pw = ph//2
        px = (c.W-pw)//2
        preview(c,px,top,pw,ph); c.paste(face_img(i,(pw*S,ph*S)),(px,top))
        controls(c,x0,x1,nav_y,i,cur)
        return c.out(),(pw,ph)
    c = C(1440,720); frame(c)
    rx0,rx1 = 880,1400
    y = header(c,rx0,rx1,40,"Sleep screen")
    g,n,d,*_ = E[i]; nm = label(i)
    if port_in_land:
        ph = 720-16*2-2*26-8; ph-=ph%2; pw=ph//2
        px,py = 40+(840-pw)//2-20,(720-ph)//2
        preview(c,px,py,pw,ph); c.paste(face_img(i,(pw*S,ph*S),False),(px,py))
    else:
        pw = 800; ph = 400; px = 52; py = (720-ph)//2
        preview(c,px,py,pw,ph); c.paste(face_img(i,(pw*S,ph*S),True),(px,py))
    c.text((rx0,y+26),nm,44); assert c.tw(nm,44)<=rx1-rx0
    # wrap desc
    words = d.split(); lines=[""]; 
    for w_ in words:
        t = (lines[-1]+" "+w_).strip()
        if c.tw(t,25,False)<=rx1-rx0: lines[-1]=t
        else: lines.append(w_)
    for k,l in enumerate(lines): c.text((rx0,y+80+k*32),l,25,bold=False)
    nav_y = c.H-16-22-(84+14+92+14+60)
    controls(c,rx0,rx1,nav_y,i,cur)
    return c.out(),(pw,ph)

def listview(cur):
    c = C(720,1440); frame(c); x0,x1 = 36,684
    y = header(c,x0,x1,40,"All screens",all_btn=False)
    c.text((x1,y-32),"Tap one to jump",24,bold=False,anchor="rm") if False else None
    c.text((x0,y+22),"Tap a screen to jump to it. Black frame = in use.",24,bold=False)
    y += 62
    groups = [[("Clock",(0,1,2)),("Home",(9,10))],[("Agenda",(3,4,5,6,7,8))],[("Photo",(12,13,14,15))],[("Other",(11,16,17))]]
    tw_,gap = 96,14; th = 192
    for row in groups:
        gx = x0
        # header text per group, aligned to its first thumb
        for gname,ids in row:
            wdt = len(ids)*tw_+(len(ids)-1)*gap
            c.text((gx,y+2),gname.upper(),22); c.line([(gx,y+34),(gx+wdt,y+34)],3)
            for k,i in enumerate(ids):
                tx = gx+k*(tw_+gap); ty = y+46
                on = (i==cur)
                c.paste(face_img(i,(tw_*S,th*S)),(tx,ty))
                c.rr((tx-3,ty-3,tx+tw_+3,ty+th+3),r=3,w=(7 if on else 2))
                nm = E[i][1]; f=21
                while c.tw(nm,f)>tw_+gap-2: f-=1
                c.text((tx+tw_/2,ty+th+22),nm,f,anchor="mm")
                if on:
                    c.d.ellipse([(tx+tw_-30)*S,(ty+8)*S,(tx+tw_-6)*S,(ty+32)*S],fill="black",outline="white",width=2*S)
                    c.check(tx+tw_-18,ty+20,6,w=3,fill="white")
                    c.text((tx+tw_/2,ty+th+46),"In use",19,anchor="mm")
            gx += wdt+gap*2.2
        y += 46+th+60
    return c.out()

def sheet(frames):
    # frames: list of (title, img)
    pad,gap,lab = 40,36,64
    pw = 460; items=[]
    for t,im in frames:
        w,h = im.size; s = pw/w if w<h else 940/w
        items.append((t,im.resize((int(w*s),int(h*s)),Image.LANCZOS)))
    portraits = [x for x in items if x[1].size[0]<x[1].size[1]]; lands=[x for x in items if x[1].size[0]>=x[1].size[1]]
    W = pad*2+len(portraits)*pw+(len(portraits)-1)*gap
    ph = portraits[0][1].size[1]
    lh = max(x[1].size[1] for x in lands)
    Hh = pad+lab+ph+gap+lab+lh+pad
    sh = Image.new("RGB",(W,Hh),"white"); d = ImageDraw.Draw(sh)
    f = ImageFont.truetype(HEL,26,index=1)
    x = pad
    for t,im in portraits:
        d.text((x,pad+8),t,font=f,fill="black"); sh.paste(im,(x,pad+lab)); d.rectangle((x-2,pad+lab-2,x+im.size[0]+1,pad+lab+im.size[1]+1),outline="black",width=2); x += pw+gap
    x = pad; y = pad+lab+ph+gap
    for t,im in lands:
        d.text((x,y+8),t,font=f,fill="black"); sh.paste(im,(x,y+lab)); d.rectangle((x-2,y+lab-2,x+im.size[0]+1,y+lab+im.size[1]+1),outline="black",width=2); x += im.size[0]+gap
    return sh

if __name__=="__main__":
    cur = IDX["Big clock"] if "Big clock" in IDX else 4
    cur = 4
    spec = {}
    out=[]; info={}
    f1,s1 = page(8,cur);   out.append(("1  Agenda · Week — Use this",f1,"1-agenda-week.png"))
    f2,s2 = page(4,cur);   out.append(("2  Agenda · Big clock — In use",f2,"2-agenda-bigclock-in-use.png"))
    f3,s3 = page(9,cur);   out.append(("3  Home · Dashboard — needs location",f3,"3-home-dashboard.png"))
    f4,s4 = page(12,cur);  out.append(("4  Photo · Crisp",f4,"4-photo-crisp.png"))
    f5 = listview(cur);    out.append(("5  All — compact grid",f5,"5-all-list.png"))
    f6,s6 = page(8,cur,land=True); out.append(("6  Landscape — Agenda · Week",f6,"6-landscape-agenda-week.png"))
    f7,s7 = page(8,cur,land=True,port_in_land=True); out.append(("6b  Landscape, portrait-shaped preview (alt.)",f7,"6b-landscape-portrait-preview-alt.png"))
    for t,im,fn in out:
        im.save(os.path.join(HERE,fn)); print(fn,im.size)
    for n,s_ in (("portrait",s1),("landscape",s6),("land-alt",s7)):
        print(n,"preview",s_,"ratio",s_[0]/s_[1])
    sheet([(t,im) for t,im,_ in out]).save(os.path.join(HERE,"picker-sheet.png"))

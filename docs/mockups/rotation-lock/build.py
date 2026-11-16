import sys; sys.path.insert(0,'.')
from lib import *
RAW='raw/'
LL=Image.open(RAW+'land_left.png').convert('L')
LR=Image.open(RAW+'land_right.png').convert('L')
GEAR_L=LL.crop((814,92,908,186)); CLOSE_L=LL.crop((920,92,1014,186))
GEAR_R=LR.crop((1220,18,1314,112)); CLOSE_R=LR.crop((1326,18,1420,112))
CLEAR_R=LR.crop((1052,18,1208,112))
H=94
def split(x0,total,n,gap):
    w=(total-(n-1)*gap)/n; xs=[];x=x0
    for i in range(n):
        a=round(x0+i*(w+gap)); b=round(x0+i*(w+gap)+w)-1; xs.append((a,b))
    return xs
def radios(img,y0,x0,total,n,gap=8,rot=None):
    """draw Wi-Fi/Bluetooth/Airplane (+ rotation as 4th when rot in ('auto','locked')) ; return info"""
    cells=split(x0,total,n,gap); info=[]
    names=[('Wi-Fi','Off',False),('Bluetooth','Off',False),('Airplane','On',True)]
    for i,(nm,st,on) in enumerate(names):
        a,b=cells[i]; box=(a,y0,b,y0+H-1)
        draw_outlined(img,box,filled=on)
        px=fit_px([nm,st],b-a+1-12)
        center_text(img,box,[nm,st],px,color=255 if on else 0,line_h=px*1.24)
        info.append((nm,b-a+1,round(px/1.95,1)))
    if rot:
        a,b=cells[3]; box=(a,y0,b,y0+H-1)
        px=fit_px(['Locked'],b-a+1-12)
        rot_toggle(img,box,rot=='locked',px,icon=44)
        info.append(('Rotate',b-a+1,round(px/1.95,1)))
    return info,cells
# ---------------- A landscape ----------------
def a_land(rot):
    img=LL.copy(); d=ImageDraw.Draw(img)
    d.rectangle([8,88,1026,190],fill=255)           # clear the row inside the panel
    if rot is None:
        return LL.copy(),None
    info,cells=radios(img,92,16,785,4,rot=rot)
    img.paste(GEAR_L,(814,92)); img.paste(CLOSE_L,(920,92))
    return img,info
# ---------------- portrait scaffolding ----------------
def page_bg(img,x0,x1):
    d=ImageDraw.Draw(img)
    for y in range(120,1380,46):
        d.rectangle([x0+14,y,x1-14-((y//46)%3)*30,y+12],fill=205)
def panel_frame(left):
    img=Image.new('L',(720,1440),255)
    W=561
    big=Image.new('L',(720*SS,1440*SS),255); d=ImageDraw.Draw(big)
    r=35*SS
    if left: x0,x1=-80*SS,W*SS-1
    else: x0,x1=(720-W)*SS,720*SS+80*SS
    d.rounded_rectangle([x0,0,x1,1440*SS-1],r,fill=0)
    s=6*SS
    if left: d.rounded_rectangle([x0,s,x1-s,1440*SS-1-s],r-s,fill=255)
    else: d.rounded_rectangle([x0+s,s,x1,1440*SS-1-s],r-s,fill=255)
    img=big.resize((720,1440),Image.LANCZOS)
    bg=Image.new('L',(720,1440),255)
    if left: page_bg(bg,W,720)
    else: page_bg(bg,0,720-W)
    from PIL import ImageChops
    bgmask=Image.new('L',(720,1440),0); ImageDraw.Draw(bgmask).rectangle([W+8,0,720,1440] if left else [0,0,720-W-8,1440],fill=255)
    img=Image.composite(ImageChops.darker(img,bg),img,bgmask)
    return img
def label(img,xy,s,px,weight=700,color=0):
    ImageDraw.Draw(img).text(xy,s,font=font(px,weight),fill=color,anchor='lm')
def a_port(rot):
    img=panel_frame(True); d=ImageDraw.Draw(img)
    y0=86; n=4 if rot else 3
    # row: 16 .. 555-16 ; gear 94 ; radioRow gets rest minus 12
    right=561-6-16
    gear_x=right-94+1
    total=gear_x-12-16
    info,cells=radios(img,y0,16,total,n,rot=rot)
    img.paste(GEAR_L,(gear_x,y0))
    d.rectangle([6,y0+H+8,554,y0+H+11],fill=0)
    label(img,(26,y0+H+36),'SCREEN MODE',21)
    modes=[('Fast','Black and white, quickest'),('Soft','Black and white, gentler'),('Dither','Fast, dotted grays for pictures'),('Text','Crisp text, fewer flashes'),('Quality','All 16 grays, cleanest'),('Auto','The screen picks for each update')]
    y=y0+H+58
    for i,(t,s) in enumerate(modes):
        on=(t=='Text'); box=(14,y,540,y+100)
        draw_outlined(img,box,filled=on,stroke=4,radius=23)
        c=255 if on else 0
        label(img,(40,y+34),t,29,color=c); label(img,(40,y+72),s,23,weight=400,color=c)
        y+=106
    # chips + actions + light (simplified context)
    draw_outlined(img,(14,y+6,268,y+64),filled=True,radius=12); label(img,(36,y+36),'Use for KOReader',22,color=255)
    draw_outlined(img,(280,y+6,540,y+64),radius=12); label(img,(300,y+36),'Use everywhere',22)
    label(img,(26,y+90),'Text · KOReader’s own',21,weight=400)
    yy=y+124; d.rectangle([6,yy,554,yy+3],fill=0)
    draw_outlined(img,(14,yy+14,200,yy+104),radius=12); label(img,(34,yy+60),'Full refresh',23)
    draw_outlined(img,(212,yy+14,300,yy+104),radius=12); label(img,(240,yy+58),'−',34)
    center_text(img,(300,yy+14,450,yy+104),['Contrast','+5'],21,line_h=26)
    draw_outlined(img,(450,yy+14,540,yy+104),radius=12); label(img,(480,yy+58),'+',34)
    yy+=124; d.rectangle([6,yy,554,yy+3],fill=0)
    for k,(t) in enumerate(['Brightness 100%','Warmth 30%']):
        by=yy+12+k*98
        draw_outlined(img,(14,by,104,by+88),radius=12); label(img,(46,by+42),'−',34)
        label(img,(150,by+32),t,23); d.rectangle([124,by+62,430,by+66],fill=0)
        draw_outlined(img,(450,by+0,540,by+88),radius=12); label(img,(480,by+42),'+',34)
    return img,info
def b_land(rot,where):
    img=LR.copy(); d=ImageDraw.Draw(img)
    if rot is None: return img,None
    if where=='header':
        a=851; w=190; px=fit_px(['Locked'],w-12)
        rot_toggle(img,(a,18,a+w-1,18+H-1),rot=='locked',px)
        # shift nothing else: it sits in the free gap before Clear all
        return img,[('Rotate',w,round(px/1.95,1))]
    # footer: toggle + two buttons share 427..1423
    d.rectangle([420,600,1428,702],fill=255)
    tw=140; gap=12; x0=427; total=1423-427+1
    bw=(total-tw-2*gap)//2
    px=fit_px(['Locked'],tw-12)
    rot_toggle(img,(x0,605,x0+tw-1,697),rot=='locked',px)
    bx=x0+tw+gap
    for i,t in enumerate(['Screen & light','Android recents']):
        a=bx+i*(bw+gap); b=a+bw-1; draw_outlined(img,(a,605,b,697),radius=12)
        p=fit_px([t],bw-24,lo=19.5,hi=27.3); center_text(img,(a,605,b,697),[t],p)
    return img,[('Rotate',tw,round(px/1.95,1))]
def b_port(rot):
    img=panel_frame(False); d=ImageDraw.Draw(img)
    X0=159+6  # content left
    # header
    hy=26
    label(img,(X0+21,hy+46),'Recents',37)
    cx=720-19-94
    img.paste(CLOSE_R,(cx,hy)); img.paste(GEAR_R,(cx-12-94,hy)); img.paste(CLEAR_R,(cx-12-94-12-156,hy))
    # rows
    apps=[('Files','48 min ago','Previous',True),('KOReader','Open now','Open now',False),('Aurora Store','27 min ago','',True)]
    y=hy+H+16
    for nm,sub,chip,x in apps:
        box=(X0+16,y,704,y+116)
        draw_outlined(img,box,filled=False,stroke=4,radius=23)
        draw_outlined(img,(X0+40,y+30,X0+96,y+86),filled=True,radius=10)
        label(img,(X0+120,y+40),nm,31); label(img,(X0+120,y+80),sub,23,weight=400)
        if x: draw_outlined(img,(598,y+14,684,y+100),radius=12); label(img,(630,y+54),'×',34)
        y+=124
    label(img,(X0+30,y+30),'1 / 9',23)
    # footer
    fy=1326; fx0=X0+16; fx1=703; total=fx1-fx0+1
    names=['Screen & light','Android recents']
    if rot is None:
        bw=(total-12)//2
        for i,t in enumerate(names):
            a=fx0+i*(bw+12); b=a+bw-1; draw_outlined(img,(a,fy,b,fy+H-1),radius=12)
            center_text(img,(a,fy,b,fy+H-1),[t],fit_px([t],bw-24,lo=19.5,hi=27.3))
        return img,None
    tw=100; bw=(total-tw-24)//2
    px=fit_px(['Locked'],tw-12)
    rot_toggle(img,(fx0,fy,fx0+tw-1,fy+H-1),rot=='locked',px)
    ps=[]
    for i,t in enumerate(names):
        a=fx0+tw+12+i*(bw+12); b=a+bw-1; draw_outlined(img,(a,fy,b,fy+H-1),radius=12)
        p=fit_px([t],bw-24,lo=19.5,hi=27.3); ps.append(round(p/1.95,1)); center_text(img,(a,fy,b,fy+H-1),[t],p)
    return img,[('Rotate',tw,round(px/1.95,1)),('footer labels sp',bw,ps)]
if __name__=='__main__':
    out={}
    for name,fn,args in [
        ('A_land_cur',a_land,(None,)),('A_land_auto',a_land,('auto',)),('A_land_locked',a_land,('locked',)),
        ('A_port_cur',a_port,(None,)),('A_port_auto',a_port,('auto',)),('A_port_locked',a_port,('locked',)),
        ('B_land_cur',b_land,(None,'')),('B_land_hdr_auto',b_land,('auto','header')),('B_land_ftr_locked',b_land,('locked','footer')),('B_land_ftr_auto',b_land,('auto','footer')),('B_land_hdr_locked',b_land,('locked','header')),
        ('B_port_cur',b_port,(None,)),('B_port_auto',b_port,('auto',)),('B_port_locked',b_port,('locked',))]:
        r=fn(*args); img=r[0] if isinstance(r,tuple) else r
        info=r[1] if isinstance(r,tuple) else None
        img.save('tmp/%s.png'%name); print(name,img.size,info)

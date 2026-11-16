from PIL import Image, ImageDraw, ImageFont
import math
FONT='tmp/GoogleSansFlex-Regular.ttf'
SS=4
def font(px, weight=700):
    f=ImageFont.truetype(FONT, int(round(px)))
    f.set_variation_by_axes([18,100,weight,0,0,0]); return f
def text_w(s,px,weight=700):
    return font(px,weight).getlength(s)
def fit_px(lines, avail, lo=17.55, hi=23.4, step=1.95):
    # autosize 9..12sp (step 1sp = 1.95px): largest size where every line fits
    px=hi
    while px>lo-0.01:
        if all(text_w(l,px)<=avail for l in lines): return px
        px-=step
    return lo
def rrect_tile(w,h,fill,stroke=4,radius=12,ink=0,paper=255):
    t=Image.new('L',(w*SS,h*SS),paper if False else 255)
    d=ImageDraw.Draw(t)
    d.rounded_rectangle([0,0,w*SS-1,h*SS-1],radius*SS,fill=ink,)
    inner=paper if fill=='paper' else ink
    d.rounded_rectangle([stroke*SS,stroke*SS,w*SS-1-stroke*SS,h*SS-1-stroke*SS],max(1,(radius-stroke)*SS),fill=inner)
    return t
def draw_outlined(img, box, filled=False, stroke=4, radius=12):
    x0,y0,x1,y1=box; w,h=x1-x0+1,y1-y0+1
    big=Image.new('L',(w*SS,h*SS),255); d=ImageDraw.Draw(big)
    d.rounded_rectangle([0,0,w*SS-1,h*SS-1],radius*SS,fill=0)
    if not filled:
        d.rounded_rectangle([stroke*SS,stroke*SS,w*SS-1-stroke*SS,h*SS-1-stroke*SS],max(1,(radius-stroke)*SS),fill=255)
    img.paste(big.resize((w,h),Image.LANCZOS).convert(img.mode),(x0,y0))
def center_text(img,box,lines,px,color=0,weight=700,line_h=None,dy=0):
    d=ImageDraw.Draw(img); f=font(px,weight)
    x0,y0,x1,y1=box; lh=line_h or px*1.25
    tot=lh*len(lines); y=(y0+y1+1)/2-tot/2+dy
    for l in lines:
        w=f.getlength(l); d.text(((x0+x1+1)/2-w/2, y+lh/2),l,font=f,fill=color,anchor='lm'); y+=lh
def icon_rotate(size,color):
    """two-arrow rotate glyph, returns RGBA-ish L mask tile (size x size) drawn supersampled"""
    s=size*8; t=Image.new('L',(s,s),0); d=ImageDraw.Draw(t)
    c=s/2; r=s*0.34; wd=int(s*0.095)
    # arc 1: from 200deg to 340deg... draw two 140deg arcs with arrowheads
    for a0 in (200,20):
        d.arc([c-r,c-r,c+r,c+r],a0+10,a0+150,fill=255,width=wd)
        ang=math.radians(a0+150)  # arrow head at end (clockwise in screen coords)
        ex,ey=c+r*math.cos(ang),c+r*math.sin(ang)
        tx,ty=-math.sin(ang),math.cos(ang) # tangent (direction of increasing angle)
        nx,ny=math.cos(ang),math.sin(ang)
        L=s*0.20;W=s*0.15
        p=[(ex+tx*L,ey+ty*L),(ex+nx*W,ey+ny*W),(ex-nx*W,ey-ny*W)]
        d.polygon(p,fill=255)
    return t.resize((size,size),Image.LANCZOS)
def icon_lock(size):
    s=size*8; t=Image.new('L',(s,s),0); d=ImageDraw.Draw(t)
    bw=s*0.74; bh=s*0.50; bx=(s-bw)/2; by=s*0.44
    # shackle
    sw=int(s*0.10); sr=s*0.23; cx=s/2
    d.arc([cx-sr,by-sr*1.25,cx+sr,by+sr*0.75],180,360,fill=255,width=sw)
    d.rectangle([cx-sr,by-sr*0.25,cx-sr+sw,by+2],fill=255); d.rectangle([cx+sr-sw,by-sr*0.25,cx+sr,by+2],fill=255)
    d.rounded_rectangle([bx,by,bx+bw,by+bh],s*0.07,fill=255)
    # rotate arc cut-out on body (small circular arrow in negative)
    c=(cx,by+bh/2); r=bh*0.28; w2=int(s*0.07)
    d.arc([c[0]-r,c[1]-r,c[0]+r,c[1]+r],30,300,fill=0,width=w2)
    ang=math.radians(300); ex,ey=c[0]+r*math.cos(ang),c[1]+r*math.sin(ang)
    tx,ty=-math.sin(ang),math.cos(ang); nx,ny=math.cos(ang),math.sin(ang); L=s*0.13;W=s*0.095
    d.polygon([(ex+tx*L,ey+ty*L),(ex+nx*W,ey+ny*W),(ex-nx*W,ey-ny*W)],fill=0)
    return t.resize((size,size),Image.LANCZOS)
def paste_icon(img, mask, xy, color):
    col=Image.new('L',mask.size,color); img.paste(col,xy,mask)
def rot_toggle(img, box, locked, px, stroke=4, radius=12, icon=44):
    draw_outlined(img,box,filled=locked,stroke=stroke,radius=radius)
    x0,y0,x1,y1=box; fg=255 if locked else 0
    lab='Locked' if locked else 'Auto'
    h=y1-y0+1; gap=3
    tot=icon+gap+px*1.0
    top=y0+(h-tot)/2
    m=icon_lock(icon) if locked else icon_rotate(icon,fg)
    paste_icon(img,m,(int((x0+x1+1)/2-icon/2),int(top)),fg)
    center_text(img,(x0,int(top+icon+gap),x1,int(top+icon+gap+px*1.15)),[lab],px,color=fg)

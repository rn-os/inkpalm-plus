import sys; sys.path.insert(0,'.')
from lib import *
T=lambda n: Image.open('tmp/%s.png'%n).convert('L')
F=lambda px,w=700: font(px,w)
def sc(im,f): return im.resize((int(im.width*f),int(im.height*f)),Image.LANCZOS)
def frame(im,f=0.5):
    s=sc(im,f); o=Image.new('L',(s.width+4,s.height+4),0); o.paste(s,(2,2)); return o
def cap(d,xy,s,px=26,w=400): d.text(xy,s,font=F(px,w),fill=0,anchor='la')
def sheet():
    W=2000; H=3000
    S=Image.new('L',(W,H),255); d=ImageDraw.Draw(S)
    cap(d,(30,24),'Rotation lock toggle  -  current (left) vs proposed (right)',40,700)
    cap(d,(30,76),'720x1440 / 1440x720, density 312. Landscape panels are real screenshots with the row redrawn; portrait panels are rebuilt from the same measurements (portrait capture was blank on the device).',22,400)
    y=130
    def band(title,sub,port,land,y):
        cap(d,(30,y),title,32,700); cap(d,(30,y+42),sub,22,400); y+=84
        x=30
        for lab,n in port:
            cap(d,(x,y),lab,24,700); im=frame(T(n)); S.paste(im,(x,y+34)); x+=im.width+16
        lx=x+20; ly=y
        for lab,n in land:
            cap(d,(lx,ly),lab,24,700); im=frame(T(n)); S.paste(im,(lx,ly+34)); ly+=im.height+34+14
        return max(y+34+722+30, ly+20)
    y=band('A  Left panel, Screen & light: 4th toggle in the top row',
        'Equal-width toggle, icon over a one-word state label. Tap: Auto <-> Locked (keeps the current orientation). Long press (not shown on device): flip locked screen portrait <-> landscape.',
        [('Portrait - current','A_port_cur'),('Portrait - Auto','A_port_auto'),('Portrait - Locked','A_port_locked')],
        [('Landscape - current','A_land_cur'),('Landscape - Auto','A_land_auto'),('Landscape - Locked','A_land_locked')],y)
    y=band('B  Right panel, Recents: toggle in free space',
        'Landscape: free gap in the header (left of Clear all) or a footer slot. Portrait: header has no room (title already touches Clear all), so the footer slot is used.',
        [('Portrait - current','B_port_cur'),('Portrait footer - Auto','B_port_auto'),('Portrait footer - Locked','B_port_locked')],
        [('Landscape - current','B_land_cur'),('Landscape header slot - Auto','B_land_hdr_auto'),('Landscape footer slot - Locked','B_land_ftr_locked')],y)
    return S.crop((0,0,W,y))
sheet().save('rotation-sheet.png')
def crop(n,box): return T(n).crop(box)
items=[
 ('A landscape, current (row)', 'A_land_cur',(0,80,1040,200)),
 ('A landscape, Auto',          'A_land_auto',(0,80,1040,200)),
 ('A landscape, Locked',        'A_land_locked',(0,80,1040,200)),
 ('A portrait, current (row)',  'A_port_cur',(0,72,570,196)),
 ('A portrait, Auto',           'A_port_auto',(0,72,570,196)),
 ('A portrait, Locked',         'A_port_locked',(0,72,570,196)),
 ('B landscape header slot, Auto','B_land_hdr_auto',(406,6,1440,126)),
 ('B landscape header slot, Locked','B_land_hdr_locked',(406,6,1440,126)),
 ('B landscape footer slot, Auto','B_land_ftr_auto',(406,590,1440,720)),
 ('B landscape footer slot, Locked','B_land_ftr_locked',(406,590,1440,720)),
 ('B portrait footer, Auto','B_port_auto',(150,1310,720,1440)),
 ('B portrait footer, Locked','B_port_locked',(150,1310,720,1440)),
]
Wd=1080; y=20; parts=[]
for t,n,b in items:
    c=crop(n,b); parts.append((t,c)); y+=c.height+52
Z=Image.new('L',(Wd,y+10),255); d=ImageDraw.Draw(Z); y=20
for t,c in parts:
    cap(d,(20,y),t+'   (1:1)',22,700); Z.paste(c,(20,y+28)); y+=c.height+52
Z.save('rotation-zoom.png'); print(Z.size)

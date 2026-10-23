"""Four clicks in TL,TR,BR,BL order; saves a normalized quad beside an owner photo.
Needs opencv-python (GUI variant). Real photos and labels belong in bench-private/.
"""
import argparse,json
from pathlib import Path
import cv2,numpy as np
p=argparse.ArgumentParser();p.add_argument('image',type=Path);a=p.parse_args()
image=cv2.imread(str(a.image)); h,w=image.shape[:2];scale=min(1.,1400/max(w,h));view=cv2.resize(image,None,fx=scale,fy=scale);points=[]
def click(event,x,y,flags,data):
    if event==cv2.EVENT_LBUTTONDOWN and len(points)<4: points.append([x/(w*scale),y/(h*scale)])
cv2.namedWindow('TL TR BR BL - Enter saves, R resets');cv2.setMouseCallback('TL TR BR BL - Enter saves, R resets',click)
while True:
    frame=view.copy()
    for x,y in points: cv2.circle(frame,(int(x*w*scale),int(y*h*scale)),5,(0,255,0),-1)
    cv2.imshow('TL TR BR BL - Enter saves, R resets',frame);key=cv2.waitKey(40)
    if key==27: break
    if key in [ord('r'),ord('R')]: points=[]
    if key in [10,13] and len(points)==4:
        a.image.with_suffix('.json').write_text(json.dumps({'file':a.image.name,'synthetic':False,'scene':'owner-photo','quad':points,'ocr':None},indent=2));break
cv2.destroyAllWindows()

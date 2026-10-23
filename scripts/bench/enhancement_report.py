from pathlib import Path
import json
import cv2,numpy as np
from PIL import Image,ImageDraw
from generate_fixtures import make_page,ROOT
from baseline_v020 import enhance as old
from enhance_v2 import enhance

p=json.loads((ROOT/'app/src/main/assets/scan_params.json').read_text());out=ROOT/'docs/quality/enhancement-v030';out.mkdir(parents=True,exist_ok=True)
original,_=make_page(table=True);h,w=original.shape[:2];metrics=[];sheets=[]
seal=(original[:,:,2]>120)&(original[:,:,2]>original[:,:,1]*1.6)&(original[:,:,2]>original[:,:,0]*1.6)
black=np.zeros((h,w),bool);black[600:650,90:630]=True
black &= np.max(original,axis=2)<60
paper=np.min(original,axis=2)>220
for scene in ['uniform','shadow','warm-cast']:
    image=original.copy()
    if scene=='shadow':image=(image*np.linspace(.42,1,w)[None,:,None]).astype(np.uint8)
    if scene=='warm-cast':image=np.clip(image*np.array([.72,.88,1.]),0,255).astype(np.uint8)
    outputs=[image,old(image),enhance(image,'AUTO',p),enhance(image,'CLEAN',p),enhance(image,'BW',p)]
    names=['Original','v0.2 AUTO','v0.3 AUTO','v0.3 Gray','v0.3 B&W']
    sheet=Image.new('RGB',(1500,460),'white');draw=ImageDraw.Draw(sheet)
    for i,(name,output) in enumerate(zip(names,outputs)):
        rgb=cv2.cvtColor(output,cv2.COLOR_BGR2RGB if output.ndim==3 else cv2.COLOR_GRAY2RGB)
        thumb=Image.fromarray(rgb);thumb.thumbnail((295,420));sheet.paste(thumb,(i*300,28));draw.text((i*300+3,5),scene+' '+name,fill='black')
    sheet.save(out/(scene+'.jpg'),quality=95)
    for name,result in zip(names[1:],outputs[1:]):
        color=result if result.ndim==3 else cv2.cvtColor(result,cv2.COLOR_GRAY2BGR)
        red=(color[:,:,2]>120)&(color[:,:,2]>color[:,:,1]*1.6)&(color[:,:,2]>color[:,:,0]*1.6)
        gray=cv2.cvtColor(color,cv2.COLOR_BGR2GRAY)
        metrics.append({'scene':scene,'filter':name,'seal_red_fraction':float(np.mean(red[seal])),'dark_fill_fraction_under_90':float(np.mean(gray[black]<90)),'paper_std':float(np.std(gray[paper])),'paper_mean':float(np.mean(gray[paper]))})
(out/'metrics.json').write_text(json.dumps(metrics,indent=2));print(json.dumps(metrics,indent=2))

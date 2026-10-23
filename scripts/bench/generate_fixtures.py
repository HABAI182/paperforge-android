"""Deterministic, non-private scanner fixtures. These are synthetic, not phone photos."""
from pathlib import Path
import argparse,json,hashlib,shutil
import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
def make_page(ocr=False,table=False):
    image=Image.new('RGB',(1000,1400),(244,243,235)); d=ImageDraw.Draw(image)
    font=lambda n:ImageFont.truetype(str(ROOT/'app/src/main/assets/fonts/PaperForgeCJK-Regular.ttf'),n)
    lines=['纸匠文档质量实验','中文与 English 0123456789']
    lines += [f'清晰讲义·第{i}行' for i in range(1,14)] if ocr else ['保留原稿，准确识别，离线处理。','PaperForge keeps your document local.']*7
    d.text((80,80),lines[0],font=font(50),fill=(20,20,20)); d.text((80,165),lines[1],font=font(39),fill=(25,25,25))
    for n,line in enumerate(lines[2:]): d.text((80,280+n*69),line,font=font(35),fill=(28,28,28))
    if not ocr:
        d.ellipse((715,940,930,1155),outline=(190,25,35),width=12)
        d.text((752,1010),'资料',font=font(46),fill=(190,25,35))
        d.line((690,1210,925,1160),fill=(35,70,205),width=8)
        d.rectangle((65,590,650,660),fill=(25,25,25)); d.text((82,600),'粗体标题 / DARK FILL',font=font(31),fill='white')
        if table:
            for y in range(710,1010,60): d.line((60,y,660,y),fill=(25,25,25),width=3)
            for x in range(60,700,120): d.line((x,710,x,950),fill=(25,25,25),width=3)
    return np.array(image)[:,:,::-1].copy(),'\n'.join(lines)

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=ROOT/'bench-private/synthetic-v1');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    cases=[]; rng=np.random.default_rng(20261023); w,h=1600,1200
    scenes=['dark-desk','white-desk','wood','clutter','shadow','glare','fills-frame','similar-luminance-color']
    for scene in scenes:
        for variant in range(8):
            bg=np.full((h,w,3),55 if scene=='dark-desk' else 225,np.uint8)
            if scene=='wood':
                yy,xx=np.mgrid[:h,:w]; grain=(12*np.sin(xx/18+np.sin(yy/150))+5*rng.normal(size=(h,w)))
                bg=np.clip(np.array([92,135,166])+grain[:,:,None],0,255).astype(np.uint8)
            if scene=='clutter':
                cv2.rectangle(bg,(40,35),(1550,1160),(135,145,150),-1)
                for _ in range(15):
                    x,y=rng.integers(0,w),rng.integers(0,h); cv2.line(bg,(int(x),int(y)),(int(x+150),int(y+80)),(50,50,50),4)
            if scene=='similar-luminance-color': bg[:]=(170,153,150)
            page,_=make_page(table=variant%2==0)
            if scene=='similar-luminance-color': page=(page.astype(float)*np.array([.60,.73,.60])).astype(np.uint8)
            if scene=='shadow':
                field=np.linspace(.42,1.,page.shape[1])[None,:,None]; page=np.clip(page*field,0,255).astype(np.uint8)
            if scene=='glare': cv2.ellipse(page,(720,350),(200,160),-20,0,360,(255,255,255),-1)
            offset=(variant-3.5)*8
            if scene=='fills-frame': q=np.array([[.006,.018],[.982,.006],[.996,.982],[.018,.995]],np.float32)
            else: q=np.array([[.26+offset/w,.08],[.76,.12+offset/h],[.84,.91],[.18,.96-offset/h]],np.float32)
            H=cv2.getPerspectiveTransform(np.float32([[0,0],[999,0],[999,1399],[0,1399]]),(q*[w-1,h-1]).astype(np.float32))
            warp=cv2.warpPerspective(page,H,(w,h),flags=cv2.INTER_LINEAR); mask=cv2.warpPerspective(np.full(page.shape[:2],255,np.uint8),H,(w,h))
            bg[mask>127]=warp[mask>127]
            if variant in [5,6]: bg=cv2.GaussianBlur(bg,(5,5),1.0)
            if variant==7: bg=np.clip(bg.astype(float)*.55+rng.normal(0,2,bg.shape),0,255).astype(np.uint8)
            name=f'{scene}-{variant}.jpg'; cv2.imwrite(str(a.output/name),bg,[cv2.IMWRITE_JPEG_QUALITY,95])
            cases.append({'file':name,'scene':scene,'synthetic':True,'quad':q.tolist(),'category':'document','ocr':None})
    for i in range(8):
        bg=np.full((h,w,3),[40,90,160,225][i%4],np.uint8)
        if i>=4:
            yy,xx=np.mgrid[:h,:w]; noise=(8*np.sin(xx/12)+3*rng.normal(size=(h,w)))
            bg=np.clip(bg.astype(float)+noise[:,:,None],0,255).astype(np.uint8)
        name=f'negative-{i}.jpg';cv2.imwrite(str(a.output/name),bg)
        cases.append({'file':name,'scene':'no-document','synthetic':True,'quad':None,'ocr':None,'category':'negative'})
    for i in range(8):
        page,gt=make_page(ocr=True); angle=[0,0,0,0,90,180,270,0][i]
        if i==1: page=np.clip(page.astype(float)*np.linspace(.5,1,1000)[None,:,None],0,255).astype(np.uint8)
        if i==2: page=cv2.resize(page,(650,910),interpolation=cv2.INTER_AREA)
        if i==3: page=cv2.GaussianBlur(page,(3,3),.6)
        if angle: page=cv2.rotate(page,{90:cv2.ROTATE_90_CLOCKWISE,180:cv2.ROTATE_180,270:cv2.ROTATE_90_COUNTERCLOCKWISE}[angle])
        name=f'ocr-{i}.png';cv2.imwrite(str(a.output/name),page)
        cases.append({'file':name,'scene':'ocr-mixed','synthetic':True,'quad':[[0,0],[1,0],[1,1],[0,1]],'ocr':gt,'rotation':angle,'category':'text'})
    old=ROOT/'docs/acceptance/v020/samples/acceptance-020-low-contrast.jpg'
    name='regression-v020.jpg';shutil.copyfile(old,a.output/name)
    gt='纸匠自动扫描与离线识别\n中文识别测试 2026\nPAPERFORGE OCR 123456\n'+'\n'.join(f'清晰讲义·第{i}行' for i in range(1,14))
    cases.append({'file':name,'scene':'ocr-regression','synthetic':True,'quad':(np.array([[150,200],[1620,120],[1720,2160],[210,2290]])/[1800,2400]).tolist(),'ocr':gt,'category':'text'})
    # Resolution probes remain synthetic; only one is used for PDF export in the device suite.
    page,gt=make_page(ocr=True); hi=cv2.resize(page,(3024,4032),interpolation=cv2.INTER_CUBIC)
    cv2.imwrite(str(a.output/'resolution-12mp.jpg'),hi,[cv2.IMWRITE_JPEG_QUALITY,96])
    cases.append({'file':'resolution-12mp.jpg','scene':'resolution-12mp','synthetic':True,'quad':[[.01,.01],[.99,.01],[.99,.99],[.01,.99]],'ocr':None,'category':'text'})
    for c in cases: c['sha256']=hashlib.sha256((a.output/c['file']).read_bytes()).hexdigest()
    manifest={'schema':1,'name':'synthetic-v1','seed':20261023,'synthetic':True,'cases':cases}
    (a.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'{len(cases)} synthetic cases: {a.output}')
if __name__=='__main__': main()

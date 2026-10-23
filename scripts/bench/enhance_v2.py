"""Independent color-preserving tile flat-field and resolution-scaled Sauvola."""
import cv2,numpy as np

def enhance(image,mode='AUTO',params=None):
    p=params or {}
    if mode=='ORIGINAL':return image.copy()
    h,w=image.shape[:2];ratio=min(1,p.get('illuminationEdge',480)/max(h,w))
    small=cv2.resize(image,None,fx=ratio,fy=ratio,interpolation=cv2.INTER_AREA)
    sh,sw=small.shape[:2];cols=max(3,round(sw/32));rows=max(3,round(sh/32))
    tiles=np.zeros((rows,cols,3),np.uint8)
    for y in range(rows):
        for x in range(cols):
            tile=small[y*sh//rows:(y+1)*sh//rows,x*sw//cols:(x+1)*sw//cols]
            tiles[y,x]=np.percentile(tile,p.get('illuminationTilePercentile',.88)*100,axis=(0,1))
    # A dark title/photo is not an illumination source. Fill it from neighboring paper tiles.
    luma=cv2.cvtColor(tiles,cv2.COLOR_BGR2GRAY);ceiling=np.percentile(luma,90)
    dark=luma<max(35,ceiling*.45)
    if dark.any() and not dark.all():tiles=cv2.inpaint(tiles,dark.astype(np.uint8)*255,3,cv2.INPAINT_TELEA)
    tiles=cv2.GaussianBlur(tiles,(3,3),0)
    field=cv2.resize(tiles,(w,h),interpolation=cv2.INTER_LINEAR)
    strength=1. if mode=='WHITEBOARD' else p.get('illuminationStrength',.85)
    gain=np.clip(250/np.maximum(field.astype(np.float32),70),.85,3.)
    corrected=np.clip(image.astype(np.float32)*(1+(gain-1)*strength),0,255).astype(np.uint8)
    if mode in ['AUTO','COLOR','WHITEBOARD']:return corrected
    gray=cv2.cvtColor(corrected,cv2.COLOR_BGR2GRAY)
    if mode!='BW':return gray
    window=max(15,round(min(w,h)*p.get('sauvolaWindowFraction',.05)))|1
    f=gray.astype(np.float32);mean=cv2.boxFilter(f,-1,(window,window),borderType=cv2.BORDER_REPLICATE)
    square=cv2.boxFilter(f*f,-1,(window,window),borderType=cv2.BORDER_REPLICATE)
    std=np.sqrt(np.maximum(0,square-mean*mean));threshold=mean*(1+p.get('sauvolaK',.22)*(std/128-1))
    threshold=np.maximum(threshold,min(65,float(np.percentile(gray,85))*.22))
    return np.where(f>threshold,255,0).astype(np.uint8)

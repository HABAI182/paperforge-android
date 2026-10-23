"""Frozen Python mirror of ScanProcessor.detectMat at 4d56d55; no new tuning."""
import cv2
import numpy as np

def ordered(p):
    c=p.mean(axis=0)
    p=p[np.argsort(np.arctan2(p[:,1]-c[1],p[:,0]-c[0]))]
    return np.roll(p,-np.argmin(p.sum(axis=1)),axis=0)

def valid(q):
    return cv2.isContourConvex(q.astype(np.float32)) and abs(cv2.contourArea(q))>.015

def detect(image,params=None):
    h,w=image.shape[:2]; scale=min(1.,900/max(h,w))
    small=cv2.resize(image,None,fx=scale,fy=scale)
    gray=cv2.cvtColor(small,cv2.COLOR_BGR2GRAY); blur=cv2.GaussianBlur(gray,(5,5),0)
    kernel=np.ones((5,5),np.uint8); candidates=[]; sh,sw=gray.shape
    def collect(mask):
        contours,_=cv2.findContours(mask,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
        contours=sorted((c for c in contours if cv2.contourArea(c)>sw*sh*.07),key=cv2.contourArea,reverse=True)[:30]
        for c in contours:
            for epsilon in [.012,.022,.035,.05]:
                p=cv2.approxPolyDP(c,epsilon*cv2.arcLength(c,True),True)
                if len(p)!=4: continue
                q=ordered(p.reshape(4,2).astype(np.float32)/[sw-1,sh-1]).astype(np.float32)
                area=abs(cv2.contourArea(q))
                if not valid(q) or not .08<=area<=.995: continue
                if sum(x<.008 or x>.992 or y<.008 or y>.992 for x,y in q)>=3: continue
                sides=np.linalg.norm((q-np.roll(q,-1,axis=0))*[sw,sh],axis=1)
                if min(sides)<min(sw,sh)*.12: continue
                rectangularity=np.clip(cv2.contourArea(c)/(area*sw*sh),0,1)
                candidates.append((area*(.7+.3*rectangularity),q.tolist())); break
    edges=cv2.morphologyEx(cv2.Canny(blur,28,92),cv2.MORPH_CLOSE,kernel); collect(edges)
    _,binary=cv2.threshold(blur,0,255,cv2.THRESH_BINARY+cv2.THRESH_OTSU)
    binary=cv2.morphologyEx(binary,cv2.MORPH_CLOSE,kernel); collect(binary); collect(cv2.bitwise_not(binary))
    return max(candidates,key=lambda c:c[0])[1] if candidates else None

def enhance(image,mode='AUTO',params=None):
    gray=cv2.cvtColor(image,cv2.COLOR_BGR2GRAY)
    if mode=='ORIGINAL': return image.copy()
    if mode=='GRAY': return gray
    if mode=='COLOR': return np.clip(image.astype(float)*1.12+6,0,255).astype(np.uint8)
    if mode=='BW': return cv2.adaptiveThreshold(gray,255,cv2.ADAPTIVE_THRESH_GAUSSIAN_C,cv2.THRESH_BINARY,41,13)
    size=max(21,min(81,min(gray.shape)//25)); size+=1-size%2
    bg=cv2.dilate(cv2.medianBlur(gray,5),np.ones((size,size),np.uint8)); bg=cv2.GaussianBlur(bg,(51,51),0)
    norm=cv2.divide(gray,bg,scale=255)
    return cv2.addWeighted(norm,1.2,cv2.GaussianBlur(norm,(3,3),0),-.2,0)

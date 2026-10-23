"""Pure benchmark metrics. Coordinates use the EXIF-corrected normalized image."""
import numpy as np
import cv2

def quad_metrics(predicted, truth, width, height):
    if truth is None:
        return {"iou": None, "corner_error_pct": None, "failed": predicted is not None}
    if predicted is None:
        return {"iou": 0., "corner_error_pct": 100., "failed": True}
    a=np.array(predicted,np.float32)*[width,height]
    b=np.array(truth,np.float32)*[width,height]
    a=a.astype(np.float32); b=b.astype(np.float32)
    area_a=abs(cv2.contourArea(a)); area_b=abs(cv2.contourArea(b))
    intersection,_=cv2.intersectConvexConvex(a,b)
    iou=float(intersection/max(1.,area_a+area_b-intersection))
    error=float(np.linalg.norm(a-b,axis=1).mean()/max(width,height)*100)
    return {"iou":iou,"corner_error_pct":error,"failed":iou<.9}

def edit_distance(a,b):
    previous=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        current=[i]
        for j,y in enumerate(b,1):
            current.append(min(current[-1]+1,previous[j]+1,previous[j-1]+(x!=y)))
        previous=current
    return previous[-1]

def normalized_text(text):
    import unicodedata
    return ''.join(unicodedata.normalize('NFKC',text).split())

def cer(actual,expected):
    a=normalized_text(actual); e=normalized_text(expected)
    return edit_distance(a,e)/max(1,len(e))

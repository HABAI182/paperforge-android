"""Private same-photo scan comparison; no images are sent to a service.
Reference crops exclude app chrome. SIFT/RANSAC aligns content before visual metrics;
these are screenshot comparisons, not OCR accuracy or ground-truth corner labels.
"""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageOps


def load(path, width, crop=None):
    im = Image.open(path).convert('RGB')
    if crop:
        im = im.crop(crop)
    im.thumbnail((width, width * 3))
    return cv2.cvtColor(np.asarray(im), cv2.COLOR_RGB2GRAY)


def line_metric(gray):
    h,w = gray.shape
    ink = (255-gray).astype(np.float32)
    ridge = cv2.GaussianBlur(ink,(0,0),5,sigmaY=.65)-cv2.GaussianBlur(ink,(0,0),5,sigmaY=4)
    profile = ridge[:,int(w*.25):int(w*.75)].mean(axis=1)
    candidates = [y for y in range(8,h-8) if profile[y] > 5 and profile[y] >= profile[y-2:y+3].max()]
    peaks=[]
    for y in sorted(candidates,key=lambda i:-profile[i]):
        if all(abs(y-p)>12 for p in peaks): peaks.append(y)
    peaks=sorted(peaks)
    if len(peaks)<6: return {'tracks':0,'rmsPixels':None}
    gap=float(np.median(np.diff(peaks)))
    xs=np.arange(int(w*.04),int(w*.96),8)
    errors=[]
    for seed in peaks:
        ys=[];valid=[]
        for x in xs:
            lo=max(0,int(seed-gap*.38));hi=min(h,int(seed+gap*.38)+1)
            values=ridge[lo:hi,x-3:x+4].mean(axis=1)
            best=int(values.argmax());ys.append(lo+best);valid.append(values[best]>3)
        if np.mean(valid)<.8: continue
        ys=np.asarray(ys,dtype=float);fit=np.polyval(np.polyfit(xs,ys,1),xs)
        errors.append(float(np.sqrt(np.mean((ys-fit)**2))))
    return {'tracks':len(errors),'rmsPixels':float(np.median(errors)) if errors else None,
            'rms95Pixels':float(np.percentile(errors,95)) if errors else None}


def paired_rule_metric(reference, image):
    """Reference-seeded tracks avoid selecting different text/rules in each output.

    Still a screenshot proxy: registration and ridge ambiguities remain. Report alongside
    the original autonomous metric, including regressions, rather than replacing it.
    """
    sift = cv2.SIFT_create(nfeatures=4000)
    a, da = sift.detectAndCompute(image, None)
    b, db = sift.detectAndCompute(reference, None)
    if da is None or db is None: return {'aligned': False}
    good = [x for pair in cv2.BFMatcher().knnMatch(da, db, k=2) if len(pair) == 2
            for x, y in [pair] if x.distance < .72 * y.distance]
    if len(good) < 12: return {'aligned': False}
    matrix, _ = cv2.findHomography(np.float32([a[x.queryIdx].pt for x in good]),
                                  np.float32([b[x.trainIdx].pt for x in good]), cv2.RANSAC, 4)
    if matrix is None: return {'aligned': False}
    h, w = reference.shape
    warped = cv2.warpPerspective(image, matrix, (w, h), borderValue=255)
    def ridge(gray):
        ink = (255 - gray).astype(np.float32)
        return (cv2.GaussianBlur(ink, (0, 0), 5, sigmaY=.65) -
                cv2.GaussianBlur(ink, (0, 0), 5, sigmaY=4))
    reference_ridge = ridge(reference)
    profile = reference_ridge[:, int(w*.25):int(w*.75)].mean(axis=1)
    peaks = []
    for y in sorted(range(8, h-8), key=lambda y: -profile[y]):
        if (profile[y] > 5 and profile[y] >= profile[y-2:y+3].max()
                and all(abs(y-p) > 12 for p in peaks)): peaks.append(y)
    peaks.sort()
    if len(peaks) < 6: return {'aligned': True, 'matchedTracks': 0}
    gap = float(np.median(np.diff(peaks)))
    xs = np.arange(int(w*.04), int(w*.96), 8)
    response = ridge(warped)
    errors = []
    for seed in peaks:
        ys, valid = [], []
        for x in xs:
            lo = max(0, int(seed-gap*.38)); hi = min(h, int(seed+gap*.38)+1)
            values = response[lo:hi, x-3:x+4].mean(axis=1)
            i = int(values.argmax()); ys.append(lo+i); valid.append(values[i] > 3)
        if np.mean(valid) < .8: continue
        ys = np.asarray(ys, dtype=float)
        fitted = np.polyval(np.polyfit(xs, ys, 1), xs)
        errors.append(float(np.sqrt(np.mean((ys-fitted)**2))))
    return {'aligned': True, 'referenceSeeds': len(peaks), 'matchedTracks': len(errors),
            'medianRmsPixels': float(np.median(errors)) if errors else None,
            'rms95Pixels': float(np.percentile(errors, 95)) if errors else None}


def compare(reference, image):
    sift=cv2.SIFT_create(nfeatures=4000)
    a,da=sift.detectAndCompute(image,None);b,db=sift.detectAndCompute(reference,None)
    pairs=cv2.BFMatcher().knnMatch(da,db,k=2)
    good=[x for x,y in pairs if x.distance < .72*y.distance]
    if len(good)<12: return {'aligned':False,'matches':len(good)}
    src=np.float32([a[x.queryIdx].pt for x in good]);dst=np.float32([b[x.trainIdx].pt for x in good])
    matrix,inliers=cv2.findHomography(src,dst,cv2.RANSAC,4)
    if matrix is None:return {'aligned':False,'matches':len(good)}
    h,w=reference.shape
    warped=cv2.warpPerspective(image,matrix,(w,h),borderValue=255)
    valid=cv2.warpPerspective(np.full(image.shape,255,np.uint8),matrix,(w,h))>250
    valid[:int(h*.04)]=False;valid[int(h*.96):]=False;valid[:,:int(w*.04)]=False;valid[:,int(w*.96):]=False
    blank=valid & (cv2.dilate((reference<235).astype(np.uint8),np.ones((21,21),np.uint8))==0)
    reference_ink=(reference<150)&valid;our_ink=(warped<150)&valid
    return {'aligned':True,'matches':len(good),'inliers':int(inliers.sum()),
            'referenceBlankPixels':int(blank.sum()),
            'darkInkInReferenceBlank':float(np.mean(warped[blank]<210)),
            'meanInkInReferenceBlank':float(np.mean(255-warped[blank])),
            'binaryInkIoU':float(np.sum(reference_ink&our_ink)/max(1,np.sum(reference_ink|our_ink)))}


def shared_geometry_visual(reference, before, after):
    """Only for variants with identical output geometry: register the baseline once.
    Changing cleanup can move SIFT matches; one fixed transform isolates appearance changes.
    """
    sift = cv2.SIFT_create(nfeatures=4000)
    a, da = sift.detectAndCompute(before, None); b, db = sift.detectAndCompute(reference, None)
    if da is None or db is None: return {'aligned': False}
    good = [x for pair in cv2.BFMatcher().knnMatch(da, db, k=2) if len(pair) == 2
            for x, y in [pair] if x.distance < .72*y.distance]
    if len(good) < 12 or before.shape != after.shape: return {'aligned': False}
    matrix, _ = cv2.findHomography(np.float32([a[x.queryIdx].pt for x in good]),
                                  np.float32([b[x.trainIdx].pt for x in good]), cv2.RANSAC, 4)
    if matrix is None: return {'aligned': False}
    h, w = reference.shape
    valid = cv2.warpPerspective(np.full(before.shape, 255, np.uint8), matrix, (w, h)) > 250
    valid[:int(h*.04)] = False; valid[int(h*.96):] = False
    valid[:, :int(w*.04)] = False; valid[:, int(w*.96):] = False
    blank = valid & (cv2.dilate((reference < 235).astype(np.uint8), np.ones((21, 21), np.uint8)) == 0)
    target = (reference < 150) & valid
    def measure(image):
        warped = cv2.warpPerspective(image, matrix, (w, h), borderValue=255)
        ink = (warped < 150) & valid
        return {'darkInkInReferenceBlank': float(np.mean(warped[blank] < 210)),
                'meanInkInReferenceBlank': float(np.mean(255-warped[blank])),
                'binaryInkIoU': float(np.sum(ink & target)/max(1, np.sum(ink | target)))}
    return {'aligned': True, 'scope': 'identical geometry only; baseline registration reused',
            'before': measure(before), 'after': measure(after)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for arg in ['reference','baseline','result','out']:parser.add_argument('--'+arg,type=Path,required=True)
    parser.add_argument('--reference-crop',type=lambda x:tuple(map(int,x.split(','))))
    parser.add_argument('--same-geometry', action='store_true', help='Also reuse baseline registration; only for identical geometry variants')
    parser.add_argument('--width',type=int,default=900)
    parser.add_argument('--baseline-label', default='Previous 1.2 review')
    parser.add_argument('--result-label', default='PaperForge: curvature + foreground')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    ref=load(args.reference,args.width,args.reference_crop);before=load(args.baseline,args.width);after=load(args.result,args.width)
    report={'metricScope':'owner same-photo screenshot reference; content alignment; not OCR or corner truth',
        'referenceCrop':args.reference_crop,'analysisWidth':args.width,
        'reference':{'rules':line_metric(ref)},
        'before':{'visual':compare(ref,before),'rules':line_metric(before), 'referenceSeededRules':paired_rule_metric(ref,before)},
        'after':{'visual':compare(ref,after),'rules':line_metric(after), 'referenceSeededRules':paired_rule_metric(ref,after)}}
    if args.same_geometry: report['sharedGeometry'] = shared_geometry_visual(ref, before, after)
    (args.out/'visual-metrics.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    board=Image.new('RGB',(1800,940),'#f1f2f0');draw=ImageDraw.Draw(board)
    for index,(array,label) in enumerate(zip([ref,before,after],['Owner reference',args.baseline_label,args.result_label])):
        im=ImageOps.contain(Image.fromarray(array).convert('RGB'),(580,880))
        board.paste(im,(index*600+(600-im.width)//2,45));draw.text((index*600+12,15),label,fill='black')
    board.save(args.out/'contact.jpg',quality=95)
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()

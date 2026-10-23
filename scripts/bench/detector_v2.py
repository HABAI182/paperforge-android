"""PaperForge detector experiment, written independently.
Technique references: OSS-DocumentScanner ff05fc52 (MIT): multi-channel candidates;
open-android-doc-scanner 3ba5c50a (Apache-2.0): evidence scoring over area ranking.
"""
import cv2,numpy as np,itertools,math
from baseline_v020 import ordered

def detect_result(image,params=None):
    params=params or {}; h,w=image.shape[:2];scale=min(1.,params.get('detectorMaxEdge',1000)/max(h,w))
    small=cv2.resize(image,None,fx=scale,fy=scale,interpolation=cv2.INTER_AREA);sh,sw=small.shape[:2]
    lab=cv2.cvtColor(small,cv2.COLOR_BGR2LAB);gray=cv2.cvtColor(small,cv2.COLOR_BGR2GRAY)
    channels=[lab[:,:,0]]
    for c in [lab[:,:,1],lab[:,:,2],cv2.cvtColor(small,cv2.COLOR_BGR2HSV)[:,:,1]]:
        if np.percentile(c,97)-np.percentile(c,3)>8: channels.append(c)
    edge=np.zeros((sh,sw),np.uint8); candidates=[]; kernel=np.ones((5,5),np.uint8)
    def collect(mask,method):
        contours,_=cv2.findContours(mask,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
        for contour in sorted(contours,key=cv2.contourArea,reverse=True)[:params.get('candidateLimit',40)]:
            if cv2.contourArea(contour)<sw*sh*params.get('minimumArea',.07): continue
            hull=cv2.convexHull(contour); perimeter=cv2.arcLength(hull,True)
            for eps in [.008,.015,.025,.04,.06]:
                p=cv2.approxPolyDP(hull,eps*perimeter,True)
                if len(p)==4 and cv2.isContourConvex(p): candidates.append((ordered(p.reshape(4,2).astype(np.float32)),method));break
            rect=cv2.boxPoints(cv2.minAreaRect(hull))
            if cv2.contourArea(hull)/max(1,cv2.contourArea(rect))>.8: candidates.append((ordered(rect),method+'-rect'))
    for index,channel in enumerate(channels):
        blur=cv2.GaussianBlur(channel,(5,5),0)
        gx=cv2.Sobel(blur,cv2.CV_32F,1,0);gy=cv2.Sobel(blur,cv2.CV_32F,0,1)
        magnitude=cv2.magnitude(gx,gy); positive=magnitude[magnitude>4]
        threshold=float(np.median(positive)) if positive.size else 24
        canny=cv2.Canny(blur,max(8,threshold*.6),max(24,min(180,threshold*1.5)))
        edge=cv2.bitwise_or(edge,canny);collect(cv2.morphologyEx(canny,cv2.MORPH_CLOSE,kernel),'channel-'+str(index))
        if index==0:
            weak=cv2.Canny(blur,8,24);edge=cv2.bitwise_or(edge,weak);collect(cv2.morphologyEx(weak,cv2.MORPH_CLOSE,kernel),'weak-luma')
            paper_level=float(np.percentile(blur,92));background_level=float(np.median(np.r_[blur[:20].ravel(),blur[-20:].ravel()]))
            if paper_level-background_level>3:
                _,paper_mask=cv2.threshold(blur,(paper_level+background_level)/2,255,cv2.THRESH_BINARY)
                collect(cv2.morphologyEx(paper_mask,cv2.MORPH_CLOSE,kernel),'paper-luma')
        _,binary=cv2.threshold(blur,0,255,cv2.THRESH_BINARY+cv2.THRESH_OTSU)
        collect(cv2.morphologyEx(binary,cv2.MORPH_CLOSE,kernel),'threshold-'+str(index))
        collect(cv2.morphologyEx(cv2.bitwise_not(binary),cv2.MORPH_CLOSE,kernel),'inverse-'+str(index))
    band=max(2,min(sh,sw)//25)
    border=np.concatenate([lab[:band].reshape(-1,3),lab[-band:].reshape(-1,3),lab[:,:band].reshape(-1,3),lab[:,-band:].reshape(-1,3)])
    background=np.median(border,axis=0)
    distance=np.linalg.norm((lab.astype(float)-background)*[.5,1,1],axis=2)
    distance=np.clip(distance*3,0,255).astype(np.uint8)
    _,mask=cv2.threshold(distance,0,255,cv2.THRESH_BINARY+cv2.THRESH_OTSU)
    collect(cv2.morphologyEx(mask,cv2.MORPH_CLOSE,kernel),'border-color')
    for threshold in [12,24,45]:
        _,mask=cv2.threshold(distance,threshold,255,cv2.THRESH_BINARY)
        collect(cv2.morphologyEx(mask,cv2.MORPH_CLOSE,kernel),'border-distance-'+str(threshold))
    support=cv2.dilate(edge,np.ones((5,5),np.uint8))
    def features(q):
        if np.any(q<[-1,-1]) or np.any(q>[sw,sh]): return None
        q=np.clip(q,[0,0],[sw-1,sh-1]).astype(np.float32);area=abs(cv2.contourArea(q))/(sw*sh)
        if area<.07 or area>.9999: return None
        lengths=np.linalg.norm(np.roll(q,-1,axis=0)-q,axis=1)
        if lengths.min()<min(sh,sw)*.06: return None
        edge_hits=[]; band_hits=[]; contrasts=[]; shape=[]
        for i in range(4):
            a=q[i];b=q[(i+1)%4];delta=b-a;length=np.linalg.norm(delta);normal=np.array([-delta[1],delta[0]])/length
            along=np.linspace(.04,.96,48)[:,None];points=a+(b-a)*along
            rounded=np.rint(points).astype(int); edge_hits.extend(support[rounded[:,1],rounded[:,0]]>0)
            inner=points+normal*4;outer=points-normal*4
            good=(inner[:,0]>=0)&(inner[:,0]<sw)&(inner[:,1]>=0)&(inner[:,1]<sh)&(outer[:,0]>=0)&(outer[:,0]<sw)&(outer[:,1]>=0)&(outer[:,1]<sh)
            if good.any():
                pi=inner[good].astype(int);po=outer[good].astype(int)
                diff=lab[pi[:,1],pi[:,0]].astype(float)-lab[po[:,1],po[:,0]]
                delta_color=np.linalg.norm(diff,axis=1); contrasts.extend(delta_color)
                votes=np.zeros(48,bool);votes[good]=delta_color>4;band_hits.extend(votes)
            else: band_hits.extend(np.zeros(48,bool))
            prior=q[(i-1)%4]-a;shape.append(abs(np.dot(prior,delta)/(np.linalg.norm(prior)*length)))
        poly=np.zeros((sh,sw),np.uint8);cv2.fillConvexPoly(poly,q.astype(int),255)
        # Strided sampling keeps candidate scoring bounded on live frames.
        pixels=gray[::4,::4][poly[::4,::4]>0]
        paper=float(np.percentile(pixels,85)); paper_fraction=float(np.mean(pixels>paper-25))
        canny_score=float(np.mean(edge_hits)); band_score=float(np.mean(band_hits)) if band_hits else 0
        edge_score=max(canny_score,band_score);contrast=min(1,float(np.median(contrasts))/22) if contrasts else 0
        aspect=max(lengths[0],lengths[2])/max(lengths[1],lengths[3])
        aspect_score=max(math.exp(-abs(math.log(aspect/r))*.7) for r in [.707,1.414,.773,1.294,.63,1.585,.3,3.3])
        shape_score=max(0,1-float(np.mean(shape)))
        score=.34*edge_score+.15*contrast+.23*paper_fraction+.12*shape_score+.04*aspect_score+.12*min(1,area/.4)
        if edge_score<.3: score*=.6
        return score,{'edge_support':edge_score,'edge_contrast':contrast,'paper_fraction':paper_fraction,'shape':shape_score,'area':area}
    scored=[];seen=set()
    for q,method in candidates:
        key=tuple(np.rint(q.reshape(-1)/4).astype(int))
        if key in seen: continue
        seen.add(key);f=features(q)
        if f:scored.append((f[0],q,method,f[1]))
    # Line candidates rescue a broken contour. Keep a small fixed candidate budget.
    if not scored or max(c[0] for c in scored)<.88:
        lines=cv2.HoughLinesP(edge,1,np.pi/180,70,minLineLength=min(sw,sh)*.22,maxLineGap=min(sw,sh)*.06)
        if lines is not None:
            lines=sorted(lines[:,0],key=lambda l:np.linalg.norm(l[:2]-l[2:]),reverse=True)[:24]
            horizontal=[l for l in lines if abs(l[2]-l[0])>abs(l[3]-l[1])*1.3][:8]
            vertical=[l for l in lines if abs(l[3]-l[1])>abs(l[2]-l[0])*1.3][:8]
            def intersect(a,b):
                v=np.cross(np.r_[a[:2],1],np.r_[a[2:],1]);u=np.cross(np.r_[b[:2],1],np.r_[b[2:],1]);p=np.cross(v,u)
                return p[:2]/p[2] if abs(p[2])>1e-8 else None
            pairs_h=[p for p in itertools.combinations(horizontal,2) if abs(np.mean(p[0][[1,3]])-np.mean(p[1][[1,3]]))>sh*.25][:8]
            pairs_v=[p for p in itertools.combinations(vertical,2) if abs(np.mean(p[0][[0,2]])-np.mean(p[1][[0,2]]))>sw*.15][:8]
            for hs in pairs_h:
                for vs in pairs_v:
                    pts=[intersect(a,b) for a in hs for b in vs]
                    if any(p is None for p in pts): continue
                    q=ordered(np.array(pts,np.float32))
                    if not cv2.isContourConvex(q):continue
                    f=features(q)
                    if f:scored.append((f[0]-.015,q,'line-fit',f[1]))
    if not scored:return {'quad':None,'confidence':0.,'method':'none','diagnostics':{}}
    score,q,method,diag=max(scored,key=lambda c:c[0])
    confidence=max(0.,min(1.,(score-.35)/.6))
    if score<.71 or diag['edge_support']<.25:return {'quad':None,'confidence':confidence,'method':method,'diagnostics':diag}
    return {'quad':(q/[sw-1,sh-1]).tolist(),'confidence':confidence,'method':method,'diagnostics':diag}

def detect(image,params=None): return detect_result(image,params)['quad']

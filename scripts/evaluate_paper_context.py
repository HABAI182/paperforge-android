"""Compare offline candidate with shipped tiny model; never writes Android assets."""
from pathlib import Path
import argparse,json,time
import cv2,numpy as np,torch,onnx,onnxruntime as ort
from onnx import numpy_helper
from train_paper_context import ContextInk,evaluate,features
from train_paper_ink import InkNet


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate',type=Path,required=True)
    p.add_argument('--reference',type=Path,required=True)
    a=p.parse_args();root=a.candidate;torch.set_num_threads(4);cv2.setNumThreads(1)
    candidate=ContextInk();candidate.load_state_dict(torch.load(root/'context-ink.pt',weights_only=True));candidate.eval()
    old=InkNet();old.load_state_dict({x.name.removeprefix('m.'):torch.tensor(numpy_helper.to_array(x).copy()) for x in onnx.load('app/src/main/assets/retouch/paper-ink-v1.onnx').graph.initializer});old.eval()
    result={'scope':'same held-out procedural fixtures; real screenshot masks are weak registration proxies, not faint-text truth',
            'synthetic':{'shipped':evaluate(old,811735,100),'candidate':evaluate(candidate,811735,100)},'promote':False}
    opts=ort.SessionOptions();opts.intra_op_num_threads=4;opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(root/'context-ink.onnx'),sess_options=opts,providers=['CPUExecutionProvider'])
    probe=np.random.default_rng(2289).random((1,3,73,101),dtype=np.float32)
    with torch.inference_mode():expected=candidate(torch.from_numpy(probe)).sigmoid().numpy()
    actual=session.run(None,{'ink_features':probe})[0]
    result['onnxDynamicOddShapeMaxAbsError']=float(np.max(np.abs(actual-expected)))
    assert np.max(np.abs(actual-expected))<1e-5
    reference=cv2.imread(str(a.reference),0)
    photo=Path(json.loads((root/'report.json').read_text())['photoInputs'][0]['path'])
    raw=cv2.imread(str(photo),0);scale=min(1,960/max(raw.shape));raw=cv2.resize(raw,(round(raw.shape[1]*scale),round(raw.shape[0]*scale)))
    sift=cv2.SIFT_create(nfeatures=5000);ka,da=sift.detectAndCompute(raw,None);kb,db=sift.detectAndCompute(reference,None)
    matches=[x for pair in cv2.BFMatcher().knnMatch(db,da,k=2) if len(pair)==2 for x,y in [pair] if x.distance<.72*y.distance]
    matrix,inliers=cv2.findHomography(np.float32([kb[x.queryIdx].pt for x in matches]),np.float32([ka[x.trainIdx].pt for x in matches]),cv2.RANSAC,4)
    assert matrix is not None and inliers.sum()>80
    valid=cv2.warpPerspective(np.ones_like(reference),(matrix),raw.shape[::-1])>0
    blank=cv2.warpPerspective((cv2.dilate((reference<235).astype('uint8'),np.ones((25,25),'uint8'))==0).astype('uint8'),matrix,raw.shape[::-1])>0
    valid[:40]=False;valid[-40:]=False;valid[:,:40]=False;valid[:,-40:]=False
    field=cv2.medianBlur(cv2.dilate(raw,np.ones((3,3),'uint8')),max(7,int(min(raw.shape)*.035)|1))
    ink=np.clip(1-raw.astype('float32')/np.maximum(20,field.astype('float32')),0,1)
    negative=blank&valid&(ink>.012)&(ink<.20)
    x=torch.from_numpy(features(ink)[None]);rows={}
    for name,model in [('shipped',old),('candidate',candidate)]:
        with torch.inference_mode():
            start=time.perf_counter();score=model(x).sigmoid()[0,0].numpy();elapsed=(time.perf_counter()-start)*1000
        rows[name]={'cpuTorch4ThreadsMs':elapsed,'weakNegativePixels':int(negative.sum()),
            'negativeRejected':{str(t):float(np.mean(score[negative]<t)) for t in [.02,.05,.1,.2,.3,.5]}}
    result['bookScreenshotProxy']={'matches':len(matches),'inliers':int(inliers.sum()),'rows':rows,'timing':'one CPU desktop inference, not Android or phone acceptance'}
    synthetic=result['synthetic']['candidate']['0.02']
    result['promotionGate']={
        'candidateThreshold':.02,
        'requiredFaintRetention':.9999,
        'requiredSyntheticBleedRejection':.90,
        'faintPass':synthetic['faintRetention']>=.9999,
        'bleedPass':synthetic['bleedRejected']>=.90,
        'heldOutRealTextMasksAvailable':False,
        'androidLatencyAccepted':False,
        'decision':'keep experimental; no app integration',
    }
    (root/'comparison.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()

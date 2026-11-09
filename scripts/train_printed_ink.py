"""Printed-document specialist with explicit non-overlapping region validation.

The only paired book page is split into separated upper/lower regions. This is
NOT a page-disjoint real test. Synthetic CJK/Latin validation uses unseen fonts.
The specialist remains separate from the handwriting model and the application.
"""

from pathlib import Path
import argparse
import hashlib
import json
import time

import cv2
import numpy as np
import torch
from torch import nn

from train_paper_context import ContextInk, Export, evaluate, fixture
from paper_pair_lab import printed_layers


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--book', type=Path, required=True)
    parser.add_argument('--warm', type=Path, required=True)
    parser.add_argument('--font', type=Path, action='append', required=True)
    parser.add_argument('--validation-font', type=Path, action='append', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--steps', type=int, default=4000)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    assert out != repo and repo not in out.parents
    out.mkdir(exist_ok=True, parents=True)
    cv2.setNumThreads(1)
    torch.set_num_threads(4)
    torch.manual_seed(20261110)
    rng = np.random.default_rng(20261110)
    image = cv2.imread(str(args.book / 'source.png'), 0)
    labels = cv2.imread(str(args.book / 'weak-labels.png'), 0)
    h,w = image.shape
    field = cv2.medianBlur(cv2.dilate(image, np.ones((3,3),'uint8')), max(7, int(min(h,w)*.035)|1))
    ink = np.clip(1-image.astype('float32')/np.maximum(20,field.astype('float32')),0,1)
    from train_paper_context import features
    x = features(ink)
    y = (labels == 255).astype('float32')
    weight = (labels == 255).astype('float32')*12 + (labels == 0).astype('float32')*8
    # The patch support cannot cross this boundary. Validation starts after a
    # full-patch gap, so a receptive field cannot see a held-out source region.
    train_end = round(h*.43)
    hold_start = round(h*.57)
    positive = np.argwhere((labels[:train_end] == 255))
    negative = np.argwhere((labels[:train_end] == 0))
    generated = printed_layers(args.font)
    held = printed_layers(args.validation_font)
    samples = []
    for i in range(1536):
        if i%2 == 0:
            sx,sy,sw,_,contrast = fixture(rng,sparse=True,font_layers=generated)
            if contrast < .12:
                sw = .1 + sy*60
            samples.append((sx,sy,sw))
        else:
            centres = positive if rng.integers(2) else negative
            cy,cx = centres[int(rng.integers(len(centres)))]
            top = int(np.clip(cy-48,0,train_end-96))
            left = int(np.clip(cx-48,0,w-96))
            samples.append((x[:,top:top+96,left:left+96],y[None,top:top+96,left:left+96],weight[None,top:top+96,left:left+96]))
    sx,sy,sw = [torch.from_numpy(np.stack([row[i] for row in samples])).cuda() for i in range(3)]
    model = ContextInk().cuda()
    model.load_state_dict(torch.load(args.warm,map_location='cuda',weights_only=True))
    optimizer = torch.optim.AdamW(model.parameters(),lr=.0002)
    criterion = nn.BCEWithLogitsLoss(reduction='none')
    start = time.time()
    for step in range(args.steps):
        ids = torch.from_numpy(rng.integers(len(samples),size=16)).cuda()
        loss = (criterion(model(sx[ids]),sy[ids])*sw[ids]).mean()
        optimizer.zero_grad();loss.backward();optimizer.step()
        if step%800 == 0:
            print(step,float(loss.detach()),round(time.time()-start,2),flush=True)
    model.eval().cpu()
    with torch.inference_mode():
        score = model(torch.from_numpy(x[None])).sigmoid()[0,0].numpy()
    report = {'scope':'single-page separated-region printed-specialist study; NOT page-disjoint real validation or Android output',
              'sourceSha256':hashlib.sha256((args.book/'source.png').read_bytes()).hexdigest(),
              'trainRows':[0,train_end],'holdoutRows':[hold_start,h],'patchSize':96,
              'fontInputs':[p.name for p in args.font],'unseenValidationFonts':[p.name for p in args.validation_font],
              'steps':args.steps,'seconds':time.time()-start,'integrated':False,'regions':[]}
    for name,first,last in [('upper-training',0,train_end),('lower-holdout',hold_start,h)]:
        p = score[first:last];weak = labels[first:last]
        report['regions'].append({'id':name,'thresholds':{str(t):{'weakPositiveRetention':float((p[weak==255]>=t).mean()),'weakNegativeRejected':float((p[weak==0]<t).mean())} for t in [.005,.01,.02,.05,.1,.5]}})
    report['fontSynthetic'] = evaluate(model,11889,100,font_layers=held)
    torch.save(model.state_dict(),out/'printed-ink.pt')
    np.save(out/'score.npy',score)
    cv2.imwrite(str(out/'offline-proposal.png'),np.round(255*(1-ink*score)).astype('uint8'))
    torch.onnx.export(Export(model),torch.zeros(1,3,73,101),out/'printed-ink.onnx',input_names=['ink_features'],output_names=['foreground'],opset_version=13,dynamo=False,dynamic_axes={'ink_features':{2:'height',3:'width'},'foreground':{2:'height',3:'width'}})
    report['sha256'] = hashlib.sha256((out/'printed-ink.onnx').read_bytes()).hexdigest()
    (out/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(report['regions'],report['fontSynthetic']['0.02'],flush=True)


if __name__ == '__main__':
    main()

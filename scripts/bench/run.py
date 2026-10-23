"""Desktop parity benchmark; output is evidence on this corpus, not phone accuracy."""
from pathlib import Path
import argparse,csv,json,time,importlib,html,platform
import cv2,numpy as np
from PIL import Image,ImageDraw
from metrics import quad_metrics,cer

ROOT=Path(__file__).resolve().parents[2]
def main():
    p=argparse.ArgumentParser();p.add_argument('dataset',type=Path);p.add_argument('--engine',default='baseline_v020');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    module=importlib.import_module(a.engine); manifest=json.loads((a.dataset/'manifest.json').read_text(encoding='utf-8')); params=json.loads((ROOT/'app/src/main/assets/scan_params.json').read_text())
    a.output.mkdir(parents=True,exist_ok=True); rows=[]; thumbs=[]
    for c in manifest['cases']:
        image=cv2.imread(str(a.dataset/c['file']));h,w=image.shape[:2]
        start=time.perf_counter(); q=module.detect(image,params);elapsed=(time.perf_counter()-start)*1000
        metrics=quad_metrics(q,c.get('quad'),w,h)
        rows.append({'file':c['file'],'scene':c['scene'],'synthetic':c['synthetic'],'input_width':w,'input_height':h,**metrics,'detect_ms':elapsed})
        thumb=cv2.resize(image,(320,240))
        if c.get('quad') is not None:
            truth=np.array(c['quad'])*[320,240];cv2.polylines(thumb,[truth.astype(np.int32)],True,(0,210,0),2)
        if q is not None: cv2.polylines(thumb,[(np.array(q)*[320,240]).astype(np.int32)],True,(0,0,255),2)
        label=f'IoU={metrics["iou"]:.3f}' if metrics['iou'] is not None else f'false positive={metrics["failed"]}'
        tile=Image.new('RGB',(320,280),'white');tile.paste(Image.fromarray(cv2.cvtColor(thumb,cv2.COLOR_BGR2RGB)),(0,0));ImageDraw.Draw(tile).text((5,244),f'{c["file"]} {label}',fill='black');thumbs.append(tile)
    with (a.output/'results.csv').open('w',encoding='utf-8',newline='') as f: writer=csv.DictWriter(f,fieldnames=rows[0].keys());writer.writeheader();writer.writerows(rows)
    metrics=[r for r in rows if r['scene'] not in ['ocr-mixed','resolution-12mp','no-document']]
    summary={'engine':a.engine,'dataset':manifest['name'],'cases':len(rows),'detection_cases':len(metrics),'synthetic_cases':sum(r['synthetic'] for r in rows),'device':'desktop '+platform.platform(),'opencv':cv2.__version__,'mean_iou':float(np.mean([r['iou'] for r in metrics])),'mean_corner_error_pct':float(np.mean([r['corner_error_pct'] for r in metrics])),'failure_rate':float(np.mean([r['failed'] for r in metrics])),'detect_ms_p50':float(np.median([r['detect_ms'] for r in metrics])),'detect_ms_p95':float(np.percentile([r['detect_ms'] for r in metrics],95)),'scenes':{s:{'n':sum(r['scene']==s for r in metrics),'mean_iou':float(np.mean([r['iou'] for r in metrics if r['scene']==s]))} for s in sorted({r['scene'] for r in metrics})}}
    negatives=[r for r in rows if r['scene']=='no-document']; summary['negative_cases']=len(negatives); summary['false_positive_rate']=float(np.mean([r['failed'] for r in negatives]))
    (a.output/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
    for start in range(0,len(thumbs),24):
        subset=thumbs[start:start+24]; sheet=Image.new('RGB',(1280,280*((len(subset)+3)//4)),(220,220,220))
        for i,t in enumerate(subset): sheet.paste(t,((i%4)*320,(i//4)*280))
        sheet.save(a.output/f'contact-{start//24}.jpg',quality=92)
    (a.output/'index.html').write_text('<!doctype html><meta charset="utf-8"><h1>PaperForge '+html.escape(a.engine)+'</h1><p>Green: ground truth; red: detector. All generated cases are synthetic.</p>'+''.join(f'<img style="max-width:100%" src="{f.name}">' for f in a.output.glob('contact-*.jpg')),encoding='utf-8')
    print(json.dumps(summary,indent=2))
if __name__=='__main__': main()

"""Copy consented fixtures into the app sandbox, run a benchmark and collect results.
The production APK gains no storage permission. adb's shell copies the chosen files.
"""
from pathlib import Path
import argparse,subprocess,shutil,json,csv
from metrics import quad_metrics,cer
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('dataset',type=Path);p.add_argument('--adb',required=True);p.add_argument('--mode',choices=['baseline','current'],default='current');p.add_argument('--output',required=True,type=Path);p.add_argument('--collect-only',action='store_true');a=p.parse_args()
def adb(*args): return subprocess.run([a.adb,*map(str,args)],capture_output=True,check=True).stdout
remote='files'
if not a.collect_only:
    adb('shell','run-as','cn.paperforge.app','mkdir','-p',remote+'/bench')
    staging='/data/local/tmp/paperforge-bench'
    adb('shell','mkdir','-p',staging)
    adb('push',str(a.dataset.resolve())+'/.',staging+'/')
    adb('shell','run-as','cn.paperforge.app','cp','-R',staging+'/.',remote+'/bench/')
    assert adb('exec-out','run-as','cn.paperforge.app','cat',remote+'/bench/manifest.json')==(a.dataset/'manifest.json').read_bytes()
    print('fixtures copied; run QualityBenchmarkTest with benchMode='+a.mode)
    raise SystemExit(0)
a.output.mkdir(parents=True,exist_ok=True)
for name in ['results.json','high-resolution.jpg','bw-ten-pages.pdf','searchable.pdf','resolution-export.pdf']:
    (a.output/name).write_bytes(adb('exec-out','run-as','cn.paperforge.app','cat',remote+'/quality-'+a.mode+'/'+name))
data=json.loads((a.output/'results.json').read_text(encoding='utf-8')); manifest=json.loads((a.dataset/'manifest.json').read_text(encoding='utf-8')); cases={c['file']:c for c in manifest['cases']}
rows=[]
for r in data['rows']:
    c=cases[r['file']]
    import cv2
    im=cv2.imread(str(a.dataset/r['file']));h,w=im.shape[:2]
    m=quad_metrics(r.get('predicted'),c.get('quad'),w,h)
    rows.append({'file':r['file'],'scene':r['scene'],**m,'detect_ms':r['detect_ms'],'ocr_cer':cer(r['ocr'],c['ocr']) if c.get('ocr') and 'ocr' in r else None,'ocr_ms':r.get('ocr_ms'),'high_render_width':r.get('high_render_width'),'high_render_height':r.get('high_render_height')})
with (a.output/'results.csv').open('w',encoding='utf-8',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=rows[0].keys());writer.writeheader();writer.writerows(rows)
print(json.dumps({'mode':a.mode,'rows':len(rows),'ocr_pages':sum(r['ocr_cer'] is not None for r in rows),'ocr_cer_mean':sum(r['ocr_cer'] for r in rows if r['ocr_cer'] is not None)/max(1,sum(r['ocr_cer'] is not None for r in rows)),'sampled_peak_pss_kb':data['sampled_peak_pss_kb']},indent=2))

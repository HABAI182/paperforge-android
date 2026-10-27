"""Package pinned official Paddle ONNX files for offline Android sideloading."""
from pathlib import Path
import argparse, hashlib, json, zipfile

parser=argparse.ArgumentParser()
parser.add_argument('directory',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('--id', default='paddle-ppocrv5-mobile')
parser.add_argument('--name', default='PaddleOCR v5 Mobile')
parser.add_argument('--version', default='official-onnx-v5-mobile')
parser.add_argument('--languages', default='zh,en,ja', help='Comma-separated BCP47 language codes')
args=parser.parse_args()
files={p.name:p for p in args.directory.iterdir() if p.name in {'det.onnx','rec.onnx','rec.yml','provenance.json','font.ttf','font-LICENSE','upstream-model-card.md'}}
files['LICENSE']=Path(__file__).resolve().parents[1]/'ocr-paddle/LICENSE'
manifest={'format':1,'runtime':'paddle-onnx-v1','id':args.id,'name':args.name,'version':args.version,'languages':args.languages.split(','),'source':'https://github.com/PaddlePaddle/PaddleOCR','sdkCommit':'dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf','files':{name:{'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,path in sorted(files.items())}}
assert {'det.onnx','rec.onnx','rec.yml','LICENSE'}<=files.keys()
assert 'font.ttf' not in files or 'font-LICENSE' in files
args.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as target:
    def write(name, data):
        info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0))
        info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=0o100644<<16
        target.writestr(info,data,compresslevel=6)
    write('manifest.json',(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode('utf8'))
    for name,path in sorted(files.items()):write(name,path.read_bytes())
args.output.with_suffix('.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({'file':str(args.output),'bytes':args.output.stat().st_size,'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()}))

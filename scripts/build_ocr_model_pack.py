"""Package pinned official Paddle ONNX files for offline Android sideloading."""
from pathlib import Path
import argparse, hashlib, json, shutil, zipfile

parser=argparse.ArgumentParser()
parser.add_argument('directory',type=Path)
parser.add_argument('output',type=Path)
args=parser.parse_args()
files={p.name:p for p in args.directory.iterdir() if p.name in {'det.onnx','rec.onnx','rec.yml','provenance.json'}}
files['LICENSE']=Path(__file__).resolve().parents[1]/'ocr-paddle/LICENSE'
manifest={'format':1,'runtime':'paddle-onnx-v1','id':'paddle-ppocrv5-mobile','name':'PaddleOCR v5 Mobile','version':'official-onnx-v5-mobile','languages':['zh','en'],'source':'https://github.com/PaddlePaddle/PaddleOCR','sdkCommit':'dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf','files':{name:{'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,path in files.items()}}
assert {'det.onnx','rec.onnx','rec.yml','LICENSE'}<=files.keys()
args.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as target:
    target.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    for name,path in sorted(files.items()):target.write(path,name)
args.output.with_suffix('.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({'file':str(args.output),'bytes':args.output.stat().st_size,'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()}))

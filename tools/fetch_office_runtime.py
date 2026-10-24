"""Fetch reproducible mobile Office runtime from its published F-Droid builds."""
from pathlib import Path
import argparse,hashlib,json,urllib.request,zipfile,shutil
PINNED={
 'x86_64':(129,'cfaf9eea5fd695c449e92eb10cc0b1617ec51d068345068cea9b7e1c67968df5'),
 'arm64-v8a':(131,'751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475'),
}
def fetch(abis):
 root=Path(__file__).resolve().parents[1]
 runtime=root/'office-engine/runtime';downloads=runtime/'downloads';downloads.mkdir(parents=True,exist_ok=True)
 records=[]
 for abi in abis:
  code,expected=PINNED[abi]
  url=f'https://f-droid.org/repo/org.documentfoundation.libreoffice_{code}.apk'
  package=downloads/f'libreoffice-{code}.apk'
  if not package.exists() or hashlib.file_digest(package.open('rb'),'sha256').hexdigest()!=expected:
   print('Downloading',abi,flush=True);urllib.request.urlretrieve(url,package)
  if hashlib.file_digest(package.open('rb'),'sha256').hexdigest()!=expected:raise RuntimeError('Office runtime hash mismatch')
  with zipfile.ZipFile(package) as archive:
   for entry in archive.infolist():
    if entry.is_dir():continue
    if entry.filename.startswith('lib/'+abi+'/'):target=runtime/'jniLibs'/entry.filename[4:]
    elif entry.filename.startswith('assets/') and not entry.filename.startswith('assets/dexopt/'):
     target=runtime/entry.filename
    else:continue
    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(entry))
  records.append({'abi':abi,'version':'26.2.6.3','url':url,'sha256':expected,
   'source':f'https://f-droid.org/repo/org.documentfoundation.libreoffice_{code}_src.tar.gz'})
 font=root/'app/src/main/assets/fonts/PaperForgeCJK-Regular.ttf'
 target=runtime/'assets/unpack/user/fonts'/font.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(font,target)
 (runtime/'runtime-manifest.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
 print('Mobile Office engine runtime ready',flush=True)
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--abi',action='append',choices=PINNED)
 fetch(parser.parse_args().abi or list(PINNED))

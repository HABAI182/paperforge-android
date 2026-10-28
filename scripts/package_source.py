"""Create a clean source ZIP without SDKs, caches, private keys or build output."""
from pathlib import Path
import zipfile, re, subprocess, shutil

root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
output=root.parent/f'PaperForge-{version}-source.zip'
if (root/'.git').exists() and shutil.which('git'):
    status=subprocess.run(['git','status','--porcelain'],cwd=root,capture_output=True,text=True,check=True).stdout
    if status.strip():
        raise SystemExit('Commit source and acceptance records before packaging the release.')
    subprocess.run(['git','archive','--format=zip','--prefix=PaperForge/','--output='+str(output),'HEAD'],cwd=root,check=True)
    print(output)
    raise SystemExit(0)
excluded={'.git','.gradle','.kotlin','.idea','.cxx','.venv','build','runtime','captures','__pycache__'}
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(root.rglob('*')):
        rel=p.relative_to(root)
        if not p.is_file() or any(x in excluded for x in rel.parts): continue
        if p.name=='local.properties' or p.suffix in {'.jks','.keystore'}: continue
        info=zipfile.ZipInfo.from_file(p,(Path('PaperForge')/rel).as_posix())
        info.create_system=3
        info.external_attr=(0o100755 if p.name=='gradlew' else 0o100644)<<16
        archive.writestr(info,p.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
print(output)

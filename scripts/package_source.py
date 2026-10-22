"""Create a clean source ZIP without SDKs, caches, private keys or build output."""
from pathlib import Path
import zipfile

root=Path(__file__).resolve().parents[1]
output=root.parent/'PaperForge-0.1.0-source.zip'
excluded={'.git','.gradle','.kotlin','.idea','build','captures','__pycache__'}
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

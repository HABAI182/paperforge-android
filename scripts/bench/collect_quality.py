"""Collect only explicitly named debug-test artifact directories, never user documents."""
import argparse,re,subprocess
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
for directory in ['quality-encoding','quality-semantics','quality-camera','quality-conversion','quality-performance']:
    listing=subprocess.run([a.adb,'exec-out','run-as','cn.paperforge.app','ls','-1','files/'+directory],capture_output=True)
    if listing.returncode:continue
    output=a.output/directory;output.mkdir(parents=True,exist_ok=True)
    for name in listing.stdout.decode().splitlines():
        if not re.fullmatch(r'[A-Za-z0-9_.-]+',name):continue
        result=subprocess.run([a.adb,'exec-out','run-as','cn.paperforge.app','cat','files/'+directory+'/'+name],capture_output=True,check=True)
        (output/name).write_bytes(result.stdout)
    print(directory,[(f.name,f.stat().st_size) for f in output.iterdir()])

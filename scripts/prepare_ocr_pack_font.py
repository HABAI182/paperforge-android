"""Derive a static TrueType font for an optional OCR pack; preserve its OFL file.

Usage: python prepare_ocr_pack_font.py upstream.ttf output/font.ttf --family Latin
Requires fonttools. The source URL/revision and hashes belong in provenance.json.
"""
from pathlib import Path
import argparse
import hashlib
import json
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--family', required=True)
args = parser.parse_args()
font = TTFont(args.source)
axes = {axis.axisTag: axis.defaultValue for axis in font['fvar'].axes} if 'fvar' in font else {}
if 'wght' in axes:
    axes['wght'] = 400
if axes:
    font = instantiateVariableFont(font, axes, inplace=True)
family = 'PaperForge ' + args.family
names = {1: family, 2: 'Regular', 4: family + ' Regular', 6: family.replace(' ', '') + '-Regular'}
for record in font['name'].names:
    if record.nameID in names:
        record.string = names[record.nameID].encode(record.getEncoding())
args.output.parent.mkdir(parents=True, exist_ok=True)
font.save(args.output)
print(json.dumps({'axes': axes, 'bytes': args.output.stat().st_size,
                  'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest()}))

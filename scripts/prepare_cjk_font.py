"""Create the embedded static CJK font from the official Noto Sans SC variable TTF.
Usage: python prepare_cjk_font.py /path/to/NotoSansSC[wght].ttf
Requires fonttools. The derived font is renamed under the upstream SIL OFL.
"""
from pathlib import Path
import sys, unicodedata, hashlib
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

source=Path(sys.argv[1])
font=TTFont(source)
if 'fvar' in font:
    font=instantiateVariableFont(font,{'wght':400},inplace=True)
names={1:'PaperForge CJK',2:'Regular',3:'PaperForgeCJK-Regular-1',4:'PaperForge CJK Regular',6:'PaperForgeCJK-Regular'}
for record in font['name'].names:
    if record.nameID in names:
        record.string=names[record.nameID].encode(record.getEncoding())
# PDFBox 2's reverse cmap chooses compatibility radicals for shared glyph IDs.
# Keep their unified equivalents, so a visible 文 copies as U+6587, not U+2F42.
removed=0
for table in font['cmap'].tables:
    if not table.isUnicode():
        continue
    for code,glyph in list(table.cmap.items()):
        canonical=unicodedata.normalize('NFKC',chr(code))
        if len(canonical)==1 and ord(canonical)!=code and table.cmap.get(ord(canonical))==glyph:
            del table.cmap[code]
            removed+=1
output=Path(__file__).resolve().parents[1]/'app/src/main/assets/fonts/PaperForgeCJK-Regular.ttf'
font.save(output)
print({'removed_aliases':removed,'bytes':output.stat().st_size,'sha256':hashlib.sha256(output.read_bytes()).hexdigest()})

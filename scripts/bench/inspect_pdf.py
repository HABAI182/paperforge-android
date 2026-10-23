"""Independent PDF object/render probe. Requires pypdf, Pillow and Poppler on PATH."""
import argparse,json,subprocess
from pathlib import Path
from pypdf import PdfReader
from PIL import Image,ImageChops,ImageStat

def main():
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--output',type=Path,required=True);a=parser.parse_args()
    results=[]
    for path in sorted(a.directory.glob('*.pdf')):
        reader=PdfReader(path);row={'file':path.name,'bytes':path.stat().st_size,'pages':len(reader.pages),'first_page_images':[]}
        page=reader.pages[0]
        for image in page.images:
            obj=image.indirect_reference.get_object()
            row['first_page_images'].append({'width':obj.get('/Width'),'height':obj.get('/Height'),'bits':obj.get('/BitsPerComponent'),'colorspace':str(obj.get('/ColorSpace')),'filter':str(obj.get('/Filter')),'smask':'/SMask' in obj,'page_width_pt':float(page.mediabox.width),'page_height_pt':float(page.mediabox.height),'dpi_y':float(obj.get('/Height'))*72/float(page.mediabox.height)})
        row['text']=page.extract_text()
        android=a.directory/(path.stem+'-android.png')
        if android.exists():
            original=Image.open(android).convert('RGB');prefix=a.directory/(path.stem+'-poppler')
            subprocess.run(['pdftoppm','-f','1','-singlefile','-scale-to-x',str(original.width),'-scale-to-y',str(original.height),'-png',str(path),str(prefix)],check=True,capture_output=True)
            other=Image.open(prefix.with_suffix('.png')).convert('RGB')
            row['cross_renderer_mae']=sum(ImageStat.Stat(ImageChops.difference(original,other)).mean)/3
            assert row['cross_renderer_mae']<2,(path,row['cross_renderer_mae'])
        results.append(row)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(results,ensure_ascii=False,indent=2))
if __name__=='__main__':main()

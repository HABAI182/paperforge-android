"""Object/text checks plus unchanged native-region render diff on generated semantic fixture."""
import argparse,hashlib,json,subprocess
from pathlib import Path
from pypdf import PdfReader
from PIL import Image,ImageChops,ImageStat

p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
source=a.directory/'mixed-original.pdf';output=a.directory/'mixed-compressed.pdf';before,after=PdfReader(source),PdfReader(output)
def hashes(reader):return [hashlib.sha256(page.get_contents().get_data()).hexdigest() for page in reader.pages]
def links(reader):return [[annotation.get_object().get('/A',{}).get('/URI') for annotation in page.get('/Annots',[]) if annotation.get_object().get('/Subtype')=='/Link'] for page in reader.pages]
def fields(reader):return {k:v.get('/V') for k,v in reader.get_fields().items()}
assert len(before.pages)==len(after.pages)==2
assert hashes(before)==hashes(after)
assert [page.extract_text() for page in before.pages]==[page.extract_text() for page in after.pages]
assert links(before)==links(after)
assert fields(before)==fields(after)
assert before.metadata.title==after.metadata.title
rendered=[]
for path in [source,output]:
    prefix=path.with_suffix('')
    subprocess.run(['pdftoppm','-f','1','-singlefile','-r','72','-png',str(path),str(prefix)],capture_output=True,check=True)
    rendered.append(Image.open(prefix.with_suffix('.png')).convert('RGB'))
top=ImageChops.difference(rendered[0].crop((0,0,595,200)),rendered[1].crop((0,0,595,200)))
bottom=ImageChops.difference(rendered[0].crop((0,680,595,842)),rendered[1].crop((0,680,595,842)))
mae=[sum(ImageStat.Stat(diff).mean)/3 for diff in [top,bottom]]
assert max(mae)<.1,mae
report={'inputBytes':source.stat().st_size,'outputBytes':output.stat().st_size,'pageContentSha256':hashes(after),'text':[p.extract_text() for p in after.pages],'links':links(after),'fields':fields(after),'nativeTopBottomRenderMAE':mae,'imageReport':json.loads(Path(str(output)+'.report.json').read_text())}
a.output.write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2))

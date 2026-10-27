"""Build indexed full-frame contact sheets from an already captured native sequence. No grading."""
from pathlib import Path
import json,argparse,textwrap
from PIL import Image,ImageDraw,ImageFont
p=argparse.ArgumentParser();p.add_argument('runs',nargs='+');a=p.parse_args()
root=Path(__file__).resolve().parents[1]
font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',15)
for rel in a.runs:
 run=root/rel;d=json.loads((run/'sequential-ability-report.json').read_text());out=root/'.local/review/sequences'/run.name;out.mkdir(parents=True,exist_ok=True)
 captures=d['captures'];pages=[]
 for start in range(0,len(captures),4):
  chunk=captures[start:start+4];im=Image.new('RGB',(1200,850),(20,30,40));draw=ImageDraw.Draw(im);entries=[]
  for i,c in enumerate(chunk):
   x=(i%2)*600;y=(i//2)*425;name=c['screenshot'];origin=run/name
   title=f"{start+i+1:02} {name} | B/I/V/O={c.get('bodyPasses',0)}/{c.get('bodyItems',0)}/{c.get('vanillaItems',0)}/{c.get('otherItems',0)}"
   for j,line in enumerate(textwrap.wrap(title,64)):draw.text((x+4,y+3+j*18),line,font=font,fill='white')
   img=Image.open(origin).convert('RGB');img.thumbnail((596,356));im.paste(img,(x+(600-img.width)//2,y+68+(356-img.height)//2))
   entries.append(dict(captureIndex=start+i+1,source=str(origin.relative_to(root)),metadata=c))
  page=len(pages)+1;file=out/f'page-{page:03}.png';im.save(file);pages.append(dict(page=page,path=str(file.relative_to(root)),captures=entries))
 (out/'index.json').write_text(json.dumps(dict(run=run.name,source=str(run.relative_to(root)),status=d['status'],captures=len(captures),pages=pages),indent=2))
 print(run.name,len(captures),'images',len(pages),'pages')

"""CPU prompt coordinates, candidate checks and explicit segmentation review."""
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from .core import read_image, load_mask, digest

# Scene-specific seeds inspected on the native fairy source, NOT universal defaults.
PROMPTS = {
 'person': {'box':[0.,.285,1.,.735], 'positive':[[.49,.36],[.56,.32],[.72,.39],[.285,.432],[.34,.451],[.72,.465],[.48,.55],[.36,.65]],
            'negative':[[.5,.81],[.28,.86],[.78,.78],[.12,.20],[.08,.59],[.9,.65]]},
 'pearl': {'box':[0.,.665,1.,.985], 'positive':[[.5,.78],[.25,.86],[.63,.85]],
           'negative':[[.49,.36],[.5,.60],[.94,.69],[.95,.92],[.08,.65],[.72,.97]]}}

def pixels(prompt,size):
    w,h=size;b=prompt['box']
    if len(b)!=4 or not all(np.isfinite(b)) or not(0<=b[0]<b[2]<=1 and 0<=b[1]<b[3]<=1):raise ValueError('invalid prompt box')
    points=prompt['positive']+prompt['negative']
    if not prompt['positive'] or not prompt['negative']:raise ValueError('positive AND negative seeds required')
    if any(len(p)!=2 or not all(np.isfinite(p)) or not all(0<=v<1 for v in p) for p in points):raise ValueError('invalid normalized point')
    # SAM receives original-image XY floats; processor performs its own resize exactly once.
    return [b[0]*w,b[1]*h,b[2]*w,b[3]*h],[[x*w,y*h] for x,y in points],[1]*len(prompt['positive'])+[0]*len(prompt['negative'])

def check_mask(mask,prompt,size,name):
    w,h=size;a=np.asarray(mask)>0;errors=[]
    if a.shape!=(h,w):return {'passed':False,'errors':['native dimensions mismatch']}
    box,points,labels=pixels(prompt,size)
    area=float(a.mean())
    if not .005<area<.65:errors.append('empty/tiny or excessive image coverage')
    for (x,y),label in zip(points,labels):
        if bool(a[int(y),int(x)])!=bool(label):errors.append('positive seed missing' if label else 'negative seed included')
    inside=np.zeros_like(a);x0,y0,x1,y1=box;inside[int(y0):min(h,int(np.ceil(y1))),int(x0):min(w,int(np.ceil(x1)))]=True
    outside=float((a&~inside).sum()/max(1,a.sum()))
    if outside>.03:errors.append('substantial support outside target box')
    return {'name':name,'passed':not errors,'errors':errors,'area_fraction':area,'outside_box_fraction':outside,
            'semantic_quality_validated':False}

def previews(work,config):
    p=Path(work);src=read_image(p/'source.png','RGB');out=p/'segmentation-previews';out.mkdir(exist_ok=True)
    for name in ['person','pearl']:
        prompt=config['sam_prompts'][name];im=src.copy();d=ImageDraw.Draw(im);box,pts,labels=pixels(prompt,src.size)
        d.rectangle(box,outline='yellow',width=5)
        for (x,y),label in zip(pts,labels):d.ellipse((x-9,y-9,x+9,y+9),fill='lime' if label else 'red')
        im.save(out/f'{name}_prompts.png')
        for f in list(p.glob(f'{name}_candidate_*.png'))+[p/f'{name}_mask.png']:
            if not f.exists():continue
            a=np.array(read_image(f,'L'))>0
            if a.shape!=(src.height,src.width):continue
            rgb=np.array(src).copy();rgb[a]=(rgb[a]*.55+np.array([255,40,160])*.45).astype('uint8')
            Image.fromarray(rgb).save(out/f'{f.stem}_overlay.png')

def inspect(work,config):
    p=Path(work);size=read_image(p/'source.png','RGB').size;reports=[]
    for name in ['person','pearl']:
        f=p/f'{name}_mask.png'
        reports.append(check_mask(load_mask(f,size),config['sam_prompts'][name],size,name) if f.exists() else {'name':name,'passed':False,'errors':['no explicitly selected mask']})
    if all(r['passed'] for r in reports):
        a=load_mask(p/'person_mask.png',size)>0;b=load_mask(p/'pearl_mask.png',size)>0
        if (a&b).sum()/max(1,min(a.sum(),b.sum()))>.03:reports.append({'passed':False,'errors':['person/pearl excessive overlap']})
    result={'passed':all(r['passed'] for r in reports),'checks':reports,'human_approved':False}
    (p/'segmentation-check.json').write_text(json.dumps(result,indent=2));previews(p,config)
    return result

def fingerprint(work,config):
    p=Path(work)
    return {'files':{n:digest(p/n) for n in ['source.png','person_mask.png','pearl_mask.png']},'prompts':config['sam_prompts']}

def require_review(work,config):
    p=Path(work);r=json.loads((p/'segmentation-review.json').read_text())
    if not r.get('human_approved') or r.get('fingerprint')!=fingerprint(p,config) or not inspect(p,config)['passed']:raise RuntimeError('Segmentation not reviewed/current/plausible; stop before matting')

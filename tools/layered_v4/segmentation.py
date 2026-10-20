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
    from .review_policy import guard_generation
    guard_generation()
    p=Path(work);r=json.loads((p/'segmentation-review.json').read_text())
    if not r.get('human_approved') or r.get('fingerprint')!=fingerprint(p,config):raise RuntimeError('Segmentation not reviewed/current; stop before matting')
    measured=inspect(p,config) # retain actual failures even when target identity was manually approved
    from .approved_layers import BASIS,validate_selection
    if r.get('approval_basis')==BASIS:
        if r.get('selection_sha256')!=digest(p/'selection.json'):raise RuntimeError('selection record changed')
        validate_selection(p);return
    if not measured['passed']:raise RuntimeError('Segmentation not plausible; stop before matting')

# Person-only re-review: two deliberately bounded hypotheses, three SAM outputs each.
# These scene hints address reported background leakage; they are NOT approved masks.
# Expanded semantic coverage: one whole-person hypothesis plus an auditable union
# of independently prompted garment/body parts. Three paired multimasks each, six total.
_REVIEW_NEG=[[.10,.50],[.14,.54],[.25,.32],[.31,.285],[.08,.61],[.04,.67],[.50,.81],[.80,.80],[.96,.66],[.97,.61],[.96,.25]]
_REVIEW_POS=[[.49,.36],[.56,.32],[.75,.39],[.84,.36],[.30,.445],[.285,.432],
 [.50,.437],[.50,.48],[.48,.53],[.46,.58],[.72,.466],[.80,.49],[.92,.50],
 [.46,.63],[.35,.66],[.28,.68],[.20,.70],[.72,.55],[.73,.59],[.70,.62]]
PERSON_REVIEW_PROFILES=[
 {'id':'full-garment-v2','description':'补足白纱/腰腹/左下裙摆/腿脚正点；不以清理代替人物完整性',
  'box':[.10,.28,1.,.75],'positive':_REVIEW_POS,'negative':_REVIEW_NEG},
 {'id':'parts-union-v2','description':'头手发、白纱腰腹、裙摆、腿脚分别提示，逐索引并集，保留全部部件供审核',
  'box':[.10,.28,1.,.75],'positive':_REVIEW_POS,'negative':_REVIEW_NEG,
  'parts':[
   {'id':'head-hands-hair','box':[.23,.28,1.,.515],
    'positive':[[.49,.36],[.56,.32],[.75,.39],[.84,.36],[.30,.445],[.285,.432]],'negative':_REVIEW_NEG},
   {'id':'sleeve-torso','box':[.30,.405,1.,.625],
    'positive':[[.50,.437],[.50,.48],[.48,.53],[.46,.58],[.72,.466],[.80,.49],[.92,.50]],'negative':_REVIEW_NEG},
   {'id':'large-skirt','box':[.10,.50,.82,.75],
    'positive':[[.46,.63],[.35,.66],[.28,.68],[.20,.70],[.43,.60]],'negative':_REVIEW_NEG},
   {'id':'leg-foot','box':[.61,.485,.90,.665],
    'positive':[[.72,.55],[.73,.59],[.70,.62]],'negative':_REVIEW_NEG}]}]
# Sample-point completeness is diagnostic, not a substitute for semantic or alpha review.
PERSON_COVERAGE_PROBES={
 'white_sleeve':[[.72,.466],[.80,.49],[.92,.50]],'chest_waist':[[.50,.437],[.50,.48],[.48,.53],[.46,.58]],
 'large_skirt':[[.46,.63],[.35,.66],[.28,.68],[.20,.70]],'leg_foot':[[.72,.55],[.73,.59],[.70,.62]],
 'hair_tips':[[.75,.39],[.84,.36]]}

def union_part_candidates(parts):
    arrays=[np.asarray(p,dtype=bool) for p in parts]
    if not arrays or arrays[0].ndim!=3 or arrays[0].shape[0]!=3 or any(a.shape!=arrays[0].shape for a in arrays):raise ValueError('part candidates must share native (3,H,W) registration')
    return np.logical_or.reduce(arrays)

# Small diagnostic ROIs only; never erase pixels by these regions or clip candidates to a box.
PERSON_BACKGROUND_PROBES={'left_platform':[.02,.49,.16,.55],'blue_ring':[.17,.285,.32,.33],
                          'lower_left_reflection':[.015,.59,.115,.68],'right_rocks':[.92,.62,1.,.68]}

def person_metrics(mask,prompt,size,pearl):
    a=np.asarray(mask)>0;w,h=size;check=check_mask(a,prompt,size,'person')
    regions={}
    for key,(x0,y0,x1,y1) in PERSON_BACKGROUND_PROBES.items():
        region=a[int(y0*h):max(int(y0*h)+1,int(y1*h)),int(x0*w):max(int(x0*w)+1,int(x1*w))]
        regions[key]=float(region.mean())
    _,pts,labels=pixels(prompt,size)
    positives=[bool(a[int(y),int(x)]) for (x,y),label in zip(pts,labels) if label]
    overlap=float((a&(np.asarray(pearl)>0)).sum()/max(1,a.sum()))
    return {'basic':check,'positive_coverage':sum(positives)/len(positives),'background_roi_occupancy':regions,
            'pearl_overlap_fraction':overlap,'roi_mask_occupancy_not_accuracy':{key:float(a[int(r['box'][1]*h):max(int(r['box'][1]*h)+1,int(r['box'][3]*h)),int(r['box'][0]*w):max(int(r['box'][0]*w)+1,int(r['box'][2]*w))].mean()) for key,r in PERSON_COVERAGE_ROIS.items()},
            'coverage_by_region':{key:float(np.mean([a[min(h-1,int(y*h)),min(w-1,int(x*w))] for x,y in pts])) for key,pts in PERSON_COVERAGE_PROBES.items()},
            'semantic_quality_validated':False}

def person_review_order(candidates):
    # Inspection order only. Constraints and leakage precede completeness; IoU is deliberately ignored.
    return sorted(range(len(candidates)),key=lambda i:(
        not candidates[i]['metrics']['basic']['passed'],
        len(candidates[i]['metrics']['basic']['errors']),
        -min(candidates[i]['metrics'].get('coverage_by_region',{'legacy':1}).values()),
        candidates[i]['metrics']['pearl_overlap_fraction'],
        sum(candidates[i]['metrics']['background_roi_occupancy'].values()),
        -candidates[i]['metrics']['positive_coverage'],i))

PERSON_COVERAGE_ROIS={
 'white_sleeve':{'label':'白纱衣袖','box':[.58,.445,1.,.535]},
 'chest_waist':{'label':'胸口腰腹','box':[.40,.46,.61,.61]},
 'large_skirt':{'label':'主要裙摆','box':[.16,.58,.65,.735]},
 'leg_foot':{'label':'右腿脚部','box':[.65,.51,.84,.64]},
 'hair_tips':{'label':'头发末梢','box':[.66,.31,.94,.44]}}

"""Run with the selected interpreter: python -m tools.layered_v4.cli COMMAND --work ..."""
import argparse,json,sys
from pathlib import Path
import numpy as np
from .core import *

DEFAULT={'conditioning_dilation':16,'translucent_regions':{'person':[[.56,.43,1.,.58],[.22,.49,.65,.65]],'pearl':[]},'boxes':{'person':[.03,.27,1.,.77],'pearl':[0.,.63,.96,1.]},'person_trimap_radius':10,'pearl_trimap_radius':4,
 'matte_tile':768,'matte_overlap':128,'global_long_side':1024,'global_steps':30,'inpaint_tile':1024,'inpaint_overlap':192,
 'refine_steps':24,'refine_strength':.70,'seed':12345,
 'background_prompt':'Underwater fantasy cavern, organic flowing rock formations, warm golden caustic light and turquoise water, continuous natural environmental background, detailed stone and water only, no objects in foreground',
 'negative_prompt':'person, woman, man, face, hair, hands, fingers, human body, clothes, dress, fabric, veil, pearl, sphere, orb, text, watermark, duplicate objects',
 'rois':{'face':[.41,.32,.61,.42],'hair':[.40,.29,.94,.49],'fingers':[.23,.40,.50,.49],'veil':[.57,.43,1.,.59],
         'skirt':[.20,.51,.78,.69],'pearl':[.02,.65,.96,1.]}}

def check_config(c):
    for tile_key,overlap_key in [('matte_tile','matte_overlap'),('inpaint_tile','inpaint_overlap')]:
        tile=c[tile_key];overlap=c[overlap_key]
        if not isinstance(tile,int) or not 256<=tile<=1024 or not isinstance(overlap,int) or not 0<overlap<tile:raise ValueError('T4 tile bounds/overlap invalid')
    if not 512<=c['global_long_side']<=1024:raise ValueError('T4 global size must be 512..1024; output remains native size')
    if not isinstance(c.get('conditioning_dilation',16),int) or not 0<=c.get('conditioning_dilation',16)<=32:raise ValueError('conditioning dilation must be 0..32 pixels')
    if not 0<c['refine_strength']<=1:raise ValueError('invalid refine strength')
    for boxes in [list(c['boxes'].values()),list(c['rois'].values()),sum(c.get('translucent_regions',{}).values(),[])]:
        for b in boxes:
            if len(b)!=4 or not all(isinstance(v,(int,float)) and np.isfinite(v) for v in b) or not (0<=b[0]<b[2]<=1 and 0<=b[1]<b[3]<=1):raise ValueError('invalid normalized region')

def seal_masks(p):
    size=read_image(p/'source.png','RGB').size
    alphas={};cores={}
    for name in ['person','pearl']:
        a=load_mask(p/f'{name}_alpha.png',size);core=load_mask(p/f'{name}_protect.png',size)
        if not np.any(a>0) or not np.any(a==0):raise ValueError(f'{name} empty/fully opaque: edit masks')
        a[core>.5]=1;alphas[name]=a;cores[name]=(core>.5).astype(np.float32)
    overlap=(alphas['person']>0)&(alphas['pearl']>0)
    unknown_path=p/'person_unobserved.png'
    unknown=load_mask(unknown_path,size)>0 if unknown_path.exists() else np.zeros(overlap.shape,bool)
    unknown|=overlap
    # Never place the observed pearl into the underlying person cutout.
    alphas['person'][alphas['pearl']>0]=0;cores['person'][alphas['pearl']>0]=0
    save_mask(unknown_path,unknown)
    combined=np.zeros(overlap.shape,bool)
    for name in ['person','pearl']:
        save_mask(p/f'{name}_alpha.png',alphas[name]);save_mask(p/f'{name}_protect.png',cores[name])
        combined|=alphas[name]>0
    hp=p/'background_hole.png'
    if hp.exists():
        hole=load_mask(hp,size)>0
        if np.any(combined&~hole):raise ValueError('edited hole must contain alpha support')
    else:hole=combined
    save_mask(hp,hole)
    return review_fingerprint(p)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['init','preflight','lock-models','segment','matte','prepare-masks','approve-masks','background','build','approve-output','pack'])
    parser.add_argument('--work',required=True);parser.add_argument('--input');parser.add_argument('--output');parser.add_argument('--basic',action='store_true')
    parser.add_argument('--allow-known-person-occlusion',action='store_true');parser.add_argument('--reviewed',action='store_true');parser.add_argument('--draft',action='store_true');args=parser.parse_args()
    p=Path(args.work);p.mkdir(parents=True,exist_ok=True)
    config_path=p/'config.json';config=json.loads(config_path.read_text()) if config_path.exists() else DEFAULT
    check_config(config)
    if args.command=='init':
        if not args.input:parser.error('--input required')
        if (p/'input.json').exists():raise ValueError('Use a fresh work directory; do not overwrite an existing reviewed project')
        result=import_v1(args.input,p);config_path.write_text(json.dumps(config,ensure_ascii=False,indent=2))
    elif args.command in ['preflight','lock-models','segment','matte','background']:
        from . import models
        if args.command=='preflight':result=models.preflight(p,args.basic)
        elif args.command=='lock-models':result=models.lock_models(p)
        elif args.command=='segment':result=models.segment(p,config)
        elif args.command=='matte':result=models.matte(p,config)
        else:result=models.background(p,config)
    elif args.command=='prepare-masks':result={'mask_hashes':seal_masks(p),'approved':False}
    elif args.command=='approve-masks':
        if not args.reviewed:raise ValueError('Inspect masks/alpha/protected cores first; --reviewed is an explicit human acknowledgement')
        hashes=seal_masks(p)
        result={'mask_hashes':hashes,'masks_approved':True,'accepted':{},'note':'mask approval is not final visual acceptance'}
        (p/'review.json').write_text(json.dumps(result,indent=2))
    elif args.command=='build':result=build(p,config)
    elif args.command=='approve-output':
        if not args.reviewed:raise ValueError('Inspect all ROI, alpha backgrounds and shift previews before --reviewed')
        result=json.loads((p/'review.json').read_text())
        if result['mask_hashes']!=review_fingerprint(p):raise ValueError('masks changed since review')
        result['accepted']={k:True for k in ['segmentation','hair','face_fingers','veil_alpha','skirt','pearl','background_no_ghosts','shift_previews','alpha_on_black_white']}
        result['accepted']['occluded_person_known_limit']=args.allow_known_person_occlusion
        result['output_hashes']={n:digest(p/n) for n in ['background.png','person_rgba.png','pearl_rgba.png']}
        (p/'review.json').write_text(json.dumps(result,indent=2))
    else:
        if not args.output:parser.error('--output required')
        result=pack(p,args.output,require_acceptance=not args.draft)
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()

"""CPU image math, safe V1 input, editable masks and experimental spatial-v2 packaging."""
from __future__ import annotations
import hashlib, io, json, math, zipfile
from pathlib import Path, PurePosixPath
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi

SCHEMA='floatdeck-spatial-layered-experimental'
MAX_PIXELS=32_000_000
MAX_BYTES=256*1024*1024

def digest(path):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for b in iter(lambda:f.read(1<<20),b''): h.update(b)
    return h.hexdigest()

def read_image(path, mode):
    with Image.open(path) as im:
        if not 0<im.width*im.height<=MAX_PIXELS: raise ValueError('image pixel limit')
        return im.convert(mode)

def import_v1(archive, work):
    work=Path(work);work.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        names=z.namelist()
        if len(names)!=len(set(names)) or len(names)>32: raise ValueError('duplicate/excess archive entries')
        for e in z.infolist():
            p=PurePosixPath(e.filename)
            if p.is_absolute() or '..' in p.parts or '\\' in e.filename or e.flag_bits&1 or (e.external_attr>>16)&0o170000==0o120000: raise ValueError('unsafe archive')
        if sum(e.file_size for e in z.infolist())>MAX_BYTES: raise ValueError('archive size limit')
        if z.getinfo('manifest.json').file_size>16384: raise ValueError('manifest too large')
        m=json.loads(z.read('manifest.json'))
        if m.get('format')!='floatdeck-spatial' or m.get('version')!=1 or m.get('depthDirection')!='white-near': raise ValueError('requires ZIP V1 white-near')
        images=[]
        for key,mode in [('image','RGB'),('depth','L')]:
            name=m['assets'][key]
            if name not in names: raise ValueError('missing asset')
            with Image.open(io.BytesIO(z.read(name))) as im:
                if not 0<im.width*im.height<=MAX_PIXELS: raise ValueError('pixel limit')
                if im.getexif().get(274,1)!=1: raise ValueError('non-upright EXIF: register image/depth explicitly before import')
                if key=='image' and im.info.get('icc_profile'):
                    from PIL import ImageCms
                    canonical=ImageCms.profileToProfile(im.convert('RGB'),ImageCms.ImageCmsProfile(io.BytesIO(im.info['icc_profile'])),ImageCms.createProfile('sRGB'),outputMode='RGB')
                    canonical.info.clear();images.append(canonical)
                else:
                    if key=='depth' and im.mode not in ['L','RGB','RGBA','P']:raise ValueError('depth must be an 8-bit white-near map')
                    images.append(im.convert(mode))
        if images[0].size!=images[1].size: raise ValueError('image/depth registration mismatch')
        for im,name in zip(images,['source.png','source_depth.png']): im.save(work/name)
    identity={'zip_sha256':digest(archive),'source_sha256':digest(work/'source.png'),
              'depth_sha256':digest(work/'source_depth.png'),'size':list(images[0].size),'original_manifest':m}
    (work/'input.json').write_text(json.dumps(identity,ensure_ascii=False,indent=2))
    return identity

def load_mask(path,size):
    with Image.open(path) as im:
        if im.size!=size: raise ValueError(f'mask must keep exact coordinates: {path}')
        if im.mode!='L': raise ValueError(f'mask must be 8-bit L PNG: {path}')
        return np.array(im,dtype=np.float32)/255

def trimap(mask,radius=8):
    foreground=mask>.5
    core=ndi.binary_erosion(foreground,iterations=radius,border_value=1)
    possible=ndi.binary_dilation(foreground,iterations=radius)
    out=np.full(mask.shape,128,dtype=np.uint8);out[~possible]=0;out[core]=255
    return out

def save_mask(path,a):
    Image.fromarray(np.rint(np.clip(a,0,1)*255).astype('uint8'),'L').save(path)

def over(bg,rgba):
    a=rgba[...,3:4]/255.
    return rgba[...,:3]*a+bg*(1-a)

def compose(bg,person,pearl): return over(over(bg,person),pearl)

def unmix(original,bg,alpha,protect=None):
    """Recover STRAIGHT foreground color only for partial alpha; opaque RGB stays original.
    Single-image matting is ambiguous: report clipped colors/reconstruction residual, never claim truth.
    """
    a=np.clip(alpha,0,1).astype(np.float32)
    if protect is not None:a=np.where(protect>.5,1,a)
    safe=np.maximum(a[...,None],1/255)
    recovered=(original.astype(np.float32)-(1-a[...,None])*bg.astype(np.float32))/safe
    clipped=((recovered<0)|(recovered>255)).any(axis=2)&(a>0)&(a<1)
    rgb=np.clip(recovered,0,255)
    rgb[a>=1]=original[a>=1];rgb[a==0]=0
    rgba=np.concatenate([np.rint(rgb).astype(np.uint8),np.rint(a[...,None]*255).astype(np.uint8)],axis=2)
    return rgba,int(clipped.sum())

def translate(array,dx,dy,fill=0):
    out=np.full_like(array,fill);h,w=array.shape[:2]
    x0=max(0,dx);x1=min(w,w+dx);y0=max(0,dy);y1=min(h,h+dy)
    if x0<x1 and y0<y1:out[y0:y1,x0:x1]=array[y0-dy:y1-dy,x0-dx:x1-dx]
    return out

def previews(work,bg,person,pearl,source,rois):
    work=Path(work);out=work/'previews';out.mkdir(exist_ok=True)
    neutral=compose(bg,person,pearl)
    Image.fromarray(np.rint(neutral).clip(0,255).astype('uint8')).save(out/'zero-displacement.png')
    Image.fromarray(source).save(out/'original.png')
    Image.fromarray(np.clip(abs(neutral-source)*4,0,255).astype('uint8')).save(out/'zero-error-x4.png')
    h,w=bg.shape[:2]
    for name,dp,dl in [('person-horizontal',(.06,0),(0,0)),('pearl-horizontal',(0,0),(.09,0)),
                        ('separate-diagonal',(.06,.035),(-.09,-.05))]:
        p=translate(person,round(dp[0]*w),round(dp[1]*w));l=translate(pearl,round(dl[0]*w),round(dl[1]*w))
        Image.fromarray(np.rint(compose(bg,p,l)).clip(0,255).astype('uint8')).save(out/f'{name}.png')
    checker=np.indices((h,w)).sum(axis=0)//16%2*80+120
    checker=np.repeat(checker[...,None],3,axis=2)
    for name,layer in [('person',person),('pearl',pearl)]:
        for matte,color in [('black',0),('white',255),('checker',None)]:
            base=checker if color is None else np.full_like(bg,color)
            Image.fromarray(np.rint(over(base,layer)).clip(0,255).astype('uint8')).save(out/f'{name}-on-{matte}.png')
    overlay=source.astype(float)*.5
    overlay[...,1]+=person[...,3]*.5;overlay[...,0]+=pearl[...,3]*.5
    Image.fromarray(overlay.clip(0,255).astype('uint8')).save(out/'mask-overlay.png')
    unknown=np.array(read_image(work/'person_unobserved.png','L'))>0
    if unknown.any():
        shifted=translate(unknown,round(.06*w),round(.035*w))
        front=translate(pearl[...,3],round(-.09*w),round(-.05*w))>0
        diagnostic=np.rint(compose(bg,translate(person,round(.06*w),round(.035*w)),translate(pearl,round(-.09*w),round(-.05*w)))).clip(0,255).astype('uint8')
        diagnostic[shifted&~front]=checker[shifted&~front].astype('uint8')
        Image.fromarray(diagnostic).save(out/'separate-diagonal-unknown-person.png')
    for name,box in rois.items():
        x0,y0,x1,y1=[round(v*(w if i%2==0 else h)) for i,v in enumerate(box)]
        if not (0<=x0<x1<=w and 0<=y0<y1<=h):raise ValueError('invalid normalized ROI')
        tiles=[]
        for im in [source,neutral,bg,over(checker,person),over(checker,pearl)]:
            tiles.append(Image.fromarray(np.rint(im[y0:y1,x0:x1]).clip(0,255).astype('uint8')).resize((480,480),Image.Resampling.NEAREST))
        sheet=Image.new('RGB',(480*5,510),(35,35,35));draw=ImageDraw.Draw(sheet)
        for i,(tile,label) in enumerate(zip(tiles,['original','recompose','background','person alpha','pearl alpha'])):
            sheet.paste(tile,(i*480,30));draw.text((i*480+8,8),label,fill='white')
        sheet.save(out/f'roi-{name}.png')

def review_fingerprint(work):
    work=Path(work)
    files=['source.png','source_depth.png','person_alpha.png','pearl_alpha.png','person_protect.png','pearl_protect.png','person_unobserved.png','background_hole.png']
    return {n:digest(work/n) for n in files}

def build(work,config):
    work=Path(work);source=np.array(read_image(work/'source.png','RGB'));size=(source.shape[1],source.shape[0])
    provenance=json.loads((work/'background_record.json').read_text())
    if provenance.get('provider')=='sdxl-inpaint-global-and-native-tiles':
        if not json.loads((work/'inpaint-execution.json').read_text()).get('succeeded'):raise ValueError('latest inpaint execution failed; do not reuse stale background')
    bg=np.array(read_image(work/'background.png','RGB'))
    if bg.shape!=source.shape:raise ValueError('background size mismatch')
    pa=load_mask(work/'person_alpha.png',size);la=load_mask(work/'pearl_alpha.png',size)
    pp=load_mask(work/'person_protect.png',size);lp=load_mask(work/'pearl_protect.png',size)
    # Original pearl is the foremost layer. Never generate its RGB. Any partial alpha is explicitly reviewed.
    hole=load_mask(work/'background_hole.png',size)>0
    pa=np.where(pp>.5,1,pa);la=np.where(lp>.5,1,la)
    if np.any(((pa>0)|(la>0))&~hole):raise ValueError('approved hole must cover all foreground alpha support')
    if np.any(bg[~hole]!=source[~hole]):raise ValueError('visible pixels outside approved hole changed')
    # Person unmixes against generated background. Pearl unmixes against the person-over-background stack.
    person,pc=unmix(source,bg,pa,pp)
    below=over(bg,person)
    pearl,lc=unmix(source,below,la,lp)
    for n,a in [('person_rgba.png',person),('pearl_rgba.png',pearl)]:Image.fromarray(a,'RGBA').save(work/n)
    save_mask(work/'person_alpha.png',person[...,3]/255);save_mask(work/'pearl_alpha.png',pearl[...,3]/255)
    # Copy reference depth, not hallucinated hidden-background geometry.
    depth=read_image(work/'source_depth.png','L');depth.save(work/'person_depth.png');depth.save(work/'pearl_depth.png')
    recomposed=compose(bg,person,pearl);err=np.abs(recomposed-source)
    visible=(pa==0)&(la==0)
    report={'synthetic':False,'model_quality_validated':False,'zero_mae':float(err.mean()),'zero_p99':float(np.percentile(err,99)),
            'zero_max':float(err.max()),'zero_bad_fraction':float((err.max(axis=2)>8).mean()),
            'person_unmix_clipped_pixels':pc,'pearl_unmix_clipped_pixels':lc,
            'visible_pixels_unchanged':bool(np.all(bg[~hole]==source[~hole])),
            'opaque_person_rgb_exact':bool(np.array_equal(person[pp>.5,:3],source[pp>.5])),
            'opaque_pearl_rgb_exact':bool(np.array_equal(pearl[lp>.5,:3],source[lp>.5])),
            'pearl_touches_frame':bool(np.any(la[-1]>0) or np.any(la[0]>0) or np.any(la[:,0]>0) or np.any(la[:,-1]>0)),
            'visible_reference_pixels':int(visible.sum()),
            'person_occluded_pixels':int((load_mask(work/'person_unobserved.png',size)>0).sum()),
            'preview_motion_width_fractions':{'person_horizontal':[.06,0],'pearl_horizontal':[.09,0],'separate_diagonal_person':[.06,.035],'separate_diagonal_pearl':[-.09,-.05]}}
    (work/'quality.json').write_text(json.dumps(report,indent=2))
    previews(work,bg,person,pearl,source,config['rois'])
    return report

def pack(work,destination,require_acceptance=True):
    work=Path(work);q=json.loads((work/'quality.json').read_text());identity=json.loads((work/'input.json').read_text())
    if identity['source_sha256']!=digest(work/'source.png') or identity['depth_sha256']!=digest(work/'source_depth.png'):raise ValueError('canonical input changed')
    approvals=json.loads((work/'review.json').read_text())
    if approvals.get('mask_hashes')!=review_fingerprint(work):raise ValueError('mask review missing or stale')
    background_record=json.loads((work/'background_record.json').read_text())
    if background_record['mask_sha256']!=digest(work/'background_hole.png') or background_record['background_sha256']!=digest(work/'background.png'):raise ValueError('background provenance stale')
    if require_acceptance:
        checks=['segmentation','hair','face_fingers','veil_alpha','skirt','pearl','background_no_ghosts','shift_previews','alpha_on_black_white']
        if not all(approvals.get('accepted',{}).get(k) is True for k in checks):raise ValueError('human quality acceptance incomplete; draft only')
        if approvals.get('output_hashes')!={n:digest(work/n) for n in ['background.png','person_rgba.png','pearl_rgba.png']}:raise ValueError('output review stale')
        if q['person_occluded_pixels'] and not approvals.get('accepted',{}).get('occluded_person_known_limit'):raise ValueError('hidden person behind pearl is unknown; explicit acceptance required')
        if background_record.get('provider')!='sdxl-inpaint-global-and-native-tiles' or background_record.get('executed') is not True:raise ValueError('actual model inpainting required; no copied/test background')
        execution=json.loads((work/'inpaint-execution.json').read_text())
        if not execution.get('succeeded'):raise ValueError('inpainting did not complete successfully')
        if q['zero_bad_fraction']>.02 or not q['opaque_person_rgb_exact'] or not q['opaque_pearl_rgb_exact']:raise ValueError('reconstruction/protected pixels failed')
    files=['source.png','source_depth.png','background.png','background_hole.png','person_rgba.png','pearl_rgba.png',
           'person_alpha.png','pearl_alpha.png','person_protect.png','pearl_protect.png','person_unobserved.png','person_depth.png','pearl_depth.png',
           'quality.json','review.json','background_record.json','input.json']
    files += [n for n in ['models.lock.json','preflight.json','segment-execution.json','matte-execution.json','inpaint-execution.json'] if (work/n).is_file()]
    files += [str(p.relative_to(work)) for p in sorted((work/'previews').glob('*.png'))]
    manifest={'format':SCHEMA,'version':2,'profile':'spatial-v2-draft','experimental':True,'not_for_android_v1':True,
        'quality_status':'human-reviewed-experimental' if require_acceptance else 'UNREVIEWED-DRAFT',
        'width':identity['size'][0],'height':identity['size'][1],'orientation':'upright','color_space':'sRGB',
        'alpha':'straight-unassociated','composition':'background -> person -> pearl; source-over in sRGB',
        'background':{'image':'background.png','hole_mask':'background_hole.png','z_order':0,'depth':'unknown-behind-occluders'},
        'layers':[{'id':n,'image':n+'_rgba.png','alpha_mask':n+'_alpha.png','depth':n+'_depth.png','z_order':i+1,
                   'depth_validity':'observed-source-reference-only; alpha=0 depth invalid'} for i,n in enumerate(['person','pearl'])],
        'known_limits':{'pearl_clipped_by_original_frame':q['pearl_touches_frame'],'occluded_person_mask':'person_unobserved.png',
                        'hidden_person_texture':'unknown; never synthesized or copied from pearl'},
        'source':{'image':'source.png','depth':'source_depth.png','zip_sha256':identity['zip_sha256']},
        'assets':{n:{'sha256':digest(work/n),'bytes':(work/n).stat().st_size} for n in files}}
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
        for n in files:z.write(work/n,n)
    return manifest

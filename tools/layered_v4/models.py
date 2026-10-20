"""GPU stages. Each CLI invocation owns one model and exits to release CUDA memory."""
import gc,json,os,sys,time,shutil,subprocess
from pathlib import Path
import numpy as np
from PIL import Image
from .core import read_image,load_mask,save_mask,trimap,digest,restore_visible

DEFAULT_MODELS={'segment':'facebook/sam-vit-base','matte':'hustvl/vitmatte-small-composition-1k',
                'inpaint':'diffusers/stable-diffusion-xl-1.0-inpainting-0.1'}

def preflight(work,basic=False):
    work=Path(work);work.mkdir(parents=True,exist_ok=True)
    report={'python':sys.executable,'version':sys.version,'basic_only':basic,'passed':False,'gpu_probe_passed':False}
    try:
        report['stage']='python'
        if sys.version_info[:2]!=(3,12):raise RuntimeError(f'Use the selected Python 3.12 environment, got {sys.executable}: {sys.version}')
        report['stage']='cache'
        cache=Path(os.environ.get('HF_HOME',str(work/'model-cache')))
        report.update(cache=str(cache),cache_writable=False)
        cache.mkdir(parents=True,exist_ok=True)
        probe=cache/'.write-probe';probe.write_text('ok');probe.unlink()
        report.update({'python':sys.executable,'version':sys.version,'cache':str(cache),'cache_writable':True,
                'free_disk_gib':shutil.disk_usage(cache).free/2**30,'nvidia_smi':shutil.which('nvidia-smi')})
        repo=Path(__file__).resolve().parents[2]
        commit=subprocess.run(['git','-C',str(repo),'rev-parse','HEAD'],capture_output=True,text=True)
        report['generator_commit']=commit.stdout.strip() if commit.returncode==0 else 'unavailable'
        if report['nvidia_smi']:
            try:
                p=subprocess.run([report['nvidia_smi'],'--query-gpu=name,memory.total,driver_version','--format=csv,noheader'],capture_output=True,text=True,timeout=15)
                report['nvidia_smi_result']=p.stdout.strip() if p.returncode==0 else 'unavailable; use torch CUDA probe'
            except (OSError,subprocess.TimeoutExpired):report['nvidia_smi_result']='unavailable; use torch CUDA probe'
        else:report['nvidia_smi_result']='command absent; not itself a CUDA failure'
        if not basic:
            report['stage']='dependencies'
            import importlib.metadata as md
            required=['numpy','torch','torchvision','transformers','diffusers','accelerate','safetensors','huggingface-hub','addict','scipy','Pillow']
            report['dependencies']={n:md.version(n) for n in required}
            report['stage']='dependency-imports'
            import addict,torch,torchvision
            import transformers,diffusers,accelerate,safetensors,huggingface_hub,scipy
            report['stage']='pip-check'
            pip_result=subprocess.run([sys.executable,'-m','pip','check'],capture_output=True,text=True)
            report['pip_check']={'returncode':pip_result.returncode,'stdout':pip_result.stdout,'stderr':pip_result.stderr}
            if pip_result.returncode:raise RuntimeError('Dependency conflicts: '+pip_result.stdout+pip_result.stderr)
            report['stage']='cuda'
            report['torch_cuda']=torch.version.cuda
            report['cuda_available']=torch.cuda.is_available()
            if not torch.cuda.is_available():raise RuntimeError('CUDA unavailable in selected interpreter; select Colab T4 GPU, not CPU runtime')
            p=torch.cuda.get_device_properties(0);free,total=torch.cuda.mem_get_info()
            report.update(gpu=p.name,total_vram_gib=total/2**30,free_vram_gib=free/2**30,torch_cuda=torch.version.cuda)
            report['stage']='vram'
            if total<14*2**30 or free<10*2**30:raise RuntimeError(f'Need >=14 GiB total / >=10 GiB free VRAM; observed total={total/2**30:.3f} GiB, free={free/2**30:.3f} GiB. Stop other GPU models or restart T4 runtime')
            report['stage']='disk'
            if report['free_disk_gib']<20:raise RuntimeError(f"Need >=20 GiB free cache/output disk; observed {report['free_disk_gib']:.3f} GiB at {cache}")
            report['stage']='cuda-torchvision-operator'
            # Tiny actual CUDA operation detects unusable runtime or mismatched torchvision extensions.
            boxes=torch.tensor([[0.,0.,1.,1.]],device='cuda')
            torchvision.ops.nms(boxes,torch.ones(1,device='cuda'),.5)
            torch.cuda.synchronize()
            del boxes
            report['gpu_probe_passed']=True
        report['passed']=True;report['stage']='complete'
        return report
    except Exception as exc:
        report['error_type']=type(exc).__name__;report['error']=str(exc)
        import traceback
        report['traceback']=traceback.format_exc()
        raise
    finally:
        # Overwrite stale success even on failure; traceback still reaches subprocess stderr.
        (work/'preflight.json').write_text(json.dumps(report,indent=2))

def lock_models(work):
    from huggingface_hub import model_info
    lock={k:{'id':v,'revision':model_info(v).sha} for k,v in DEFAULT_MODELS.items()}
    (Path(work)/'models.lock.json').write_text(json.dumps(lock,indent=2));return lock

def model_spec(work,key):
    p=Path(work)/'models.lock.json'
    if not p.is_file():raise RuntimeError('Run lock-models first; model commits must be recorded')
    spec=json.loads(p.read_text())[key]
    if len(spec['revision'])!=40:raise RuntimeError('unresolved model revision')
    return spec

def gpu_stage(work,name,fn):
    import torch
    preflight(work);torch.cuda.reset_peak_memory_stats()
    start=time.time()
    succeeded=False
    try:
        result=fn();succeeded=True
    finally:
        gc.collect();torch.cuda.empty_cache()
        record={'stage':name,'seconds':time.time()-start,'peak_allocated_gib':torch.cuda.max_memory_allocated()/2**30,
                'peak_reserved_gib':torch.cuda.max_memory_reserved()/2**30,'executed':True,'succeeded':succeeded}
        (Path(work)/f'{name}-execution.json').write_text(json.dumps(record,indent=2))
    return result

def segment(work,config,targets=('person','pearl'),prompt_sets=None):
    if not targets or any(n not in ['person','pearl'] for n in targets):raise ValueError('invalid segmentation targets')
    if prompt_sets is not None and tuple(targets)!=('person',):raise ValueError('review prompt sets are person-only')
    workp=Path(work)
    if 'pearl' in targets and (workp/'fixed-pearl.json').exists():raise RuntimeError('Approved pearl is locked; run person-only review, never resegment pearl')
    # Invalidate stale downstream approvals before any rerun, even if CUDA fails.
    for f in ['segmentation-review.json','review.json']+[f'{n}_mask.png' for n in targets]:
        (workp/f).unlink(missing_ok=True)
    for name in targets:
        for f in list(workp.glob(f'{name}_candidate_*.png'))+[workp/f'{name}_proposal.json']:
            f.unlink(missing_ok=True)
    shutil.rmtree(workp/'segmentation-previews',ignore_errors=True)
    from .segmentation import pixels,check_mask,previews
    def run():
        import torch
        from transformers import SamModel,SamProcessor
        workp=Path(work);spec=model_spec(work,'segment')
        image=read_image(workp/'source.png','RGB');w,h=image.size
        processor=SamProcessor.from_pretrained(spec['id'],revision=spec['revision'])
        model=SamModel.from_pretrained(spec['id'],revision=spec['revision'],torch_dtype=torch.float32).to('cuda').eval()
        for name in targets:
            prompts=prompt_sets if prompt_sets is not None else [config['sam_prompts'][name]]
            scores_all=[];checks_all=[];parameters=[]
            for profile_index,prompt in enumerate(prompts):
                part_masks=[];part_scores=[];part_parameters=[]
                parts=prompt.get('parts',[prompt])
                for part_index,part in enumerate(parts):
                    box,points,labels=pixels(part,image.size)
                    inputs=processor(images=image,input_boxes=[[box]],input_points=[[points]],input_labels=[[labels]],return_tensors='pt')
                    original=inputs['original_sizes'];reshaped=inputs['reshaped_input_sizes']
                    inputs={k:v.to('cuda') for k,v in inputs.items() if k not in ['original_sizes','reshaped_input_sizes']}
                    with torch.inference_mode():output=model(**inputs,multimask_output=True)
                    masks=processor.image_processor.post_process_masks(output.pred_masks.cpu(),original,reshaped)[0][0].numpy()
                    scores=output.iou_scores[0,0].float().cpu().tolist()
                    if masks.shape!=(3,h,w) or len(scores)!=3 or not np.isfinite(scores).all():
                        raise RuntimeError('SAM candidates must be 3 native HxW masks with finite scores; no resize fallback')
                    part_masks.append(masks);part_scores.append(scores)
                    part_parameters.append({'box':box,'points':points,'labels':labels,'prompt':part})
                    if 'parts' in prompt:
                        folder=workp/'person_parts';folder.mkdir(exist_ok=True)
                        for i,m in enumerate(masks):save_mask(folder/f'profile{profile_index}_part{part_index}_candidate{i}.png',m)
                if 'parts' in prompt:
                    from .segmentation import union_part_candidates
                    masks=union_part_candidates(part_masks)
                    scores=[None]*3 # SAM never scored the union; do not invent an IoU for it.
                else:masks=part_masks[0];scores=part_scores[0]
                for i,m in enumerate(masks):save_mask(workp/f'{name}_candidate_{profile_index*3+i}.png',m)
                scores_all.extend(scores)
                checks_all.extend(check_mask(m,prompt,image.size,name) for m in masks)
                parameters.extend({'profile_index':profile_index,'prompt':prompt,'parts':part_parameters,
                                   'part_iou_scores':part_scores,'composition':'paired-index-union' if 'parts' in prompt else 'single-SAM-output'} for _ in masks)
            (workp/f'{name}_proposal.json').write_text(json.dumps({'scores':scores_all,'checks':checks_all,
                 'parameters':parameters,'selected_index':None,'accepted':False,
                 'warning':'Inspection only; IoU is not identity. No automatic selection.'},indent=2))
        previews(workp,config)
        del model,processor
    return gpu_stage(work,'segment',run)

def starts(length,tile,overlap):
    if length<=tile:return [0]
    values=list(range(0,length-tile+1,tile-overlap))
    if values[-1]!=length-tile:values.append(length-tile)
    return values

def matte(work,config):
    from .review_policy import guard_generation
    guard_generation() # latest Reject blocks before any GPU/model loading
    from .segmentation import require_review
    require_review(work,config) # fail BEFORE importing/loading GPU models
    def run():
        import torch
        from transformers import VitMatteForImageMatting,VitMatteImageProcessor
        p=Path(work);spec=model_spec(work,'matte');image=read_image(p/'source.png','RGB');w,h=image.size
        processor=VitMatteImageProcessor.from_pretrained(spec['id'],revision=spec['revision'])
        model=VitMatteForImageMatting.from_pretrained(spec['id'],revision=spec['revision']).to('cuda').eval()
        for name,radius in [('person',config['person_trimap_radius']),('pearl',config['pearl_trimap_radius'])]:
            binary=load_mask(p/f'{name}_mask.png',image.size)
            tp=p/f'{name}_trimap.png'
            if tp.exists():
                tri=np.array(read_image(tp,'L'))
                if tri.shape!=(h,w) or not set(np.unique(tri)).issubset({0,128,255}):raise ValueError('trimap must be native size, values 0/128/255')
            else:
                tri=trimap(binary,radius)
                # Scene-specific uncertain cloth regions, not asserted transparency.
                # Manual trimaps take precedence. Never force all white cloth opaque.
                for region in config.get('translucent_regions',{}).get(name,[]):
                    x0,y0,x1,y1=[round(v*(w if i%2==0 else h)) for i,v in enumerate(region)]
                    view=tri[y0:y1,x0:x1];view[view!=0]=128
                Image.fromarray(tri,'L').save(tp)
            acc=np.zeros((h,w),np.float32);weight=np.zeros((h,w),np.float32)
            tile=config['matte_tile'];overlap=config['matte_overlap']
            for y in starts(h,tile,overlap):
                for x in starts(w,tile,overlap):
                    box=(x,y,min(w,x+tile),min(h,y+tile));t=tri[y:box[3],x:box[2]]
                    if not np.any(t==128):alpha=t.astype(np.float32)/255
                    else:
                        data=processor(images=image.crop(box),trimaps=Image.fromarray(t,'L'),return_tensors='pt')
                        with torch.inference_mode():alpha=model(**{k:v.to('cuda') for k,v in data.items()}).alphas[0,0].float().cpu().numpy()[:t.shape[0],:t.shape[1]]
                        if alpha.shape!=t.shape:raise RuntimeError('matting model changed pixel registration')
                    win=np.outer(np.maximum(np.hanning(t.shape[0]),.05),np.maximum(np.hanning(t.shape[1]),.05)).astype(np.float32)
                    acc[y:box[3],x:box[2]]+=alpha*win;weight[y:box[3],x:box[2]]+=win
            alpha=acc/np.maximum(weight,1e-8);alpha[tri==0]=0;alpha[tri==255]=1
            save_mask(p/f'{name}_alpha.png',alpha)
            # Editable protected cores preserve source identity and glossy opaque pearl texture.
            protect=p/f'{name}_protect.png'
            if not protect.exists():save_mask(protect,tri==255)
        del model,processor
    return gpu_stage(work,'matte',run)

def background(work,config):
    from .review_policy import guard_generation
    guard_generation() # latest Reject blocks before any GPU/model loading
    def run():
        import torch
        from diffusers import AutoPipelineForInpainting
        from .core import review_fingerprint
        p=Path(work);review=json.loads((p/'review.json').read_text())
        if review.get('mask_hashes')!=review_fingerprint(work) or not review.get('masks_approved'):raise RuntimeError('review current alpha/protect/background-hole masks before AI inpainting')
        spec=model_spec(work,'inpaint');source=read_image(p/'source.png','RGB');hole=read_image(p/'background_hole.png','L')
        pipe=AutoPipelineForInpainting.from_pretrained(spec['id'],revision=spec['revision'],variant='fp16',torch_dtype=torch.float16,
                                                      use_safetensors=True,low_cpu_mem_usage=True)
        pipe.enable_model_cpu_offload();pipe.enable_attention_slicing();pipe.enable_vae_tiling()
        w,h=source.size
        scale=min(1,config['global_long_side']/max(w,h));sw=max(64,round(w*scale/8)*8);sh=max(64,round(h*scale/8)*8)
        from scipy import ndimage as ndi
        approved=np.array(hole)>0
        radius=config.get('conditioning_dilation',16)
        expanded=ndi.binary_dilation(approved,iterations=radius) if radius else approved.copy()
        # Diffusion uses a latent grid: tiny hair holes can disappear if fed literally.
        # Expand conditioning ONLY. Published background still changes approved pixels ONLY.
        conditioning=Image.fromarray((expanded*255).astype('uint8'),'L')
        generator=torch.Generator('cuda').manual_seed(config['seed'])
        kwargs=dict(prompt=config['background_prompt'],negative_prompt=config['negative_prompt'],guidance_scale=6,
                    generator=generator,num_inference_steps=config['global_steps'])
        with torch.inference_mode():
            global_bg=pipe(image=source.resize((sw,sh),Image.Resampling.LANCZOS),mask_image=conditioning.resize((sw,sh),Image.Resampling.NEAREST),
                           strength=1.,width=sw,height=sh,**kwargs).images[0].resize((w,h),Image.Resampling.LANCZOS)
        global_array=np.array(global_bg,dtype=np.float32);src=np.array(source);mask=np.array(hole)>0
        base=np.where(mask[...,None],global_array,src).astype(np.uint8)
        # High-resolution tiled model refinement. Blending is ONLY among AI proposals INSIDE
        # approved occlusion, not copying/blurring observed foreground into hidden background.
        acc=np.zeros((h,w,3),np.float32);weight=np.zeros((h,w),np.float32)
        tile=config['inpaint_tile'];overlap=config['inpaint_overlap']
        for y in starts(h,tile,overlap):
            for x in starts(w,tile,overlap):
                x1=min(w,x+tile);y1=min(h,y+tile);local=mask[y:y1,x:x1]
                if not local.any():continue
                im=Image.fromarray(base[y:y1,x:x1]);mh=conditioning.crop((x,y,x1,y1))
                # Pad instead of rescaling so all tile pixels stay registered.
                tw=(im.width+7)//8*8;th=(im.height+7)//8*8
                padded=Image.new('RGB',(tw,th));padded.paste(im);pm=Image.new('L',(tw,th));pm.paste(mh)
                with torch.inference_mode():
                    result=pipe(image=padded,mask_image=pm,width=tw,height=th,prompt=config['background_prompt'],
                        negative_prompt=config['negative_prompt'],strength=config['refine_strength'],guidance_scale=6,
                        num_inference_steps=config['refine_steps'],generator=generator).images[0]
                proposal=np.array(result)[:im.height,:im.width,:3].astype(np.float32)
                win=np.outer(np.maximum(np.hanning(im.height),.05),np.maximum(np.hanning(im.width),.05)).astype(np.float32)
                acc[y:y1,x:x1]+=proposal*win[...,None];weight[y:y1,x:x1]+=win
        generated=acc/np.maximum(weight[...,None],1e-8)
        if np.any(mask&(weight==0)):raise RuntimeError('inpainting tile coverage incomplete')
        final=restore_visible(src,generated,mask)
        Image.fromarray(final,'RGB').save(p/'background.png')
        (p/'background_record.json').write_text(json.dumps({'provider':'sdxl-inpaint-global-and-native-tiles','model':spec,
            'seed':config['seed'],'mask_sha256':digest(p/'background_hole.png'),'background_sha256':digest(p/'background.png'),
            'only_mask_written':True,'conditioning_dilation_pixels':radius,'conditioning_is_not_output_permission':True,'config':config,'executed':True},indent=2))
        del pipe
    return gpu_stage(work,'inpaint',run)

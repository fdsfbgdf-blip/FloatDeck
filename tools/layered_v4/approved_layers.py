"""Explicit user selection (person 5, pearl 2), conservative cleanup and alpha-stage export."""
import copy,io,json,re,shutil,time,traceback,zipfile
from pathlib import Path
from .diagnostics import DiagnosticSession,redact
from .sam_export import require_environment

BASIS='user-selected-candidate-5-plus-authorized-light-cleanup'
# Approval applies only to the exact assets the user supplied and reviewed, never arbitrary candidate #5.
APPROVED_INPUTS={
 'source.png':'a4d52b43677af347a12fc127d0916c3354a6370bd4eab996a3bfeda8035e0b56',
 'source_depth.png':'8a424b61cf15f79abdfeef762b9181d45ccf156261cf5a76664abc96b393beb6',
 'person_selected_mask.png':'bfddd86d02ceb606ddea971efbc51019efe3618ac0f1229369f377b4aa0e5f46',
 'pearl_candidate_2.png':'c22a549596d982940b6045d34ffd230fecce75c0d2a150e6bd29169454a9d51d'}

def cleanup_person(mask):
    import numpy as np
    from scipy import ndimage as ndi
    a=np.asarray(mask)>0
    labels,count=ndi.label(a,np.ones((3,3)));areas=np.bincount(labels.ravel())
    if not count:raise ValueError('empty approved person mask')
    main=1+int(np.argmax(areas[1:]));distance=ndi.distance_transform_edt(labels!=main)
    h,w=a.shape;clean=a.copy();records=[]
    # Scene-specific protection is only used to KEEP original pixels, never grow a mask.
    protected=[(.39,.28,.94,.435),(.23,.39,.55,.49),(.42,.43,.91,.51),(.64,.50,.84,.64)]
    for k in range(1,count+1):
        yy,xx=np.where(labels==k);cx=float(xx.mean()/w);cy=float(yy.mean()/h);n=int(areas[k])
        nearby=float(distance[yy,xx].min())
        keep_protected=any(np.any((xx>=x0*w)&(xx<x1*w)&(yy>=y0*h)&(yy<y1*h)) for x0,y0,x1,y1 in protected)
        reason=None
        if k!=main and not keep_protected:
            if n<=15000*(w*h/(1648*2944)) and cx<.46 and .54<cy<.65 and nearby>8:reason='detached lower-left reflection fragment'
            elif n<=1024*(w*h/(1648*2944)) and cy>.65 and nearby>8:reason='small detached lower-edge fragment'
            elif n<=2000*(w*h/(1648*2944)) and .17<cx<.32 and .285<cy<.35:reason='detached blue-ring background fragment'
            elif n<=128*(w*h/(1648*2944)) and nearby>8:reason='tiny isolated fragment outside protected details'
        if reason:clean[yy,xx]=False
        records.append({'id':k,'area':n,'centroid_normalized':[cx,cy],'bbox':[int(xx.min()),int(yy.min()),int(xx.max()+1),int(yy.max()+1)],'protected':keep_protected,'removed':bool(reason),'reason':reason})
    removed=int((a&~clean).sum())
    if removed/max(1,int(a.sum()))>.06:raise ValueError('cleanup would remove >6% of approved pixels; stop rather than silently damage subject')
    return clean,{'components_before':count,'components_after':int(ndi.label(clean,np.ones((3,3)))[1]),
                  'foreground_before':int(a.sum()),'foreground_after':int(clean.sum()),'removed_pixels':removed,
                  'removed_fraction':removed/int(a.sum()),'added_pixels':int((clean&~a).sum()),'components':records,
                  'largest_component_preserved':True,'no_erosion_dilation_or_fill':True}

def mask_previews(work):
    import numpy as np
    from PIL import Image
    from .core import read_image,load_mask
    p=Path(work);out=p/'selection-previews';out.mkdir(exist_ok=True);src=read_image(p/'source.png','RGB');rgb=np.array(src)
    removed=None
    for label,file in [('selected','person_selected_mask.png'),('cleaned','person_mask.png')]:
        a=load_mask(p/file,src.size)>0
        color=rgb.copy();color[a]=(color[a]*.45+np.array([255,45,150])*.55).astype('uint8')
        im=Image.fromarray(color);im.thumbnail((720,1280));im.save(out/f'person_{label}_overlay.jpg',quality=94)
        im=Image.fromarray((a*255).astype('uint8'));im.thumbnail((720,1280));im.save(out/f'person_{label}_mask_preview.png')
    old=load_mask(p/'person_selected_mask.png',src.size)>0;new=load_mask(p/'person_mask.png',src.size)>0
    color=rgb.copy();color[old&~new]=[255,50,20];im=Image.fromarray(color);im.thumbnail((720,1280));im.save(out/'removed_fragments_red.jpg',quality=94)

def prepare_selection(archive,work,project):
    import numpy as np
    from PIL import Image
    from .core import digest,load_mask,save_mask
    from .cli import DEFAULT,check_config
    from .person_review import import_review
    from .segmentation import fingerprint,inspect
    p=Path(work);p.mkdir(parents=True,exist_ok=True)
    identity=import_review(archive,p,p/'.input-check') # validate safely before touching the live project
    with zipfile.ZipFile(archive) as z:
        raw=z.read('person_candidate_5.png')
        with Image.open(io.BytesIO(raw)) as im:
            if im.mode!='L' or im.size!=Image.open(p/'source.png').size or set(np.unique(im))!={0,255}:raise ValueError('selected person5 must be native binary L mask')
        (p/'person_selected_mask.png').write_bytes(raw)
        cfg=json.loads(z.read('config.json'))
    if any(digest(p/name)!=sha for name,sha in APPROVED_INPUTS.items()):raise ValueError('素材与本次已人工批准的候选5/珍珠2不一致，禁止套用审核')
    identity=import_review(archive,p,project)
    shutil.rmtree(p/'.input-check',ignore_errors=True)
    check_config(cfg)
    cfg['CONTINUE_AFTER_SEGMENTATION']=False # selection enables matte only, not unconditional whole generation
    if cfg.get('person_review_profiles'):cfg['sam_prompts']['person']=copy.deepcopy(cfg['person_review_profiles'][1])
    (p/'config.json').write_text(json.dumps(cfg,ensure_ascii=False,indent=2))
    input_path=Path(project)/'input.json'
    source_identity=json.loads(input_path.read_text()) if input_path.exists() else None
    if source_identity and (source_identity.get('source_sha256')!=digest(p/'source.png') or source_identity.get('depth_sha256')!=digest(p/'source_depth.png')):raise ValueError('original input provenance does not match approved assets')
    if not source_identity:
        source_identity={'zip_sha256':digest(archive),'source_sha256':digest(p/'source.png'),'depth_sha256':digest(p/'source_depth.png'),
                         'size':list(Image.open(p/'source.png').size),'input_kind':'approved-person-review','original_v1_provenance_unknown':True}
    (p/'input.json').write_text(json.dumps(source_identity,ensure_ascii=False,indent=2))
    a=load_mask(p/'person_selected_mask.png',Image.open(p/'source.png').size)>0
    clean,report=cleanup_person(a);save_mask(p/'person_mask.png',clean)
    if digest(p/'pearl_mask.png')!=identity['pearl_sha256']:raise RuntimeError('approved pearl changed')
    selection={'person':{'candidate_index':5,'selected':True,'human_approved':True,'selected_mask_sha256':digest(p/'person_selected_mask.png'),
                         'cleaned_mask_sha256':digest(p/'person_mask.png'),'cleanup_authorized_by_user':True},
               'pearl':{'candidate_index':2,'selected':True,'human_approved':True,'sha256':identity['pearl_sha256']},
               'input_zip_sha256':digest(archive),'approval_scope':'binary selection plus light cleanup; not unseen alpha/background/final image approval',
               'source_sha256':digest(p/'source.png'),'depth_sha256':digest(p/'source_depth.png')}
    (p/'selection.json').write_text(json.dumps(selection,ensure_ascii=False,indent=2));(p/'cleanup-report.json').write_text(json.dumps(report,indent=2))
    # Keep measured failures visible: user explicitly reviewed target identity, overriding prompt heuristics only.
    measured=inspect(p,cfg)
    review={'human_approved':True,'approval_basis':BASIS,'fingerprint':fingerprint(p,cfg),'selection_sha256':digest(p/'selection.json'),
            'basic_checks_passed':measured['passed'],'note':'User explicitly selected person5 and pearl2. Basic failures are retained; no fake passed=true.'}
    (p/'segmentation-review.json').write_text(json.dumps(review,indent=2));mask_previews(p)
    return selection

def alpha_previews(work):
    import numpy as np
    from PIL import Image
    from .core import read_image,load_mask
    p=Path(work);out=p/'alpha-previews';out.mkdir(exist_ok=True);src=read_image(p/'source.png','RGB');rgb=np.array(src)
    for name in ['person','pearl']:
        a=load_mask(p/f'{name}_alpha.png',src.size)
        rgba=np.dstack([rgb,np.rint(a*255).astype('uint8')]);Image.fromarray(rgba,'RGBA').save(p/f'{name}_rgba_ALPHA_REVIEW.png')
        for bg in [0,255]:
            im=Image.fromarray(np.rint(rgb*a[...,None]+bg*(1-a[...,None])).astype('uint8'));im.thumbnail((720,1280));im.save(out/f'{name}_on_{bg}.jpg',quality=94)
    # These are matting previews with original RGB, not decontaminated final layers.
    (p/'ALPHA_REVIEW_REQUIRED.txt').write_text('真实Alpha已推理；RGB仅来自原图，尚未进行补全背景去污染。必须审核发丝、白纱、黑白边及保护核心后才能批准背景补全，不能将此包当最终spatial-v2。')

def execute_alpha(work):
    from .models import matte,model_spec,DEFAULT_MODELS
    from .cli import seal_masks
    p=Path(work);cfg=json.loads((p/'config.json').read_text());lock=json.loads((p/'models.lock.json').read_text())
    if 'matte' not in lock:
        from huggingface_hub import model_info
        lock['matte']={'id':DEFAULT_MODELS['matte'],'revision':model_info(DEFAULT_MODELS['matte']).sha}
        (p/'models.lock.json').write_text(json.dumps(lock,indent=2))
    if model_spec(p,'matte')['id']!=DEFAULT_MODELS['matte']:raise ValueError('unexpected matting model')
    matte(p,cfg);seal_masks(p);alpha_previews(p)
    return {'matte_executed':True,'alpha_human_approved':False,'background_executed':False}

def export_stage(work,out,session=None):
    p=Path(work);out=Path(out);out.parent.mkdir(parents=True,exist_ok=True)
    names=['input.json','source.png','source_depth.png','person_selected_mask.png','person_mask.png','pearl_mask.png','pearl_candidate_2.png','fixed-pearl.json',
           'config.json','models.lock.json','selection.json','cleanup-report.json','segmentation-review.json','segmentation-check.json',
           'person_alpha.png','pearl_alpha.png','person_protect.png','pearl_protect.png','background_hole.png','person_unobserved.png',
           'person_trimap.png','pearl_trimap.png','person_rgba_ALPHA_REVIEW.png','pearl_rgba_ALPHA_REVIEW.png','matte-execution.json','preflight.json','ALPHA_REVIEW_REQUIRED.txt','GPU_NOT_RUN.txt']
    for folder in ['selection-previews','alpha-previews']:
        if (p/folder).exists():names += [str(f.relative_to(p)) for f in (p/folder).iterdir() if f.suffix in ['.png','.jpg']]
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        if session:z.writestr('stage-session.json',redact(json.dumps(session.report,ensure_ascii=False,indent=2),session.env))
        for name in names:
            f=p/name
            if f.is_file():
                if f.suffix in ['.json','.txt']:z.writestr(name,redact(f.read_text(),session.env if session else {}))
                else:z.write(f,name)
    return out

def run_approved_stage(base,root,archive,bootstrap_commands=()):
    base=Path(base);root=Path(root);project=base/'project-001';py=base/'python312/bin/python';work=base/'approved-layer-runs'/str(time.time_ns());work.mkdir(parents=True)
    s=DiagnosticSession(base,root);s.report.update(stage='approved-selection',person_selected=5,pearl_selected=2,alpha_human_approved=False)
    s.report['commands'].extend(bootstrap_commands);s.env.update(HF_HOME=str(base/'cache'),TOKENIZERS_PARALLELISM='false')
    try:
        s.report['previous_preflight']=require_environment(project,py)
        s.command([py,'-m','tools.layered_v4.approved_layers','--work',work,'--project',project,'--archive',archive])
        s.report.update(passed=True,stage='alpha-awaiting-review',message='人物5已选定并轻清理，珍珠2不变；Alpha抠图已完成，请发送结果ZIP继续审核。未自动批准新Alpha或背景。')
    except Exception as exc:s.report.update(error=str(exc),traceback=traceback.format_exc(),message='后处理已停止，结果和错误已打包；请发送ZIP，无需查看日志。')
    finally:
        if (work/'matte-execution.json').exists():
            s.report['matte_execution']=json.loads((work/'matte-execution.json').read_text())
            s.report['models_started']=bool(s.report['matte_execution'].get('executed'))
        if (work/'preflight.json').exists():
            s.report['current_preflight']=json.loads((work/'preflight.json').read_text())
            s.report['gpu_verified']=bool(s.report['current_preflight'].get('gpu_probe_passed'))
        out=export_stage(work,base/'FloatDeck-selected5-alpha-review.zip',s)
    print(s.report['message']);return {'zip':str(out),'work':str(work),'passed':s.report['passed']}


def validate_selection(work):
    import numpy as np
    from PIL import Image
    from .core import digest,load_mask
    p=Path(work);s=json.loads((p/'selection.json').read_text());person=s['person'];pearl=s['pearl']
    if person.get('candidate_index')!=5 or not person.get('selected') or not person.get('human_approved') or not person.get('cleanup_authorized_by_user'):raise ValueError('person selection scope invalid')
    if pearl.get('candidate_index')!=2 or not pearl.get('human_approved'):raise ValueError('pearl selection scope invalid')
    checks={'source.png':s['source_sha256'],'source_depth.png':s['depth_sha256'],'person_selected_mask.png':person['selected_mask_sha256'],
            'person_mask.png':person['cleaned_mask_sha256'],'pearl_mask.png':pearl['sha256'],'pearl_candidate_2.png':pearl['sha256']}
    if any(digest(p/n)!=h for n,h in checks.items()) or any(digest(p/n)!=h for n,h in APPROVED_INPUTS.items()):raise ValueError('approved selection/source changed')
    size=Image.open(p/'source.png').size;old=load_mask(p/'person_selected_mask.png',size)>0;new=load_mask(p/'person_mask.png',size)>0
    if not new.any() or np.any(new&~old) or np.sum(old&~new)/max(1,old.sum())>.06:raise ValueError('cleanup exceeds authorized scope')
    return s

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--work',required=True);p.add_argument('--project',required=True);p.add_argument('--archive',required=True)
    a=p.parse_args();prepare_selection(a.archive,a.work,a.project);execute_alpha(a.work)

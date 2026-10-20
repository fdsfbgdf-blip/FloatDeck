"""Approved pearl is immutable. Only person hypotheses are sent to SAM."""
import copy,io,json,re,shutil,time,traceback,zipfile
from pathlib import Path,PurePosixPath
from .diagnostics import DiagnosticSession,redact
from .sam_export import require_environment

ZIP_NAME='FloatDeck-person-coverage-rereview.zip'

def import_review(archive,work,project):
    from PIL import Image
    import numpy as np
    from .core import digest,MAX_BYTES,MAX_PIXELS
    p=Path(work);p.mkdir(parents=True,exist_ok=True);project=Path(project)
    with zipfile.ZipFile(archive) as z:
        entries=z.infolist();names=z.namelist()
        if len(names)!=len(set(names)) or len(names)>256 or sum(e.file_size for e in entries)>MAX_BYTES:raise ValueError('审核ZIP重复/过大')
        for e in entries:
            n=PurePosixPath(e.filename)
            if n.is_absolute() or '..' in n.parts or '\\' in e.filename or e.flag_bits&1 or (e.external_attr>>16)&0o170000==0o120000:raise ValueError('不安全审核ZIP')
        size=None
        for name,mode in [('source.png','RGB'),('source_depth.png','L'),('pearl_candidate_2.png','L')]:
            raw=z.read(name)
            with Image.open(io.BytesIO(raw)) as im:
                if im.mode!=mode or not 0<im.width*im.height<=MAX_PIXELS:raise ValueError('审核素材尺寸/通道错误')
                if size is None:size=im.size
                if size!=im.size:raise ValueError('原生尺寸不一致，禁止缩放')
                if name.startswith('pearl'):
                    values=set(np.unique(np.asarray(im)))
                    if values!={0,255}:raise ValueError('珍珠必须是非空二值原生遮罩')
            (p/name).write_bytes(raw)
        if 'person_candidate_0.png' in names:(p/'previous_person_0.png').write_bytes(z.read('person_candidate_0.png'))
        if 'person_candidate_5.png' in names:(p/'rejected_person_5.png').write_bytes(z.read('person_candidate_5.png'))
        elif 'person_selected_mask.png' in names:(p/'rejected_person_5.png').write_bytes(z.read('person_selected_mask.png'))
        lock=json.loads(z.read('models.lock.json'));spec=lock['segment']
        if spec.get('id')!='facebook/sam-vit-base' or not re.fullmatch('[a-fA-F0-9]{40}',spec.get('revision','')):raise ValueError('审核包缺少有效SAM版本锁')
        (p/'models.lock.json').write_text(json.dumps({'segment':spec},indent=2))
    identity={'source_sha256':digest(p/'source.png'),'depth_sha256':digest(p/'source_depth.png'),
              'pearl_sha256':digest(p/'pearl_candidate_2.png'),'origin':'pearl_candidate_2.png',
              'pearl_human_approved':True,'person_human_approved':False,'user_instruction':'固定候选2，禁止重新猜测或更换'}
    for n,key in [('source.png','source_sha256'),('source_depth.png','depth_sha256')]:
        if (project/n).exists() and digest(project/n)!=identity[key]:raise ValueError('审核包与现存原始素材不一致，未覆盖')
    fixed=project/'fixed-pearl.json'
    if fixed.exists():
        old=json.loads(fixed.read_text())
        if any(old.get(k)!=identity[k] for k in ['source_sha256','depth_sha256','pearl_sha256']):raise ValueError('已固定珍珠或素材不一致，禁止替换')
        if not (project/'pearl_mask.png').exists() or digest(project/'pearl_mask.png')!=old['pearl_sha256']:raise ValueError('已固定珍珠文件被修改，停止')
    shutil.copyfile(p/'pearl_candidate_2.png',p/'pearl_mask.png')
    project.mkdir(parents=True,exist_ok=True)
    (project/'person-rejection.json').write_text(json.dumps({'person_human_approved':False,'previous_candidate_5_rejected':True,'reason':'semantic garment/body coverage missing; not environment or matting failure','pearl_candidate_2_approved':True},indent=2))
    if not fixed.exists():
        shutil.copyfile(p/'pearl_candidate_2.png',project/'pearl_mask.png');fixed.write_text(json.dumps(identity,ensure_ascii=False,indent=2))
    # Every new re-review invalidates whole-scene/person approval, including repeated runs.
    for name in ['segmentation-review.json','review.json','person_mask.png']:(project/name).unlink(missing_ok=True)
    (p/'fixed-pearl.json').write_text(json.dumps(identity,ensure_ascii=False,indent=2))
    return identity

def review_candidates(work,config,profiles):
    from .core import read_image,load_mask
    from .segmentation import person_metrics,person_review_order,previews,PERSON_BACKGROUND_PROBES
    from .sam_export import make_sheets
    p=Path(work);size=read_image(p/'source.png','RGB').size;pearl=load_mask(p/'pearl_mask.png',size)
    proposal=json.loads((p/'person_proposal.json').read_text());previews(p,config);candidates=[]
    previous=None
    if (p/'previous_person_0.png').exists():previous=person_metrics(load_mask(p/'previous_person_0.png',size),profiles[0],size,pearl)
    for i in range(6):
        profile=profiles[i//3];a=load_mask(p/f'person_candidate_{i}.png',size);m=person_metrics(a,profile,size,pearl)
        item={'target':'person','中文名称':'人物','candidate_index':i,'profile':profile,'roi':profile['box'],
              'iou_score':proposal['scores'][i],'metrics':m,'check':m['basic'],'human_approved':False}
        if previous:item['background_occupancy_change_vs_old0']={k:m['background_roi_occupancy'][k]-previous['background_roi_occupancy'][k] for k in PERSON_BACKGROUND_PROBES}
        candidates.append(item)
    order=person_review_order(candidates)
    coverage_crop_previews(p,candidates)
    result={'complete':True,'candidates':candidates,'inspection_order':order,'recommended_first_two':order[:2],
            'ranking_basis':'基础约束→最弱区域覆盖→珍珠交叠→环境占用；IoU不参与排序。区域覆盖只是采样点代理，不能自动通过。',
            'selected':None,'human_approved':False,'passed':False,'candidate_generation_complete':True,'CONTINUE_AFTER_SEGMENTATION':False,'background_rois':PERSON_BACKGROUND_PROBES}
    (p/'person-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));make_sheets(p,result,{'person':'人物'})
    # Show both actual prompt sets; the generic prompt preview represents only profile 0.
    from PIL import ImageDraw,ImageFont
    from .segmentation import pixels
    font=ImageFont.truetype(str(Path(__file__).resolve().parent/'fonts/ReviewLabels.otf'),22)
    for index,profile in enumerate(profiles):
        im=read_image(p/'source.png','RGB');draw=ImageDraw.Draw(im);box,pts,labels=pixels(profile,size)
        draw.rectangle(box,outline='yellow',width=5)
        for (x,y),label in zip(pts,labels):draw.ellipse((x-9,y-9,x+9,y+9),fill='lime' if label else 'red')
        im.thumbnail((620,1040));draw=ImageDraw.Draw(im);draw.rectangle((0,0,620,34),fill='black');draw.text((4,4),f'人物 · 提示框与正负点 {index}',font=font,fill='white')
        im.save(p/'segmentation-previews'/f'person_review_profile{index}.jpg',quality=90)
    lines=['# 人物复审（珍珠候选2固定）','本轮仅生成候选，未批准人物，未运行后续生成。',
           '优先查看候选：'+', '.join(map(str,order[:2]))+'（仅基于可测约束，不是人物完整性的视觉结论）。',
           '环境区域变化为小ROI的像素占用变化，不代表整个环境误吸已解决。负数表示占用减少；正点覆盖仅是完整性的代理。']
    for c in candidates:
        lines.append(f"- 候选{c['candidate_index']} / {c['profile']['id']}：正点覆盖{c['metrics']['positive_coverage']:.1%}；各部位覆盖{c['metrics']['coverage_by_region']}；环境ROI占用{c['metrics']['background_roi_occupancy']}；与旧0变化{c.get('background_occupancy_change_vs_old0','未提供旧0，无法比较')}。")
    lines.append('必须人工核对脸、右侧长发、手指、透明衣料、裙摆和右腿/脚；不能以点约束分数宣称这些细节已通过。')
    (p/'人物复审说明.md').write_text('\n'.join(lines)+'\n');return result

def execute_person(work,project,archive):
    from .cli import DEFAULT,check_config
    from .segmentation import PERSON_REVIEW_PROFILES
    from .models import segment
    from .core import digest
    p=Path(work);identity=import_review(archive,p,project)
    profiles=copy.deepcopy(PERSON_REVIEW_PROFILES);cfg=copy.deepcopy(DEFAULT)
    cfg['sam_prompts']['person']=profiles[0];cfg['boxes']['person']=profiles[0]['box'];cfg['person_review_profiles']=profiles
    cfg['CONTINUE_AFTER_SEGMENTATION']=False;cfg['previous_person5_rejected']=True;check_config(cfg)
    (p/'person-rejection.json').write_text(json.dumps({'person_human_approved':False,'previous_person5_rejected':True,'generation_paused':True,'pearl_candidate_2_approved':True},indent=2))
    (p/'config.json').write_text(json.dumps(cfg,ensure_ascii=False,indent=2))
    segment(p,cfg,targets=('person',),prompt_sets=profiles)
    if digest(p/'pearl_mask.png')!=identity['pearl_sha256'] or digest(p/'pearl_candidate_2.png')!=identity['pearl_sha256']:raise RuntimeError('固定珍珠发生变化，停止')
    if digest(p/'source.png')!=identity['source_sha256'] or digest(p/'source_depth.png')!=identity['depth_sha256']:raise RuntimeError('原始像素文件发生变化')
    return review_candidates(p,cfg,profiles)

def export_person(work,session):
    p=Path(work);out=session.base/ZIP_NAME
    files=['source.png','source_depth.png','pearl_candidate_2.png','pearl_mask.png','fixed-pearl.json','previous_person_0.png',
           'models.lock.json','config.json','person-rejection.json','rejected_person_5.png','person_proposal.json','person-checks.json','segment-execution.json','preflight.json','人物复审说明.md']
    files+=[f'person_candidate_{i}.png' for i in range(6)]
    files+=['segmentation-previews/'+f.name for f in sorted((p/'segmentation-previews').glob('person_review_*.jpg'))] if (p/'segmentation-previews').exists() else []
    files+=['person_parts/'+f.name for f in sorted((p/'person_parts').glob('*.png'))] if (p/'person_parts').exists() else []
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('person-session-report.json',redact(json.dumps(session.report,ensure_ascii=False,indent=2),session.env))
        for name in files:
            f=p/name
            if f.is_file():
                if f.suffix in ['.json','.md']:z.writestr(name,redact(f.read_text(),session.env))
                else:z.write(f,name)
    return out

def run_person_export(base,root,archive,bootstrap_commands=()):
    base=Path(base);root=Path(root);project=base/'project-001';py=base/'python312/bin/python'
    work=base/'person-review-runs'/str(time.time_ns());work.mkdir(parents=True)
    s=DiagnosticSession(base,root);s.report.update(task='person-only re-review',human_approved=False,selected=None,CONTINUE_AFTER_SEGMENTATION=False)
    s.report['commands'].extend(bootstrap_commands);s.env.update(HF_HOME=str(base/'cache'),TOKENIZERS_PARALLELISM='false')
    try:
        s.report['previous_preflight']=require_environment(project,py)
        s.report['generator_commit']=s.command(['git','rev-parse','HEAD'],check=False).stdout.strip()
        s.report['stage']='person-only-inference';s.report['sam_command_attempted']=True
        s.command([py,'-m','tools.layered_v4.cli','person-review','--work',work,'--project',project,'--input',archive])
        checks=json.loads((work/'person-checks.json').read_text())
        if not checks.get('complete') or len(checks['candidates'])!=6:raise RuntimeError('人物候选未完整导出')
        s.report.update(passed=False,candidate_generation_complete=True,stage='awaiting-person-review',message='新人物覆盖复审候选已完整导出；人物审核仍未通过。珍珠2不变，不进入matte或背景。')
    except Exception as exc:s.report.update(error=str(exc),traceback=traceback.format_exc(),message='人物复审未完成，已停止。请发送下载的复审ZIP，无需分析日志。')
    finally:
        execution=work/'segment-execution.json'
        if execution.exists():
            s.report['sam_execution']=json.loads(execution.read_text());s.report['models_started']=bool(s.report['sam_execution'].get('executed'))
        path=export_person(work,s)
    print(s.report['message']);return {'zip':str(path),'work':str(work),'passed':False,'candidate_generation_complete':s.report.get('candidate_generation_complete',False)}


def coverage_crop_previews(work,candidates):
    import numpy as np
    from PIL import Image,ImageDraw,ImageFont
    from .core import read_image,load_mask
    from .segmentation import PERSON_COVERAGE_ROIS
    p=Path(work);out=p/'segmentation-previews';out.mkdir(exist_ok=True)
    src=read_image(p/'source.png','RGB');w,h=src.size;rgb=np.array(src)
    font=ImageFont.truetype(str(Path(__file__).resolve().parent/'fonts/ReviewLabels.otf'),20)
    for c in candidates:
        i=c['candidate_index'];a=load_mask(p/f'person_candidate_{i}.png',src.size)>0
        overlay=rgb.copy();overlay[a]=(overlay[a]*.5+np.array([255,40,150])*.5).astype('uint8')
        for key,r in PERSON_COVERAGE_ROIS.items():
            box=tuple(round(v*(w if k%2==0 else h)) for k,v in enumerate(r['box']))
            source=src.crop(box);tint=Image.fromarray(overlay).crop(box);mask=Image.fromarray((a*255).astype('uint8')).convert('RGB').crop(box)
            panels=[]
            for im in [source,tint,mask]:im.thumbnail((360,420));panels.append(im)
            height=max(im.height for im in panels);canvas=Image.new('RGB',(1080,height+55),'#202020');d=ImageDraw.Draw(canvas)
            d.text((8,8),f"人物 · 候选{i} · {r['label']} / 原图 · 叠加 · 遮罩（未批准）",font=font,fill='white')
            for k,im in enumerate(panels):canvas.paste(im,(k*360,55))
            canvas.save(out/f'person_review_crop{i}_{key}.jpg',quality=93)

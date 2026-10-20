"""One-click SAM review export. No candidate selection, matting or generation."""
import json,os,re,shutil,sys,time,traceback,zipfile
from pathlib import Path
from .diagnostics import DiagnosticSession,redact

NAMES={'person':'人物','pearl':'前景珍珠'}

def require_environment(project,python):
    record=json.loads((Path(project)/'preflight.json').read_text())
    if not record.get('passed') or not record.get('gpu_probe_passed') or record.get('basic_only'):
        raise RuntimeError('上一阶段环境检查没有通过，请先点击一键环境检查单元')
    if os.path.abspath(record.get('python',''))!=os.path.abspath(str(python)):
        raise RuntimeError('环境报告解释器与当前目标不一致，请先点击一键环境检查单元')
    if not Path(python).is_file():raise RuntimeError('独立环境已丢失，请先运行环境检查单元')
    return record

def candidate_review(work):
    from .core import read_image,load_mask
    from .segmentation import check_mask,previews
    p=Path(work);cfg=json.loads((p/'config.json').read_text());size=read_image(p/'source.png','RGB').size
    previews(p,cfg);results=[]
    for name,label in NAMES.items():
        proposal=json.loads((p/f'{name}_proposal.json').read_text())
        for i in range(3):
            f=p/f'{name}_candidate_{i}.png'
            check=check_mask(load_mask(f,size),cfg['sam_prompts'][name],size,name)
            results.append({'target':name,'中文名称':label,'candidate_index':i,'iou_score':proposal['scores'][i],
                            'check':check,'human_approved':False})
    report={'complete':True,'size':list(size),'candidates':results,'human_approved':False,'selected':None,
            'note':'基础合理性通过不等于画质通过；未选择候选，等待人工审核。'}
    (p/'candidate-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    make_sheets(p,report)
    return report

def make_sheets(work,report,names=None):
    from PIL import Image,ImageDraw,ImageFont
    p=Path(work)
    # Chinese captions are baked into review previews, not just Notebook print labels.
    font_path=Path(__file__).resolve().parent/'fonts/ReviewLabels.otf'
    font=ImageFont.truetype(str(font_path),22)
    error_names={'positive seed missing':'正点漏选','negative seed included':'负点误选',
                 'substantial support outside target box':'框外过多','empty/tiny or excessive image coverage':'面积异常'}
    for name,label in (names or NAMES).items():
        files=[('原图',p/'source.png'),('提示框与正负点',p/'segmentation-previews'/f'{name}_prompts.png')]
        for c in [c for c in report['candidates'] if c['target']==name]:
            i=c['candidate_index']
            status='基础检查通过，待审核' if c['check']['passed'] else '不合格：'+','.join(error_names.get(e,'检查失败') for e in c['check']['errors'])
            files.extend([(f'候选{i} 遮罩',p/f'{name}_candidate_{i}.png'),(f'候选{i} {status}',p/'segmentation-previews'/f'{name}_candidate_{i}_overlay.png')])
        for idx,(title,path) in enumerate(files):
            im=Image.open(path).convert('RGB');im.thumbnail((620,1040))
            canvas=Image.new('RGB',(660,im.height+95),'#202020');canvas.paste(im,((660-im.width)//2,90))
            draw=ImageDraw.Draw(canvas);draw.text((10,8),label+' · '+title[:26],font=font,fill='white')
            draw.text((10,40),'未选择 / 未批准；原生遮罩独立保存',font=font,fill='#ffd090')
            canvas.save(p/'segmentation-previews'/f'{name}_review_{idx}.jpg',quality=90)

def execute(work,project,archive=None):
    """Runs in the existing target interpreter, never the Notebook kernel."""
    import copy
    from .core import import_v1,read_image,digest
    from .cli import DEFAULT,check_config
    from .models import DEFAULT_MODELS,segment
    from .segmentation import PROMPTS
    p=Path(work);p.mkdir(parents=True,exist_ok=True);project=Path(project)
    if archive:
        import_v1(archive,p)
        # Restore missing originals for the next click; never overwrite conflicting existing pixels.
        for n in ['source.png','source_depth.png']:
            if (project/n).exists() and digest(project/n)!=digest(p/n):raise ValueError('上传素材与现存原图不一致，已停止，未覆盖')
        for n in ['source.png','source_depth.png','input.json']:
            if not (project/n).exists():shutil.copyfile(p/n,project/n)
    else:
        for n in ['source.png','source_depth.png']:shutil.copyfile(project/n,p/n)
        if (project/'input.json').exists():shutil.copyfile(project/'input.json',p/'input.json')
    src=read_image(p/'source.png','RGB');depth=read_image(p/'source_depth.png','L')
    if src.size!=depth.size:raise ValueError('原图与深度尺寸不一致，禁止自动缩放')
    cfg=copy.deepcopy(DEFAULT);cfg['sam_prompts']=copy.deepcopy(PROMPTS);cfg['boxes']={k:v['box'] for k,v in PROMPTS.items()}
    check_config(cfg);(p/'config.json').write_text(json.dumps(cfg,ensure_ascii=False,indent=2))
    lock_path=project/'models.lock.json';lock=json.loads(lock_path.read_text()) if lock_path.exists() else {}
    spec=lock.get('segment')
    if spec is None:
        from huggingface_hub import model_info
        spec={'id':DEFAULT_MODELS['segment'],'revision':model_info(DEFAULT_MODELS['segment']).sha}
    if spec.get('id')!=DEFAULT_MODELS['segment'] or not re.fullmatch('[a-fA-F0-9]{40}',spec.get('revision','')):
        raise ValueError('已有 SAM 模型版本锁不正确，未静默刷新')
    (p/'models.lock.json').write_text(json.dumps({'segment':spec},indent=2))
    # Persist SAM-only lock for retries; keep any existing matte/inpaint revisions untouched.
    if 'segment' not in lock:
        lock['segment']=spec;lock_path.write_text(json.dumps(lock,indent=2))
    before={n:digest(p/n) for n in ['source.png','source_depth.png']}
    segment(p,cfg);result=candidate_review(p)
    if before!={n:digest(p/n) for n in before}:raise RuntimeError('源素材像素文件被修改')
    (p/'source-hashes.json').write_text(json.dumps(before,indent=2));return result

def export_zip(work,session):
    p=Path(work);out=session.base/'FloatDeck-SAM-review.zip'
    allowed=['source.png','source_depth.png','input.json','config.json','models.lock.json','candidate-checks.json',
             'preflight.json','segment-execution.json','source-hashes.json']
    for name in NAMES:
        allowed+=[f'{name}_proposal.json']+[f'{name}_candidate_{i}.png' for i in range(3)]
    allowed+=['segmentation-previews/'+f.name for f in sorted((p/'segmentation-previews').glob('*_review_*.jpg'))] if (p/'segmentation-previews').exists() else []
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('sam-session-report.json',redact(json.dumps(session.report,ensure_ascii=False,indent=2),session.env))
        z.writestr('说明.txt','仅用于SAM人工审核；未选择候选、未批准分割、未执行抠图/补全/最终素材打包。\n'+session.report.get('message',''))
        for name in allowed:
            f=p/name
            if f.is_file():
                if f.suffix=='.json':z.writestr(name,redact(f.read_text(),session.env))
                else:z.write(f,name)
    return out

def run_export(base,root,archive=None,bootstrap_commands=()):
    base=Path(base);root=Path(root);project=base/'project-001';py=base/'python312/bin/python'
    work=base/'sam-runs'/str(time.time_ns());work.mkdir(parents=True)
    s=DiagnosticSession(base,root);s.report.update(stage='previous-environment',task='SAM review only',human_approved=False,selected=None)
    s.report['commands'].extend(bootstrap_commands)
    s.env.update(HF_HOME=str(base/'cache'),HF_HUB_DISABLE_TELEMETRY='1',TOKENIZERS_PARALLELISM='false')
    try:
        if (project/'fixed-pearl.json').exists():raise RuntimeError('珍珠已固定，请使用人物分割复审导出，禁止重新分割珍珠')
        s.report['previous_preflight']=require_environment(project,py)
        s.report['generator_commit']=s.command(['git','rev-parse','HEAD'],check=False).stdout.strip()
        if archive is None and not all((project/n).is_file() for n in ['source.png','source_depth.png']):
            raise RuntimeError('源素材已丢失，需要上传原始 spatial.zip')
        s.report['stage']='SAM-inference-and-review'
        args=[py,'-m','tools.layered_v4.sam_export','--work',work,'--project',project]
        if archive:args+=['--archive',archive]
        s.report['models_started']=True
        s.command(args)
        checks=json.loads((work/'candidate-checks.json').read_text())
        if not checks.get('complete') or len(checks['candidates'])!=6:raise RuntimeError('六个候选输出不完整')
        s.report.update(passed=True,stage='awaiting-human-review',models_started=True,
                        message='SAM 已完成六个候选。基础检查已标注，未选择或批准任何候选。请把下载的审核 ZIP 发给 ChatGPT。')
    except Exception as exc:
        s.report.update(error=str(exc),traceback=traceback.format_exc(),message='SAM 未完整完成，已停止。请把下载的审核 ZIP 发给 ChatGPT，无需阅读错误或手动改代码。')
    finally:
        if (work/'segment-execution.json').exists():s.report['sam_execution']=json.loads((work/'segment-execution.json').read_text())
        path=export_zip(work,s)
    print(s.report['message']);return {'zip':str(path),'work':str(work),'passed':s.report['passed']}

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--work',required=True);parser.add_argument('--project',required=True);parser.add_argument('--archive')
    a=parser.parse_args();execute(a.work,a.project,a.archive)

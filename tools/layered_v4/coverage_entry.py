"""Standalone coverage review: current environment -> validated assets -> person-only SAM."""
import copy,json,shutil,time,traceback,zipfile
from pathlib import Path,PurePosixPath
from .diagnostics import DiagnosticSession,prepare
from .sam_export import require_environment
from .person_review import export_person

REQUIRED={'source.png','source_depth.png','pearl_candidate_2.png','models.lock.json'}

def has_review(path):
    """Cheap stdlib validation before installing/loading image/model packages."""
    try:
        with zipfile.ZipFile(path) as z:
            entries=z.infolist();names=z.namelist()
            if not REQUIRED.issubset(names) or len(names)!=len(set(names)) or len(names)>256 or sum(e.file_size for e in entries)>256*1024**2:return False
            for e in entries:
                p=PurePosixPath(e.filename)
                if p.is_absolute() or '..' in p.parts or '\\' in e.filename or e.flag_bits&1 or (e.external_attr>>16)&0o170000==0o120000:return False
            return True
    except (OSError,zipfile.BadZipFile):return False

def find_review(base):
    base=Path(base)
    for name in ['coverage-review-input.zip','person-review-input.zip','FloatDeck-person-rereview.zip','FloatDeck-selected5-alpha-review.zip','FloatDeck-SAM-review.zip']:
        p=base/name
        if has_review(p):return p
    return None

def obtain_review(base,upload_once=None,archive=None):
    base=Path(base)
    if archive is not None:
        if not has_review(archive):raise ValueError('指定审核包不完整，请选择原SAM审核ZIP或包含原图/深度/珍珠2/版本锁的完整复审包')
        return Path(archive)
    found=find_review(base)
    if found:return found
    if upload_once is None:raise ValueError('没有完整审核素材，且未提供上传入口；本次未启动SAM')
    # This is the only call site. Invalid/cancelled upload never reopens the dialog in a loop.
    data=upload_once()
    if not isinstance(data,dict) or len(data)!=1:raise ValueError('未收到一个完整审核ZIP；本次未启动SAM。再次点击时请选择原审核包')
    path=base/'coverage-review-input.zip';tmp=base/('incoming-review-'+str(time.time_ns())+'.zip')
    tmp.write_bytes(next(iter(data.values())))
    if not has_review(tmp):raise ValueError('上传的不是完整审核包：需要原图、深度、珍珠候选2和SAM模型版本锁；本次未启动SAM')
    tmp.replace(path);return path

def restore_assets(archive,work,project):
    """Target-Python CPU phase; full ZIP/native/hash validation before SAM is allowed."""
    from .person_review import import_review
    from .core import digest
    p=Path(work);project=Path(project);project.mkdir(parents=True,exist_ok=True)
    # A partial reset may preserve the lock but delete the approved pearl file.
    # First validate the package into an isolated staging project, then restore ONLY a missing
    # file that exactly matches the pre-existing lock. Never replace a conflicting pearl.
    identity=import_review(archive,p,p/'.restore-check')
    fixed=project/'fixed-pearl.json'
    if fixed.exists() and not (project/'pearl_mask.png').exists():
        old=json.loads(fixed.read_text())
        if any(old.get(k)!=identity[k] for k in ['source_sha256','depth_sha256','pearl_sha256']):raise ValueError('审核包与已固定珍珠来源不一致，停止恢复')
        shutil.copyfile(p/'pearl_candidate_2.png',project/'pearl_mask.png')
    identity=import_review(archive,p,project)
    shutil.rmtree(p/'.restore-check',ignore_errors=True)
    for name,key in [('source.png','source_sha256'),('source_depth.png','depth_sha256')]:
        if not (project/name).exists():shutil.copyfile(p/name,project/name)
        if digest(project/name)!=identity[key]:raise ValueError('项目素材与固定珍珠来源不一致，未覆盖或启动SAM')
    # Never import a preflight or approval file from the ZIP. import_review writes Reject and locks pearl2.
    if not (project/'models.lock.json').exists():shutil.copyfile(p/'models.lock.json',project/'models.lock.json')
    result={'validated':True,'source_sha256':identity['source_sha256'],'depth_sha256':identity['depth_sha256'],
            'pearl_sha256':identity['pearl_sha256'],'pearl_candidate':2,'person5_rejected':True}
    (p/'assets-validated.json').write_text(json.dumps(result,indent=2));return result

def ensure_environment(session):
    """Always do a real current preflight. Existing passing JSON is never a substitute."""
    project=session.base/'project-001';project.mkdir(parents=True,exist_ok=True)
    old=project/'preflight.json'
    if old.exists():
        old.replace(project/f'preflight.previous-{time.time_ns()}.json')
        session.report['previous_preflight_quarantined']=True
    # Shared uv-based preparation owns bounded recovery; no second, independent venv retry loop.
    result=prepare(session,task_label='人物覆盖复审')
    snapshot={k:copy.deepcopy(v) for k,v in session.report.items() if k in ['passed','stage','error','traceback','observations','preflight','target_python','install_plan','gpu_verified','python_environment','python_creation_failures','quarantined_venvs']}
    session.report.setdefault('environment_attempts',[]).append(snapshot)
    if not result['passed']:raise RuntimeError(session.report.get('message','真实环境准备失败，已停止'))
    record=require_environment(project,result['python'])
    if not record.get('gpu_probe_passed'):raise RuntimeError('当前GPU真实检查没有通过')
    return result,record

def run_coverage(base,root,upload_once=None,archive=None,bootstrap_commands=(),local=False):
    base=Path(base);root=Path(root);project=base/'project-001'
    exporter=export_person
    if local:
        exporter=export_local
    work=base/'person-review-runs'/str(time.time_ns());work.mkdir(parents=True)
    s=DiagnosticSession(base,root);s.report.update(task='standalone-person-coverage',selected=None,human_approved=False,
        candidate_generation_complete=False,person5_rejected=True,pearl_candidate=2,CONTINUE_AFTER_SEGMENTATION=False)
    s.report['commands'].extend(bootstrap_commands)
    try:
        s.report['stage']='current-environment'
        env,current=ensure_environment(s)
        s.report.update(passed=False,current_environment_passed=True,current_preflight=current,stage='review-material',models_started=False)
        print('当前环境已通过，正在检查原审核素材；不会恢复人物5审批。')
        if local and archive is None:
            choices=[base/'local-review-input.zip',base/'FloatDeck-person-coverage-rereview.zip']
            archive=next((f for f in choices if has_review(f)),None)
            if archive is None:
                if upload_once is None:raise ValueError('请上传最新完整人物覆盖复审ZIP')
                uploaded=upload_once()
                if not isinstance(uploaded,dict) or len(uploaded)!=1:raise ValueError('需要一次上传一个完整覆盖复审ZIP')
                archive=base/'local-review-input.zip';archive.write_bytes(next(iter(uploaded.values())))
        review=obtain_review(base,upload_once,archive)
        if local:
            with zipfile.ZipFile(review) as z:
                if 'person_candidate_2.png' not in z.namelist():raise ValueError('局部分割需要完整覆盖复审包（含人物候选2）')
        s.report['input_archive']=str(review);s.report['stage']='asset-validation'
        py=env['python'];s.env=env['env']
        s.command([py,'-m','tools.layered_v4.cli','restore-review','--work',work,'--project',project,'--input',review])
        validation=json.loads((work/'assets-validated.json').read_text())
        if not validation.get('validated') or not validation.get('person5_rejected') or validation.get('pearl_candidate')!=2:raise ValueError('素材验证/人物Reject/固定珍珠条件没有满足')
        s.report['assets_validated']=True;s.report['asset_validation']=validation
        s.report['stage']='person-only-SAM';s.report['sam_command_attempted']=True
        print('素材已验证：固定珍珠2，开始局部候选审核。' if local else '素材已验证：固定珍珠2，只运行新的六个人物覆盖候选。')
        s.command([py,'-m','tools.layered_v4.cli','local-person-review' if local else 'person-review','--work',work,'--project',project,'--input',review])
        checks=json.loads((work/('local-checks.json' if local else 'person-checks.json')).read_text())
        if not checks.get('complete') or len(checks.get('candidates',[]))!=(18 if local else 6):raise RuntimeError('SAM候选输出不完整')
        s.report.update(passed=False,stage='awaiting-human-review',candidate_generation_complete=True,
            message=('局部候选与组合预览已生成，全部待人工审核。' if local else '六个人物覆盖候选已完整生成，尚未人工通过。')+'请发送复审ZIP；未运行Alpha、背景或最终打包。')
    except (Exception,KeyboardInterrupt) as exc:
        s.report.update(passed=False,candidate_generation_complete=False,error=str(exc),traceback=traceback.format_exc(),
            message='【未完成分割：失败诊断包】阶段：'+s.report.get('stage','unknown')+'。'+str(exc)+'。请把下载ZIP发给ChatGPT，不需要阅读Python日志。')
    finally:
        execution=work/'segment-execution.json'
        if execution.exists():
            s.report['sam_execution']=json.loads(execution.read_text());s.report['models_started']=bool(s.report['sam_execution'].get('executed'))
        out=exporter(work,s)
    print(s.report['message'])
    return {'zip':str(out),'work':str(work),'passed':False,'candidate_generation_complete':s.report['candidate_generation_complete']}


def export_local(work,session):
    from .diagnostics import redact
    p=Path(work);out=session.base/'FloatDeck-person-local-rereview.zip'
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        complete=session.report.get('candidate_generation_complete',False)
        z.writestr('00_请先阅读_运行结果.txt',('候选完整生成，尚未人工通过' if complete else '未完成分割：失败诊断包')+'\n'+redact(session.report.get('message',''),session.env))
        z.writestr('person-session-report.json',redact(json.dumps(session.report,ensure_ascii=False,indent=2),session.env))
        for f in sorted(p.rglob('*')):
            if f.is_file() and f.suffix in ('.png','.jpg','.json','.md'):
                if f.suffix in ('.json','.md'):z.writestr(str(f.relative_to(p)),redact(f.read_text(),session.env))
                else:z.write(f,str(f.relative_to(p)))
    return out

"""One-click environment preparation; stdlib only, never loads model weights."""
import json,os,re,shutil,subprocess,sys,time,traceback,zipfile
from pathlib import Path

def redact(text,env=None):
    text=str(text)
    for k,v in (env or {}).items():
        if re.search(r'token|password|secret|credential|api.?key|authorization',k,re.I) and len(v)>=6:
            text=text.replace(v,'[REDACTED]')
    text=re.sub(r'(https?://)[^\s/@]+:[^\s/@]+@',r'\1[REDACTED]@',text)
    text=re.sub(r'(?i)Bearer\s+[^\s\"\x27]+','Bearer [REDACTED]',text)
    text=re.sub(r'(?i)((?:token|password|secret|api[_-]?key|authorization|signature|credential)[\w-]*[\s\"\x27]*[:=][\s\"\x27]*)([^\s\"\x27,&}]+)',r'\1[REDACTED]',text)
    text=re.sub(r'\b(?:hf_|ghp_|github_pat_)[A-Za-z0-9_]+','[REDACTED]',text)
    return text

def classify(stage,error):
    missing=re.search(r"No (?:module named|package metadata was found for) ['\"]?([\w.-]+)",error)
    if missing:return f'检测到缺少依赖 {missing.group(1)}，已停止。请直接发送诊断 ZIP，由我修复；无需手动运行命令。'
    if stage in ('repository','bootstrap'):
        return '代码或启动环境准备失败。请稍后重新点击本单元；若仍失败，将诊断 ZIP 发给我。'
    if stage=='python':return '目标解释器不是可用的 Python 3.12。已停止；请将诊断 ZIP 发给我，不需要手动改命令。'
    if stage in ('cache','disk'):return '缓存不可写或磁盘空间不足。请在 Colab 更换干净运行时或释放旧文件后重试；不要删除未备份素材。'
    if stage=='vram':return 'GPU 显存不足（要求总量≥14 GiB、空闲≥10 GiB）。请重启 Colab 运行时，只运行本单元。'
    if stage in ('cuda','cuda-torchvision-operator'):return 'CUDA 或 PyTorch/torchvision GPU 运算不可用。请确认“更改运行时类型”选择 T4；重启后重试，仍失败发送诊断 ZIP。'
    if stage in ('install','pip-check','dependencies','dependency-imports'):
        if re.search('No space left|disk quota',error,re.I):return classify('disk',error)
        return '依赖安装、导入或一致性检查失败，已停止且未忽略冲突。请直接发送诊断 ZIP，由我继续修复。'
    return '检查未通过，已自动保存具体原因。请直接发送诊断 ZIP，不需要阅读日志。'

class DiagnosticSession:
    def __init__(self,base,root,env=None):
        self.base=Path(base);self.root=Path(root);self.env=dict(env or os.environ)
        self.directory=self.base/'diagnostics'/str(time.time_ns());self.directory.mkdir(parents=True)
        self.report={'passed':False,'stage':'bootstrap','commands':[],'gpu_verified':False,
                     'kernel_python':sys.executable,'kernel_version':sys.version,'observations':{},'models_started':False}
    def command(self,args,check=True):
        args=list(map(str,args))
        try:
            p=subprocess.run(args,cwd=self.root,env=self.env,capture_output=True,text=True,errors='replace')
            item={'argv':args,'returncode':p.returncode,'stdout':p.stdout,'stderr':p.stderr}
        except OSError as exc:
            item={'argv':args,'returncode':None,'stdout':'','stderr':str(exc)}
            self.report['commands'].append(item);raise
        self.report['commands'].append(item)
        if check and p.returncode:raise subprocess.CalledProcessError(p.returncode,args,p.stdout,p.stderr)
        return p
    def finish(self):
        text=redact(json.dumps(self.report,ensure_ascii=False,indent=2),self.env)
        # Export ONLY our generated diagnostic data; no environment dump, cache, user images or arbitrary files.
        (self.directory/'report.json').write_text(text)
        zip_path=self.base/'FloatDeck-environment-diagnostics.zip'
        with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED) as z:
            z.writestr('report.json',text)
            z.writestr('中文结论.txt',redact(self.report.get('message',''),self.env))
            for i,item in enumerate(self.report['commands']):
                for stream in ['stdout','stderr']:z.writestr(f'commands/{i:02d}.{stream}.txt',redact(item[stream],self.env))
        print(self.report.get('message','检查结束'))
        print('诊断文件：',zip_path)
        return zip_path

PROBE="""import sys,json,importlib.metadata as m
names=json.loads(sys.argv[1]);versions={}
for name in names:
 try: versions[name]=m.version(name)
 except m.PackageNotFoundError: versions[name]=None
print(json.dumps({'python':sys.executable,'version':list(sys.version_info[:2]),'packages':versions}))
"""

def expected_versions(root):
    pins={}
    for line in (Path(root)/'tools/layered_v4/requirements-colab.txt').read_text().splitlines():
        line=line.strip()
        if line and not line.startswith('#'):
            name,version=line.split('==');pins[name]=version
    return {'torch':'2.6.0+cu124','torchvision':'0.21.0+cu124',**pins}

def install_plan(expected,installed):
    gpu=any(installed.get(n)!=expected[n] for n in ['torch','torchvision'])
    other=[f'{n}=={v}' for n,v in expected.items() if n not in ('torch','torchvision') and installed.get(n)!=v]
    return gpu,other

def prepare(session):
    """Always package a report. All installs target the dedicated Python, never the kernel."""
    s=session;report=s.report;py=s.base/'python312/bin/python';cache=s.base/'cache';work=s.base/'project-001'
    report['target_python']=str(py)
    try:
        report['stage']='cache';cache.mkdir(parents=True,exist_ok=True)
        s.env.update(HF_HOME=str(cache),HF_HUB_DISABLE_TELEMETRY='1',TOKENIZERS_PARALLELISM='false',PIP_DISABLE_PIP_VERSION_CHECK='1')
        f=cache/'.diagnostic-write-test';f.write_text('ok');f.unlink()
        free=shutil.disk_usage(cache).free/2**30
        report['observations'].update(cache=str(cache),cache_writable=True,free_disk_gib=free)
        smi=shutil.which('nvidia-smi')
        if smi:
            p=s.command([smi,'--query-gpu=name,memory.total,memory.free,driver_version','--format=csv,noheader'],check=False)
            report['observations']['nvidia_smi']=p.stdout or p.stderr
        else:report['observations']['nvidia_smi']='command absent; actual CUDA test remains required'
        report['stage']='disk'
        if free<20:raise RuntimeError(f'Need >=20 GiB free disk; observed {free:.3f} GiB')
        report['stage']='python'
        if not py.exists():
            base=shutil.which('python3.12')
            if sys.version_info[:2]==(3,12):base=sys.executable
            if not base:
                uv=s.command([sys.executable,'-m','uv','--version'],check=False)
                if uv.returncode:s.command([sys.executable,'-m','pip','install','uv==0.6.3'])
                s.command([sys.executable,'-m','uv','python','install','3.12'])
                base=s.command([sys.executable,'-m','uv','python','find','3.12']).stdout.strip()
            s.command([base,'-m','venv',str(py.parent.parent)])
        pins=expected_versions(s.root)
        info=json.loads(s.command([py,'-c',PROBE,json.dumps(list(pins))]).stdout)
        report['observations']['before_install']=info
        if info['version']!=[3,12]:raise RuntimeError('Dedicated target interpreter must be Python 3.12')
        gpu,other=install_plan(pins,info['packages']);report['install_plan']={'gpu_pair':gpu,'other':other}
        report['stage']='install'
        if gpu:s.command([py,'-m','pip','install','torch==2.6.0+cu124','torchvision==0.21.0+cu124','--index-url','https://download.pytorch.org/whl/cu124'])
        if other:s.command([py,'-m','pip','install',*other])
        report['observations']['after_install']=json.loads(s.command([py,'-c',PROBE,json.dumps(list(pins))]).stdout)
        if report['observations']['after_install']['packages']!=pins:raise RuntimeError('Installed versions do not match pinned requirements; stop before CUDA')
        report['stage']='pip-check';s.command([py,'-m','pip','check'])
        report['stage']='preflight';work.mkdir(exist_ok=True)
        preflight=work/'preflight.json';preflight.unlink(missing_ok=True)
        p=s.command([py,'-m','tools.layered_v4.cli','preflight','--work',work],check=False)
        if preflight.exists():
            report['preflight']=json.loads(preflight.read_text());report['stage']=report['preflight']['stage']
        if p.returncode:raise subprocess.CalledProcessError(p.returncode,p.args,p.stdout,p.stderr)
        pf=report.get('preflight',{})
        if not pf.get('passed') or not pf.get('gpu_probe_passed') or pf.get('basic_only'):raise RuntimeError('Full GPU preflight success record missing')
        report.update(passed=True,gpu_verified=True,stage='complete',message='环境检查通过：Python 3.12、依赖与实际 CUDA 小算子检查成功。本次未启动分割或生成模型，请停在此处。')
    except Exception as exc:
        report['error']=str(exc);report['traceback']=traceback.format_exc()
        details=str(exc)+'\n'+str(getattr(exc,'stderr','') or '')+'\n'+str(getattr(exc,'output','') or '')
        report['message']=classify(report['stage'],details)
    finally:
        try:report['generator_commit']=s.command(['git','rev-parse','HEAD'],check=False).stdout.strip()
        except OSError:report['generator_commit']='unavailable'
        path=s.finish()
    return {'passed':report['passed'],'zip':str(path),'python':str(py),'env':s.env,'work':str(work)}

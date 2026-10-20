"""Standard-library-only Colab subprocess diagnostics, no models or GPU imports."""
import json,shlex,subprocess,sys,time
from pathlib import Path

def run_logged(argv,*,cwd,env,log_dir):
    argv=list(map(str,argv));logs=Path(log_dir);logs.mkdir(parents=True,exist_ok=True)
    stem=logs/f'{time.time_ns()}'
    print('COMMAND:',shlex.join(argv),flush=True)
    try:
        result=subprocess.run(argv,cwd=cwd,env=env,capture_output=True,text=True,errors='replace')
    except OSError as exc:
        stem.with_suffix('.launch-error.txt').write_text(str(exc));raise
    stem.with_suffix('.stdout.txt').write_text(result.stdout)
    stem.with_suffix('.stderr.txt').write_text(result.stderr)
    stem.with_suffix('.json').write_text(json.dumps({'argv':argv,'cwd':str(cwd),'returncode':result.returncode},indent=2))
    print('EXIT CODE:',result.returncode,'LOG:',stem,flush=True)
    if result.stdout:print('--- FULL STDOUT ---\n'+result.stdout,flush=True)
    if result.stderr:print('--- FULL STDERR ---\n'+result.stderr,file=sys.stderr,flush=True)
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode,argv,output=result.stdout,stderr=result.stderr)
    return result

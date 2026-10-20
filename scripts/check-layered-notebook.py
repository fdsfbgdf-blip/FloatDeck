"""Validate Notebook JSON, Python syntax and staged interpreter/review wiring; no GPU claims."""
import argparse,ast,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--require-nbformat',action='store_true');args=p.parse_args()
path=Path('notebooks/FloatDeck_V4_Layered_Generator.ipynb');nb=json.loads(path.read_text())
assert nb['nbformat']==4 and nb['nbformat_minor']==5
ids=set();source=[]
for c in nb['cells']:
    assert c['id'] not in ids;ids.add(c['id'])
    if c['cell_type']=='code':
        assert c['execution_count'] is None and c['outputs']==[], 'unrun notebook must not contain fabricated model output'
        s=''.join(c['source']);ast.parse(s);source.append(s)
joined='\n'.join(source)
for token in ["[PY,'-m','pip'", "[PY,'-m','tools.layered_v4.cli'", "run('preflight')", "run('segment')", "run('matte')", "run('background')",'MASKS_REVIEWED=False',"FINAL_REVIEWED=all",'ACCEPT_UNKNOWN_PERSON_BEHIND_PEARL=False',"SELECT={'person':None,'pearl':None}","run('check-segmentation')","run('approve-segmentation','--reviewed')",'CONTINUE_AFTER_SEGMENTATION=False',"sam_prompts"]:
    assert token in joined,token
assert 'from tools.layered_v4.process import run_logged' in joined
assert "checked([PY,'-m','pip','check'])" in joined
assert joined.index('def checked(')<joined.index("checked([PY,'-m','pip'")<joined.index("run('preflight')")
assert 'LOCK_MODEL_REVISIONS=False' in joined
assert '[sys.executable,\' -m\'' not in joined
try:
    import nbformat
    nbformat.validate(nbformat.read(str(path),as_version=4));strict=True
except ImportError:
    if args.require_nbformat:raise
    strict=False
print(json.dumps({'notebook_json_and_python_ast':'passed','strict_nbformat':strict,'code_cells':len(source),
                  'gpu_models_executed':False,'quality_validated':False}))

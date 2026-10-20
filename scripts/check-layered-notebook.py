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
for token in ["[PY,'-m','tools.layered_v4.cli'", "run('segment')", "run('matte')", "run('background')",'MASKS_REVIEWED=False',"FINAL_REVIEWED=all",'ACCEPT_UNKNOWN_PERSON_BEHIND_PEARL=False',"SELECT={'person':None,'pearl':None}","run('check-segmentation')","run('approve-segmentation','--reviewed')",'CONTINUE_AFTER_SEGMENTATION=False',"sam_prompts"]:
    assert token in joined,token
first=source[0]
for token in ['一键环境检查与诊断','diagnostics.prepare(session)',"files.download(str(DIAGNOSTIC_ZIP))",'ALLOW_ASSET_STAGES=False','ENV_READY=False']:
    assert token in first,token
person=source[1]
for token in ['人物覆盖复审','coverage_entry.run_coverage(','CONTINUE_AFTER_SEGMENTATION=False',"files.download(str(PERSON_ZIP))"]:assert token in person,token
assert 'run_approved_stage(' not in joined
assert 'require_environment(' not in person
assert person.count('files.upload()')==1
assert '00_请先阅读_运行结果.txt' in person and 'candidate_generation_complete' in person
assert 'importlib.reload(diagnostics)' in person and 'upload_once=upload_review_once' in person
sam=source[2]
for token in ['一键 SAM 分割与预览','sam_export.require_environment(project,python)','sam_export.run_export(',"files.download(str(SAM_ZIP))"]:assert token in sam,token
assert 'diagnostics.prepare(' not in sam and "'pip'" not in sam
for s in source[3:]:assert s.startswith("if not globals().get('ALLOW_ASSET_STAGES',False)"),'diagnosis-only guard missing'
assert first.index("'pull','--ff-only'")<first.index('diagnostics.prepare(session)')
assert 'lock-models' not in first and "run('segment')" not in first
module=ast.parse(Path('tools/layered_v4/diagnostics.py').read_text())
expected=next(t for t in module.body if isinstance(t,ast.FunctionDef) and t.name=='redact')
actual=next(t for t in ast.parse(first).body if isinstance(t,ast.FunctionDef) and t.name=='redact')
assert ast.dump(expected)==ast.dump(actual),'bootstrap redaction must match exporter'
try:
    import nbformat
    nbformat.validate(nbformat.read(str(path),as_version=4));strict=True
except ImportError:
    if args.require_nbformat:raise
    strict=False
print(json.dumps({'notebook_json_and_python_ast':'passed','strict_nbformat':strict,'code_cells':len(source),
                  'gpu_models_executed':False,'quality_validated':False}))

"""Synthetic CPU review/export checks, never real SAM quality acceptance."""
import copy,io,json,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import numpy as np
from PIL import Image
from .cli import DEFAULT
from .core import save_mask,digest
from .sam_export import candidate_review,require_environment,run_export,export_zip,execute
from .diagnostics import DiagnosticSession
ROOT=Path(__file__).resolve().parents[2]

class SamExportTests(unittest.TestCase):
    def make_candidates(self,p):
        p.mkdir(parents=True,exist_ok=True)
        Image.new('RGB',(96,160),(40,80,120)).save(p/'source.png');Image.new('L',(96,160),100).save(p/'source_depth.png')
        (p/'config.json').write_text(json.dumps(DEFAULT))
        for name in ['person','pearl']:
            a=np.zeros((160,96));a[50:80,30:70]=1
            for i in range(3):save_mask(p/f'{name}_candidate_{i}.png',a if i else np.zeros_like(a))
            (p/f'{name}_proposal.json').write_text(json.dumps({'scores':[.7,.8,.9],'accepted':False}))
    def test_six_candidates_chinese_previews_and_no_approval(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.make_candidates(p);h=digest(p/'source.png');r=candidate_review(p)
            self.assertEqual(len(r['candidates']),6);self.assertFalse(r['human_approved']);self.assertIsNone(r['selected'])
            self.assertFalse(r['candidates'][0]['check']['passed']);self.assertEqual(h,digest(p/'source.png'))
            self.assertEqual(len(list((p/'segmentation-previews').glob('*_review_*.jpg'))),16)
            self.assertFalse((p/'person_mask.png').exists())
    def test_pack_native_masks_allowlist_and_failure_logs(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'work';self.make_candidates(p);candidate_review(p)
            (p/'private-token.txt').write_text('do not export')
            s=DiagnosticSession(d,ROOT,{'HF_TOKEN':'secret-123456'})
            s.report['commands']=[{'argv':['sam'],'returncode':1,'stdout':'out','stderr':'secret-123456'}]
            zpath=export_zip(p,s)
            with zipfile.ZipFile(zpath) as z:
                self.assertNotIn('private-token.txt',z.namelist())
                self.assertNotIn('secret-123456',z.read('sam-session-report.json').decode())
                for name in ['person','pearl']:
                    for i in range(3):
                        im=Image.open(io.BytesIO(z.read(f'{name}_candidate_{i}.png')));self.assertEqual(im.size,(96,160));self.assertEqual(im.mode,'L')
    def test_unpassed_environment_stops_and_exports(self):
        with tempfile.TemporaryDirectory() as d:
            with patch.object(DiagnosticSession,'command') as cmd:
                r=run_export(d,ROOT)
            self.assertFalse(r['passed']);cmd.assert_not_called()
            with zipfile.ZipFile(r['zip']) as z:self.assertFalse(json.loads(z.read('sam-session-report.json'))['passed'])
    def test_interpreter_identity_and_basic_not_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);py=p/'python';py.touch()
            record={'passed':True,'gpu_probe_passed':True,'basic_only':True,'python':str(py)}
            (p/'preflight.json').write_text(json.dumps(record))
            with self.assertRaises(RuntimeError):require_environment(p,py)
            record['basic_only']=False;record['python']='/wrong/python';(p/'preflight.json').write_text(json.dumps(record))
            with self.assertRaises(RuntimeError):require_environment(p,py)
    def test_execute_reuses_lock_and_preserves_project(self):
        # Fake model only: verifies plumbing, not real segmentation accuracy.
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);project=root/'project';project.mkdir();work=root/'run'
            Image.new('RGB',(96,160),(50,60,70)).save(project/'source.png');Image.new('L',(96,160),120).save(project/'source_depth.png')
            (project/'review.json').write_text('prior-review');(project/'models.lock.json').write_text(json.dumps({'segment':{'id':'facebook/sam-vit-base','revision':'a'*40},'matte':{'id':'keep','revision':'b'*40}}))
            before={n:digest(project/n) for n in ['source.png','source_depth.png','review.json','models.lock.json']}
            def fake_segment(p,cfg):
                for name in ['person','pearl']:
                    for i in range(3):save_mask(p/f'{name}_candidate_{i}.png',np.ones((160,96))*.0)
                    (p/f'{name}_proposal.json').write_text(json.dumps({'scores':[.2,.3,.4]}))
            with patch('tools.layered_v4.models.segment',side_effect=fake_segment) as sam:execute(work,project)
            sam.assert_called_once();self.assertEqual(before,{n:digest(project/n) for n in before})
            self.assertEqual(json.loads((work/'config.json').read_text())['sam_prompts'],DEFAULT['sam_prompts'])

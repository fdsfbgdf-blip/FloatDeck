import json,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import numpy as np
from PIL import Image
from .local_review import PROFILES,rectangle,constrain,execute_local
from .core import save_mask
from . import test_coverage_entry as fixtures
ROOT=fixtures.ROOT
from .coverage_entry import export_local
from .diagnostics import DiagnosticSession

class LocalTests(unittest.TestCase):
    def test_profiles_native_points_and_bounded_leakage(self):
        size=(200,300);raw=np.ones((300,200),bool);pearl=np.zeros_like(raw);pearl[240:]=True
        for profile in PROFILES:
            for x,y in profile['positive']:
                self.assertTrue(profile['box'][0]<=x<=profile['box'][2]);self.assertTrue(profile['box'][1]<=y<=profile['box'][3])
            mask,check=constrain(raw,size,profile,pearl)
            self.assertFalse((mask&~rectangle(size,profile['box'])).any());self.assertFalse((mask&pearl).any())
            self.assertFalse(check['passed']);self.assertFalse(check['human_approved'])
        with self.assertRaises(ValueError):constrain(np.ones((2,2)),size,PROFILES[0],pearl)
    def test_local_pipeline_exports_raw_bounded_and_unapproved_compositions(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);archive=p/'review.zip';archive.write_bytes(fixtures.CoverageRecoveryTests().package())
            with zipfile.ZipFile(archive,'a') as z:
                import io
                raw=io.BytesIO();Image.new('L',(40,50),255).save(raw,format='PNG');z.writestr('person_candidate_2.png',raw.getvalue())
            # Determine native size from fixture rather than resize any input.
            with zipfile.ZipFile(archive) as z:
                import io
                size=Image.open(io.BytesIO(z.read('source.png'))).size
            with zipfile.ZipFile(archive) as z:files={n:z.read(n) for n in z.namelist() if n!='person_candidate_2.png'}
            raw=io.BytesIO();Image.new('L',size,255).save(raw,format='PNG');files['person_candidate_2.png']=raw.getvalue()
            with zipfile.ZipFile(archive,'w') as z:
                for n,data in files.items():z.writestr(n,data)
            work=p/'work';work.mkdir()
            def fake_segment(work,config,targets,prompt_sets):
                self.assertEqual(targets,('person',));self.assertEqual(len(prompt_sets),6)
                for i in range(18):save_mask(Path(work)/f'person_candidate_{i}.png',np.ones((size[1],size[0]),bool))
            with patch('tools.layered_v4.models.segment',side_effect=fake_segment):result=execute_local(work,p/'project',archive)
            self.assertEqual(len(result['candidates']),18);self.assertFalse(result['passed']);self.assertIsNone(result['selected'])
            self.assertFalse((work/'person_mask.png').exists());self.assertEqual((work/'pearl_mask.png').read_bytes(),files['pearl_candidate_2.png'])
            self.assertTrue((work/'local-previews/composition_2_reference2_missing.jpg').exists())
            s=DiagnosticSession(p,ROOT);s.report['candidate_generation_complete']=True
            out=export_local(work,s)
            with zipfile.ZipFile(out) as z:self.assertIn('local-checks.json',z.namelist());self.assertIn('local-masks/core_candidate_0_removed.png',z.namelist())
    def test_failure_zip_is_explicit(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);s=DiagnosticSession(p,ROOT);s.report['message']='环境失败'
            with zipfile.ZipFile(export_local(p/'empty',s)) as z:self.assertIn('未完成分割',z.read('00_请先阅读_运行结果.txt').decode())

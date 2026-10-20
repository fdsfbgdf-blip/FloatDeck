"""CPU selection/cleanup tests; no fabricated GPU inference."""
import io,json,tempfile,unittest,zipfile,hashlib
from pathlib import Path
from unittest.mock import patch
import numpy as np
from PIL import Image
from .approved_layers import cleanup_person,prepare_selection,validate_selection,export_stage,execute_alpha,APPROVED_INPUTS
from .core import digest,save_mask,load_mask
from .cli import DEFAULT
from .segmentation import require_review,PERSON_REVIEW_PROFILES

class ApprovedLayerTests(unittest.TestCase):
    def make_input(self,p):
        p=Path(p);a=np.zeros((160,96),np.uint8);a[48:110,40:58]=255;pearl=np.zeros_like(a);pearl[120:150,25:75]=255
        data={}
        for name,im in [('source.png',Image.new('RGB',(96,160),(70,100,130))),('source_depth.png',Image.new('L',(96,160),100)),('person_candidate_5.png',Image.fromarray(a)),('pearl_candidate_2.png',Image.fromarray(pearl))]:
            b=io.BytesIO();im.save(b,format='PNG');data[name]=b.getvalue()
        cfg=dict(DEFAULT,person_review_profiles=PERSON_REVIEW_PROFILES)
        with zipfile.ZipFile(p/'input.zip','w') as z:
            for n,b in data.items():z.writestr(n,b)
            z.writestr('config.json',json.dumps(cfg));z.writestr('models.lock.json',json.dumps({'segment':{'id':'facebook/sam-vit-base','revision':'a'*40}}))
        expected={('person_selected_mask.png' if n=='person_candidate_5.png' else n):hashlib.sha256(b).hexdigest() for n,b in data.items()}
        return p/'input.zip',expected
    def test_cleanup_keeps_detached_leg_and_never_grows(self):
        a=np.zeros((589,330),bool);a[175:420,135:190]=True;a[320:340,232:242]=True;a[340:345,98:103]=True;a[440:442,290:292]=True
        c,r=cleanup_person(a)
        self.assertTrue(c[330,237]);self.assertFalse(c[342,100]);self.assertFalse(c[441,291]);self.assertFalse(np.any(c&~a));self.assertTrue(np.all(c[175:420,135:190]))
        self.assertEqual(r['added_pixels'],0)
    def test_user_approval_bound_to_exact_assets_and_no_fake_basic_pass(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);archive,expected=self.make_input(p)
            with patch.dict(APPROVED_INPUTS,expected,clear=True):
                s=prepare_selection(archive,p/'work',p/'project');validate_selection(p/'work')
                cfg=json.loads((p/'work/config.json').read_text());require_review(p/'work',cfg)
                self.assertEqual(s['person']['candidate_index'],5);self.assertTrue(s['person']['human_approved']);self.assertEqual(s['pearl']['candidate_index'],2)
                self.assertFalse((p/'work/review.json').exists());self.assertFalse((p/'work/background.png').exists())
                self.assertFalse(json.loads((p/'work/segmentation-check.json').read_text())['passed'])
    def test_different_assets_not_automatically_approved(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);archive,_=self.make_input(p)
            with self.assertRaisesRegex(ValueError,'不一致'):prepare_selection(archive,p/'work',p/'project')
            self.assertFalse((p/'project/fixed-pearl.json').exists())
    def test_changed_cleaned_mask_invalidates_review(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);archive,expected=self.make_input(p)
            with patch.dict(APPROVED_INPUTS,expected,clear=True):
                prepare_selection(archive,p/'work',p/'project');Image.new('L',(96,160)).save(p/'work/person_mask.png')
                with self.assertRaises(ValueError):validate_selection(p/'work')
    def test_alpha_stage_exports_real_rgba_but_not_auto_approval(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);archive,expected=self.make_input(p)
            with patch.dict(APPROVED_INPUTS,expected,clear=True):prepare_selection(archive,p/'work',p/'project')
            work=p/'work';lock=json.loads((work/'models.lock.json').read_text());lock['matte']={'id':'hustvl/vitmatte-small-composition-1k','revision':'b'*40};(work/'models.lock.json').write_text(json.dumps(lock))
            def fake_matte(work,cfg):
                for n in ['person','pearl']:
                    a=load_mask(work/f'{n}_mask.png',(96,160));save_mask(work/f'{n}_alpha.png',a);save_mask(work/f'{n}_protect.png',a)
            with patch('tools.layered_v4.models.matte',side_effect=fake_matte):r=execute_alpha(work)
            self.assertFalse(r['alpha_human_approved']);self.assertFalse(r['background_executed'])
            self.assertEqual(Image.open(work/'person_rgba_ALPHA_REVIEW.png').mode,'RGBA')
            z=export_stage(work,p/'stage.zip')
            with zipfile.ZipFile(z) as a:self.assertIn('person_mask.png',a.namelist());self.assertIn('person_alpha.png',a.namelist());self.assertNotIn('background.png',a.namelist())
    def test_actual_approved_mask_cleanup_matches_delivered_result(self):
        # Actual provided binary mask regression, no synthetic depth or GPU claim.
        root=Path(__file__).resolve().parents[2]/'work/v4/selected5-mask-download'
        if not root.exists():self.skipTest('Private user mask fixture is not published; actual local regression recorded in report')
        original=np.array(Image.open(root/'person_selected_mask.png'))>0
        expected=np.array(Image.open(root/'person_mask.png'))>0
        clean,r=cleanup_person(original)
        np.testing.assert_array_equal(clean,expected)
        self.assertEqual(r['removed_pixels'],22836);self.assertEqual(r['added_pixels'],0)
        self.assertEqual(r['components_after'],35)
        self.assertTrue(np.all(clean[1500:1830,1120:1300][original[1500:1830,1120:1300]]))
        self.assertEqual(digest(root/'pearl_mask.png'),digest(root/'pearl_candidate_2.png'))

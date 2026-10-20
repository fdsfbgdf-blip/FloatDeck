"""CPU/synthetic regression tests; no actual SAM inference or visual quality claim."""
import io,json,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import numpy as np
from PIL import Image
from .core import digest,save_mask
from .person_review import import_review,execute_person,export_person
from .diagnostics import DiagnosticSession
from .segmentation import PERSON_REVIEW_PROFILES,person_review_order,pixels
ROOT=Path(__file__).resolve().parents[2]

class PersonReviewTests(unittest.TestCase):
    def archive(self,root,extra=None):
        p=Path(root)/'old.zip'
        with zipfile.ZipFile(p,'w') as z:
            pearl=np.zeros((160,96),np.uint8);pearl[115:145,20:80]=255
            for name,im in [('source.png',Image.new('RGB',(96,160),(40,50,60))),('source_depth.png',Image.new('L',(96,160),100)),('pearl_candidate_2.png',Image.fromarray(pearl)),('person_candidate_0.png',Image.new('L',(96,160),255))]:
                b=io.BytesIO();im.save(b,format='PNG');z.writestr(name,b.getvalue())
            z.writestr('models.lock.json',json.dumps({'segment':{'id':'facebook/sam-vit-base','revision':'a'*40}}))
            if extra:z.writestr(*extra)
        return p
    def test_person_only_preserves_exact_pearl_and_never_approves(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);project=root/'project';work=root/'run';archive=self.archive(root)
            def fake_segment(p,c,targets,prompt_sets):
                self.assertEqual(targets,('person',));self.assertEqual(len(prompt_sets),2)
                self.assertFalse(c['CONTINUE_AFTER_SEGMENTATION'])
                for i in range(6):save_mask(p/f'person_candidate_{i}.png',np.zeros((160,96)))
                (p/'person_proposal.json').write_text(json.dumps({'scores':[.9]*6}))
            with patch('tools.layered_v4.models.segment',side_effect=fake_segment):execute_person(work,project,archive)
            self.assertEqual(digest(work/'pearl_mask.png'),digest(work/'pearl_candidate_2.png'))
            self.assertEqual(digest(work/'pearl_mask.png'),digest(project/'pearl_mask.png'))
            self.assertFalse((work/'person_mask.png').exists());self.assertFalse((work/'review.json').exists())
            r=json.loads((work/'person-checks.json').read_text());self.assertEqual(len(r['candidates']),6);self.assertIsNone(r['selected']);self.assertFalse(r['human_approved'])
            self.assertEqual(len(list((work/'segmentation-previews').glob('person_review_*.jpg'))),16)
            z=export_person(work,DiagnosticSession(root,ROOT))
            with zipfile.ZipFile(z) as a:self.assertEqual(a.read('pearl_mask.png'),a.read('pearl_candidate_2.png'));self.assertIn('人物复审说明.md',a.namelist())
    def test_fixed_pearl_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);a=self.archive(p);project=p/'project'
            import_review(a,p/'first',project);(project/'pearl_mask.png').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError,'被修改'):import_review(a,p/'next',project)
    def test_review_zip_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);a=self.archive(p,('../escape','bad'))
            with self.assertRaisesRegex(ValueError,'不安全'):import_review(a,p/'work',p/'project')
    def test_ranking_ignores_iou_and_never_returns_selection(self):
        def c(iou,errors,occupancy):return {'iou_score':iou,'metrics':{'basic':{'passed':not errors,'errors':errors},'pearl_overlap_fraction':0,'background_roi_occupancy':{'platform':occupancy},'positive_coverage':1}}
        candidates=[c(.99,['leak'],.8),c(.2,[],.1),c(.8,[],.2)]
        self.assertEqual(person_review_order(candidates),[1,2,0])
    def test_focused_profiles_coordinates_and_exclusions(self):
        for p in PERSON_REVIEW_PROFILES:
            b,pts,labels=pixels(p,(1648,2944));self.assertGreater(b[0],0)
            self.assertGreaterEqual(len(p['negative']),10);self.assertGreaterEqual(len(p['positive']),10)
            self.assertLess(p['box'][3],.77)
    def test_person_only_segment_does_not_delete_pearl(self):
        from .models import segment
        from .cli import DEFAULT
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'pearl_mask.png').write_bytes(b'fixed');(p/'pearl_candidate_2.png').write_bytes(b'fixed')
            with patch('tools.layered_v4.models.gpu_stage',return_value=None):segment(p,DEFAULT,targets=('person',),prompt_sets=PERSON_REVIEW_PROFILES)
            self.assertEqual((p/'pearl_mask.png').read_bytes(),b'fixed');self.assertEqual((p/'pearl_candidate_2.png').read_bytes(),b'fixed')

    def test_generic_segment_cannot_replace_approved_pearl(self):
        from .models import segment
        from .cli import DEFAULT
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'fixed-pearl.json').write_text('{}');(p/'pearl_mask.png').write_bytes(b'approved')
            with self.assertRaisesRegex(RuntimeError,'locked'):segment(p,DEFAULT)
            self.assertEqual((p/'pearl_mask.png').read_bytes(),b'approved')

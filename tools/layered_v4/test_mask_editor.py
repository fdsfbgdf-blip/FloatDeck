import tempfile,unittest,json,zipfile
from pathlib import Path
from unittest.mock import patch
import numpy as np
from PIL import Image
from . import mask_editor as e
from .core import save_mask,digest

class EditorTests(unittest.TestCase):
    def test_local_update_preserves_every_outside_pixel_and_excludes_background(self):
        rng=np.random.default_rng(3);old=rng.random((30,20))>.5;roi=e.roi_mask((20,30),[5,8,15,20]);bg=np.zeros_like(old);bg[10,10]=True;pearl=np.zeros_like(old);pearl[11,10]=True
        out=e.merge_local(old,np.ones_like(old),roi,bg,pearl)
        np.testing.assert_array_equal(out[~roi],old[~roi]);self.assertFalse(out[10,10]);self.assertFalse(out[11,10])
    def test_brush_coordinate_validation_and_native_radius(self):
        bg,points,labels=e.marks((1648,2944),[{'label':0,'radius':2,'points':[[100,200],[110,200]]}]);self.assertEqual(bg.shape,(2944,1648));self.assertTrue(bg[200,105]);self.assertFalse(bg[210,105])
        with self.assertRaises(ValueError):e.marks((20,30),[{'label':1,'points':[[21,4]]}])
        with self.assertRaises(ValueError):e.roi_mask((20,30),[0,0,30,30])
    def fixture(self,p):
        Image.new('RGB',(20,30),'gray').save(p/'source.png');pearl=np.zeros((30,20),bool);pearl[-1,-1]=True;save_mask(p/'pearl_mask.png',pearl)
        Image.new('L',(20,30),128).save(p/'source_depth.png');save_mask(p/'pearl_candidate_2.png',pearl)
        (p/'models.lock.json').write_text(json.dumps({'segment':{'id':'facebook/sam-vit-base','revision':'a'*40}}))
        save_mask(p/'initial_0.png',np.zeros((30,20),bool))
        e.persist(p,{'source_sha256':digest(p/'source.png'),'pearl_sha256':digest(p/'pearl_mask.png'),'history':[],'cursor':-1,'human_approved':False,'passed':False,'events':[]})
        e.select_initial(p,0)
    def test_apply_undo_redo_reject_stale_and_export_progress(self):
        with tempfile.TemporaryDirectory() as d,patch.object(e,'SIZE',(20,30)):
            p=Path(d);self.fixture(p);mask=np.zeros((30,20),bool);mask[8:15,5:12]=True
            save_mask(p/'proposal_0.png',mask);save_mask(p/'proposal_background.png',np.zeros_like(mask))
            (p/'proposal.json').write_text(json.dumps({'revision':0,'request':{'roi':[5,8,12,15],'strokes':[]}}))
            e.apply(p,0);rev=e.current(p);e.move(p,-1);self.assertEqual(e.current(p),0);e.move(p,1);self.assertEqual(e.current(p),rev)
            with self.assertRaises(FileNotFoundError):e.apply(p,0)
            out=e.export(p)
            with zipfile.ZipFile(out) as z:
                self.assertIn('editor.json',z.namelist());self.assertIn('black_background.png',z.namelist());self.assertFalse(json.loads(z.read('editor.json'))['human_approved'])
    def test_reject_candidate_that_changes_protected_outside_roi(self):
        with tempfile.TemporaryDirectory() as d,patch.object(e,'SIZE',(20,30)):
            p=Path(d);self.fixture(p);save_mask(p/'proposal_0.png',np.ones((30,20),bool))
            (p/'proposal.json').write_text(json.dumps({'revision':0,'request':{'roi':[5,8,12,15]}}))
            with self.assertRaisesRegex(ValueError,'框外'):e.apply(p,0)
            self.assertEqual(e.current(p),0)

    def test_export_can_restore_progress_without_approval(self):
        with tempfile.TemporaryDirectory() as d,patch.object(e,'SIZE',(20,30)):
            p=Path(d)/'first';p.mkdir();self.fixture(p)
            (p/'draft.json').write_text(json.dumps({'roi':[5,8,12,15],'strokes':[{'label':0,'radius':2,'points':[[8,10]]}]}))
            archive=e.export(p);restored=Path(d)/'restored'
            s=e.initialize(restored,archive,Path(d)/'project')
            self.assertEqual(s['history'],[0]);self.assertFalse(s['human_approved']);self.assertFalse(s['passed'])
            self.assertEqual((restored/'draft.json').read_bytes(),(p/'draft.json').read_bytes())
            self.assertEqual((restored/'pearl_mask.png').read_bytes(),(p/'pearl_mask.png').read_bytes())

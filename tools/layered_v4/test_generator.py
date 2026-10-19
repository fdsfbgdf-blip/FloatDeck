"""CPU structural/mathematical tests. Synthetic fixtures are NEVER visual acceptance."""
import io,json,tempfile,unittest,zipfile,hashlib
from pathlib import Path
import numpy as np
from PIL import Image
from .core import *
from .cli import DEFAULT,seal_masks
from .validate import validate

class GeneratorTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.work=self.root/'work'
        w,h=96,160;y,x=np.mgrid[:h,:w]
        bg=np.stack([x+40,y//2+60,(x+y)//3+40],axis=2).astype('uint8')
        pa=((x>12)&(x<73)&(y>25)&(y<113)).astype(float)
        la=(((x-52)**2+(y-128)**2)<24**2).astype(float)
        pf=np.zeros((h,w,4),np.uint8);pf[...,:3]=[190,130,80];pf[...,3]=(pa*255).astype('uint8')
        lf=np.zeros_like(pf);lf[...,:3]=[210,220,240];lf[...,3]=(la*255).astype('uint8')
        source=np.rint(compose(bg,pf,lf)).astype('uint8')
        archive=self.root/'input.zip'
        with zipfile.ZipFile(archive,'w') as z:
            z.writestr('manifest.json',json.dumps({'format':'floatdeck-spatial','version':1,'name':'synthetic-only',
                'assets':{'image':'image.png','depth':'depth.png'},'depthDirection':'white-near','preset':{}}))
            for n,a in [('image.png',source),('depth.png',((x+y)%256).astype('uint8'))]:
                b=io.BytesIO();Image.fromarray(a).save(b,format='PNG');z.writestr(n,b.getvalue())
        import_v1(archive,self.work)
        for name,a in [('person',pa),('pearl',la)]:
            save_mask(self.work/f'{name}_alpha.png',a)
            save_mask(self.work/f'{name}_protect.png',ndi.binary_erosion(a,iterations=2))
        hashes=seal_masks(self.work)
        (self.work/'review.json').write_text(json.dumps({'mask_hashes':hashes,'masks_approved':True,'accepted':{}}))
        Image.fromarray(bg).save(self.work/'background.png')
        (self.work/'background_record.json').write_text(json.dumps({'provider':'synthetic-test','executed':False,
            'mask_sha256':digest(self.work/'background_hole.png'),'background_sha256':digest(self.work/'background.png')}))
        # Outside true foreground, synthetic source already equals known background.
        self.q=build(self.work,DEFAULT);self.archive=self.root/'draft.spatial-v2.zip'
        pack(self.work,self.archive,False)
    def tearDown(self):self.tmp.cleanup()
    def mutate(self,change):
        with zipfile.ZipFile(self.archive) as z:files={n:z.read(n) for n in z.namelist()}
        change(files)
        with zipfile.ZipFile(self.archive,'w') as z:
            for n,b in files.items():z.writestr(n,b)
    def update(self,files,name,content):
        files[name]=content;m=json.loads(files['manifest.json']);m['assets'][name]={'sha256':hashlib.sha256(content).hexdigest(),'bytes':len(content)}
        files['manifest.json']=json.dumps(m).encode()
    def test_draft_structure_and_exact_opaque_detail(self):
        r=validate(self.archive,True);self.assertTrue(r['structure_passed']);self.assertLess(r['zero_mae'],.01)
        self.assertFalse(r['human_approval_recorded']);self.assertFalse(r['independent_visual_validation'])
        self.assertTrue(self.q['opaque_person_rgb_exact']);self.assertTrue(self.q['opaque_pearl_rgb_exact'])
    def test_draft_cannot_silently_pass_final(self):
        with self.assertRaises(ValueError):validate(self.archive)
        with self.assertRaises(ValueError):pack(self.work,self.root/'final.zip',True)
    def test_alpha_recovery_not_black_background(self):
        bg=np.full((4,4,3),40.);f=np.full_like(bg,180.);a=np.full((4,4),.4);original=a[...,None]*f+(1-a[...,None])*bg
        layer,_=unmix(original,bg,a);self.assertTrue(np.all(layer[...,3]==102))
        self.assertLess(abs(over(bg,layer)-original).max(),1)
    def test_hash_tampering_rejected(self):
        self.mutate(lambda f:f.__setitem__('background.png',f['background.png']+b'x'))
        with self.assertRaises(ValueError):validate(self.archive,True)
    def test_rgb_fake_transparency_rejected(self):
        def change(f):
            im=Image.open(io.BytesIO(f['person_rgba.png'])).convert('RGB');b=io.BytesIO();im.save(b,format='PNG');self.update(f,'person_rgba.png',b.getvalue())
        self.mutate(change)
        with self.assertRaisesRegex(ValueError,'channel'):validate(self.archive,True)
    def test_manifest_reference_rejected(self):
        def change(f):
            m=json.loads(f['manifest.json']);m['layers'][0]['image']='source.png';f['manifest.json']=json.dumps(m).encode()
        self.mutate(change)
        with self.assertRaisesRegex(ValueError,'layer references'):validate(self.archive,True)
    def test_unsafe_zip_rejected(self):
        self.mutate(lambda f:f.__setitem__('../escape.png',b'not image'))
        with self.assertRaisesRegex(ValueError,'unsafe'):validate(self.archive,True)
    def test_visible_background_changes_rejected(self):
        bg=np.array(Image.open(self.work/'background.png'));bg[0,0]=0;Image.fromarray(bg).save(self.work/'background.png')
        with self.assertRaisesRegex(ValueError,'visible pixels'):build(self.work,DEFAULT)
    def test_mask_edit_invalidates_review(self):
        a=np.array(Image.open(self.work/'person_alpha.png'));a[0,0]=255;Image.fromarray(a).save(self.work/'person_alpha.png')
        with self.assertRaisesRegex(ValueError,'stale'):pack(self.work,self.root/'bad.zip',False)
    def test_pearl_does_not_duplicate_into_person(self):
        p=np.array(Image.open(self.work/'person_rgba.png'));l=np.array(Image.open(self.work/'pearl_rgba.png'))
        self.assertFalse(np.any((p[...,3]>0)&(l[...,3]>0)));self.assertGreater(self.q['person_occluded_pixels'],0)
    def test_forged_zero_error_rejected(self):
        def change(f):
            q=json.loads(f['quality.json']);q['zero_bad_fraction']=.5;self.update(f,'quality.json',json.dumps(q).encode())
        self.mutate(change)
        with self.assertRaisesRegex(ValueError,'forged'):validate(self.archive,True)
    def test_original_input_unchanged(self):
        before=digest(self.root/'input.zip');import_v1(self.root/'input.zip',self.root/'another');self.assertEqual(before,digest(self.root/'input.zip'))
    def test_native_mask_size_required(self):
        path=self.root/'wrong.png';Image.new('L',(48,80)).save(path)
        with self.assertRaisesRegex(ValueError,'coordinates'):load_mask(path,(96,160))
    def test_duplicate_archive_entry_rejected(self):
        path=self.root/'duplicate.zip'
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with zipfile.ZipFile(path,'w') as z:
                z.writestr('manifest.json','{}');z.writestr('manifest.json','{}')
        with self.assertRaisesRegex(ValueError,'duplicate'):import_v1(path,self.root/'dup')
    def test_native_tile_coverage(self):
        from .models import starts
        for length,tile,overlap in [(1648,768,128),(2944,768,128),(1648,1024,192),(2944,1024,192),(400,768,128)]:
            cover=np.zeros(length,bool)
            for x in starts(length,tile,overlap):cover[x:x+tile]=True
            self.assertTrue(cover.all())
    def test_trimaps_keep_clipped_opaque_border(self):
        a=np.zeros((30,30));a[10:,5:25]=1
        t=trimap(a,2);self.assertEqual(t[-1,15],255);self.assertEqual(t[0,0],0)
    def test_model_conditioning_cannot_overwrite_visible_pixels(self):
        src=np.full((20,30,3),33,np.uint8);proposal=np.full_like(src,240)
        hole=np.zeros((20,30),bool);hole[7:12,13:18]=True
        out=restore_visible(src,proposal,hole)
        self.assertTrue(np.array_equal(out[~hole],src[~hole]));self.assertTrue(np.all(out[hole]==240))
    def test_writeback_registration_mismatch_rejected(self):
        with self.assertRaisesRegex(ValueError,'registration'):restore_visible(np.zeros((20,30,3)),np.zeros((10,30,3)),np.zeros((20,30),bool))
    def test_synthetic_demo_export(self):
        # Optional explicitly named schema fixture for CI. Never a user-art model result.
        import os,shutil
        target=os.environ.get('FLOATDECK_SCHEMA_FIXTURE')
        if target:
            Path(target).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(self.archive,target)

if __name__=='__main__':unittest.main()

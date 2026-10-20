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



class SegmentationTests(unittest.TestCase):
    def test_native_xy_not_swapped_or_double_scaled(self):
        from .segmentation import pixels
        b,p,l=pixels({'box':[.1,.2,.9,.8],'positive':[[.5,.3]],'negative':[[.2,.9]]},(1648,2944))
        np.testing.assert_allclose(b,[164.8,588.8,1483.2,2355.2]);np.testing.assert_allclose(p,[[824,883.2],[329.6,2649.6]])
        self.assertEqual(l,[1,0])
    def test_scene_boxes_and_seed_validation(self):
        from .segmentation import pixels,PROMPTS
        from .cli import check_config
        check_config(DEFAULT)
        self.assertLess(PROMPTS['person']['box'][3],.77)
        self.assertGreater(PROMPTS['pearl']['box'][1],.63)
        for point in [[1.,.2],[float('nan'),.2]]:
            with self.assertRaises(ValueError):pixels({'box':[0,0,1,1],'positive':[point],'negative':[[.1,.1]]},(100,200))
    def test_wrong_target_and_environment_rejected(self):
        from .segmentation import check_mask
        prompt={'box':[.1,.1,.8,.6],'positive':[[.3,.3]],'negative':[[.5,.8]]}
        good=np.zeros((200,100));good[20:120,10:80]=1
        self.assertTrue(check_mask(good,prompt,(100,200),'person')['passed'])
        wrong=np.zeros_like(good);wrong[140:190,10:80]=1
        self.assertFalse(check_mask(wrong,prompt,(100,200),'person')['passed'])
        self.assertFalse(check_mask(np.ones_like(good),prompt,(100,200),'pearl')['passed'])
        self.assertFalse(check_mask(good.T,prompt,(100,200),'person')['passed'])
    def test_matte_blocked_before_torch_without_review(self):
        from .models import matte
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError):matte(d,DEFAULT)
    def test_old_box_only_config_rejected(self):
        from .cli import check_config
        c=dict(DEFAULT);c.pop('sam_prompts')
        with self.assertRaisesRegex(ValueError,'migrate'):check_config(c)



class PreflightDiagnosticsTests(unittest.TestCase):
    def test_full_subprocess_output_and_nonzero_exit(self):
        import contextlib,subprocess,sys,os
        from .process import run_logged
        with tempfile.TemporaryDirectory() as d:
            out=io.StringIO();err=io.StringIO()
            with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    run_logged([sys.executable,'-c',"import sys; print('stdout-evidence'); print('stderr-evidence',file=sys.stderr); sys.exit(7)"],cwd=d,env=os.environ.copy(),log_dir=d)
            self.assertEqual(caught.exception.returncode,7)
            self.assertIn('COMMAND:',out.getvalue());self.assertIn('EXIT CODE: 7',out.getvalue())
            self.assertIn('stdout-evidence',out.getvalue());self.assertIn('stderr-evidence',err.getvalue())
            self.assertEqual(next(Path(d).glob('*.stderr.txt')).read_text(),'stderr-evidence\n')
    def test_preflight_does_not_parse_invalid_scene_config(self):
        import subprocess,sys
        with tempfile.TemporaryDirectory() as d:
            Path(d,'config.json').write_text('{invalid obsolete scene config')
            r=subprocess.run([sys.executable,'-m','tools.layered_v4.cli','preflight','--basic','--work',d],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)
            report=json.loads(Path(d,'preflight.json').read_text())
            self.assertTrue(report['basic_only']);self.assertFalse(report['gpu_probe_passed'])
    def test_wrong_python_failure_overwrites_previous_success(self):
        from unittest.mock import patch
        from .models import preflight
        with tempfile.TemporaryDirectory() as d:
            Path(d,'preflight.json').write_text('{"passed":true}')
            with patch('sys.version_info',(3,11)):
                with self.assertRaisesRegex(RuntimeError,'Python 3.12'):preflight(d)
            r=json.loads(Path(d,'preflight.json').read_text())
            self.assertFalse(r['passed']);self.assertEqual(r['stage'],'python');self.assertIn('Traceback',r['traceback'])
    def test_cache_failure_saved(self):
        from unittest.mock import patch
        from .models import preflight
        with tempfile.TemporaryDirectory() as d:
            cache=Path(d,'not-directory');cache.write_text('x')
            with patch.dict('os.environ',{'HF_HOME':str(cache)}):
                with self.assertRaises(OSError):preflight(d)
            r=json.loads(Path(d,'preflight.json').read_text())
            self.assertEqual(r['stage'],'cache');self.assertFalse(r['cache_writable']);self.assertFalse(r['passed'])

    def test_resource_failures_are_not_success(self):
        from unittest.mock import patch
        from types import SimpleNamespace as NS
        from contextlib import ExitStack
        from .models import preflight
        for stage in ['cuda','vram','disk','pip-check']:
            with self.subTest(stage=stage),tempfile.TemporaryDirectory() as d,ExitStack() as stack:
                cuda=NS(is_available=lambda:stage!='cuda',get_device_properties=lambda n:NS(name='FAKE-CPU-TEST'),
                        mem_get_info=lambda:((9 if stage=='vram' else 12)*2**30,15*2**30))
                modules={n:NS() for n in ['addict','torchvision','transformers','diffusers','accelerate','safetensors','huggingface_hub','scipy']}
                modules['torch']=NS(cuda=cuda,version=NS(cuda='FAKE'))
                stack.enter_context(patch.dict('sys.modules',modules))
                stack.enter_context(patch('importlib.metadata.version',return_value='mock-only'))
                stack.enter_context(patch('shutil.which',return_value=None))
                stack.enter_context(patch('shutil.disk_usage',return_value=NS(free=(19 if stage=='disk' else 40)*2**30)))
                stack.enter_context(patch('subprocess.run',return_value=NS(returncode=1 if stage=='pip-check' else 0,stdout='mock-conflict' if stage=='pip-check' else '',stderr='')))
                with self.assertRaises(RuntimeError):preflight(d)
                r=json.loads(Path(d,'preflight.json').read_text())
                self.assertFalse(r['passed']);self.assertFalse(r['gpu_probe_passed']);self.assertEqual(r['stage'],stage)
                if stage=='vram':self.assertIn('9.000',r['error'])

if __name__=='__main__':unittest.main()

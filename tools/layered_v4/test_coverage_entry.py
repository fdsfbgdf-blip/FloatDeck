"""CPU integration tests of reset-session recovery. GPU/install calls are explicit mocks."""
import io,json,os,sys,tempfile,unittest,zipfile,subprocess
from pathlib import Path
from types import SimpleNamespace as NS
from contextlib import redirect_stdout
from unittest.mock import patch,Mock
from PIL import Image
import numpy as np
from .coverage_entry import run_coverage,ensure_environment,restore_assets,obtain_review
from .diagnostics import DiagnosticSession
ROOT=Path(__file__).resolve().parents[2]

class CoverageRecoveryTests(unittest.TestCase):
    def package(self):
        b=io.BytesIO();a=np.zeros((160,96),np.uint8);a[115:145,20:75]=255
        with zipfile.ZipFile(b,'w') as z:
            for name,im in [('source.png',Image.new('RGB',(96,160),(20,40,60))),('source_depth.png',Image.new('L',(96,160),90)),('pearl_candidate_2.png',Image.fromarray(a))]:
                raw=io.BytesIO();im.save(raw,format='PNG');z.writestr(name,raw.getvalue())
            z.writestr('models.lock.json',json.dumps({'segment':{'id':'facebook/sam-vit-base','revision':'a'*40}}))
            # These stale reports/approvals must NEVER be imported as current facts.
            z.writestr('preflight.json',json.dumps({'passed':True,'stale_from_zip':True}))
            z.writestr('segmentation-review.json',json.dumps({'human_approved':True}))
        return b.getvalue()
    def ready(self,s,task_label=None):
        project=s.base/'project-001';project.mkdir(parents=True,exist_ok=True)
        py=s.base/'python312/bin/python';py.parent.mkdir(parents=True,exist_ok=True);py.touch()
        record={'passed':True,'gpu_probe_passed':True,'basic_only':False,'python':str(py),'verification_id':'CPU-MOCK-CURRENT'}
        (project/'preflight.json').write_text(json.dumps(record));s.report.update(passed=True,stage='complete')
        return {'passed':True,'python':str(py),'env':s.env,'work':str(project)}
    def commands(self,log):
        def command(s,args,check=True):
            args=list(map(str,args));log.append(args)
            if 'restore-review' in args:
                # Exercise real CPU ZIP/hash/native validation, not the GPU or installation.
                result=subprocess.run([sys.executable,*args[1:]],cwd=ROOT,capture_output=True,text=True)
                if result.returncode:raise RuntimeError(result.stderr)
                return result
            if 'person-review' in args:
                work=Path(args[args.index('--work')+1]);project=Path(args[args.index('--project')+1])
                assert (work/'assets-validated.json').is_file() and (project/'source.png').is_file()
                assert json.loads((project/'person-rejection.json').read_text())['previous_candidate_5_rejected']
                for i in range(6):Image.new('L',(96,160)).save(work/f'person_candidate_{i}.png')
                (work/'person-checks.json').write_text(json.dumps({'complete':True,'passed':False,'human_approved':False,'candidates':[{}]*6}))
            return NS(returncode=0,stdout='',stderr='')
        return command
    def run_simulated(self,base,upload=None):
        log=[]
        with patch('tools.layered_v4.coverage_entry.prepare',side_effect=self.ready) as prep,patch.object(DiagnosticSession,'command',new=self.commands(log)),redirect_stdout(io.StringIO()):
            r=run_coverage(base,ROOT,upload_once=upload)
        return r,log,prep.call_count
    def test_fresh_colab_empty_project_upload_once_then_sam(self):
        with tempfile.TemporaryDirectory() as d:
            upload=Mock(return_value={'old-review.zip':self.package()})
            r,log,calls=self.run_simulated(d,upload)
            self.assertEqual(calls,1);upload.assert_called_once();self.assertTrue(r['candidate_generation_complete'])
            self.assertFalse(r['passed']);self.assertTrue((Path(d)/'project-001/source_depth.png').is_file())
            self.assertIn('restore-review',log[0]);self.assertIn('person-review',log[1])
            with zipfile.ZipFile(r['zip']) as z:
                self.assertIn('00_请先阅读_运行结果.txt',z.namelist());report=json.loads(z.read('person-session-report.json'))
                self.assertEqual(report['current_preflight']['verification_id'],'CPU-MOCK-CURRENT')
                self.assertFalse(report['human_approved']);self.assertTrue(report['person5_rejected'])
    def test_existing_archive_no_upload_even_when_project_and_record_missing(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d,'FloatDeck-SAM-review.zip').write_bytes(self.package());upload=Mock()
            r,log,calls=self.run_simulated(d,upload)
            upload.assert_not_called();self.assertEqual(calls,1);self.assertTrue(r['candidate_generation_complete'])
    def test_old_passing_record_is_quarantined_not_trusted(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);project=p/'project-001';project.mkdir();(project/'preflight.json').write_text('{"passed":true,"old":true}')
            s=DiagnosticSession(p,ROOT)
            def current(s,task_label=None):
                self.assertFalse((project/'preflight.json').exists());return self.ready(s,task_label)
            with patch('tools.layered_v4.coverage_entry.prepare',side_effect=current) as prep:_,record=ensure_environment(s)
            prep.assert_called_once();self.assertNotIn('old',record);self.assertTrue(list(project.glob('preflight.previous-*.json')))
    def test_cuda_failure_stops_before_upload_and_sam_with_chinese_zip(self):
        with tempfile.TemporaryDirectory() as d:
            upload=Mock()
            def fail(s,task_label=None):s.report.update(stage='cuda',message='CUDA不可用，请选择T4');return {'passed':False}
            with patch('tools.layered_v4.coverage_entry.prepare',side_effect=fail),patch.object(DiagnosticSession,'command') as commands,redirect_stdout(io.StringIO()):r=run_coverage(d,ROOT,upload_once=upload)
            upload.assert_not_called();commands.assert_not_called();self.assertFalse(r['candidate_generation_complete'])
            with zipfile.ZipFile(r['zip']) as z:
                text=z.read('00_请先阅读_运行结果.txt').decode();self.assertIn('未完成分割',text)
                report=json.loads(z.read('person-session-report.json'));self.assertFalse(report['models_started']);self.assertFalse(report['passed'])
    def test_invalid_upload_is_requested_only_once_no_sam(self):
        with tempfile.TemporaryDirectory() as d:
            upload=Mock(return_value={'not-a-review.zip':b'bad'})
            r,log,_=self.run_simulated(d,upload)
            upload.assert_called_once();self.assertFalse(r['candidate_generation_complete']);self.assertEqual(log,[])
    def test_cancelled_upload_is_not_reopened(self):
        with tempfile.TemporaryDirectory() as d:
            upload=Mock(return_value={})
            r,log,_=self.run_simulated(d,upload)
            upload.assert_called_once();self.assertFalse(r['candidate_generation_complete']);self.assertFalse(log)
    def test_restore_never_copies_stale_preflight_or_person_approval(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);project=p/'project';project.mkdir();(project/'preflight.json').write_text('{"current":true}')
            archive=p/'input.zip';archive.write_bytes(self.package());r=restore_assets(archive,p/'work',project)
            self.assertTrue(r['validated']);self.assertTrue(r['person5_rejected'])
            self.assertEqual(json.loads((project/'preflight.json').read_text()),{'current':True})
            self.assertFalse((project/'segmentation-review.json').exists());self.assertFalse((project/'person_mask.png').exists())
            self.assertEqual((project/'pearl_mask.png').read_bytes(),(p/'work/pearl_candidate_2.png').read_bytes())
    def test_broken_dedicated_venv_is_quarantined_once_then_recreated(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);venv=p/'python312';venv.mkdir();(venv/'pyvenv.cfg').write_text('old')
            s=DiagnosticSession(p,ROOT);attempts=[]
            def repair(s,task_label=None):
                attempts.append(1)
                if len(attempts)==1:s.report.update(stage='python',message='wrong interpreter');return {'passed':False}
                self.assertFalse(venv.exists());return self.ready(s,task_label)
            with patch('tools.layered_v4.coverage_entry.prepare',side_effect=repair):r,record=ensure_environment(s)
            self.assertTrue(r['passed']);self.assertEqual(len(attempts),2);self.assertTrue(list(p.glob('python312.quarantined-*')))
    def test_missing_preflight_is_actionable_runtime_error_not_file_trace(self):
        from .sam_export import require_environment
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(RuntimeError,'自动重新验证'):require_environment(d,'missing-python')

    def test_partial_reset_restores_missing_locked_pearl_without_changing_identity(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);project=p/'project';archive=p/'input.zip';archive.write_bytes(self.package())
            first=restore_assets(archive,p/'first',project)
            lock=(project/'fixed-pearl.json').read_bytes();(project/'pearl_mask.png').unlink();(project/'source_depth.png').unlink()
            second=restore_assets(archive,p/'second',project)
            self.assertEqual(first['pearl_sha256'],second['pearl_sha256']);self.assertEqual(lock,(project/'fixed-pearl.json').read_bytes())
            self.assertEqual((project/'pearl_mask.png').read_bytes(),(p/'second/pearl_candidate_2.png').read_bytes())
            self.assertTrue((project/'source_depth.png').is_file())
    def test_corrupt_report_is_revalidated_not_parsed_as_a_prerequisite(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);project=p/'project-001';project.mkdir();(project/'preflight.json').write_text('{broken')
            Path(d,'FloatDeck-SAM-review.zip').write_bytes(self.package())
            r,log,calls=self.run_simulated(d,Mock())
            self.assertEqual(calls,1);self.assertTrue(r['candidate_generation_complete'])
            self.assertTrue(list(project.glob('preflight.previous-*.json')))
    def test_real_prepare_control_flow_bootstraps_fresh_python_and_installs_only_target(self):
        from .diagnostics import expected_versions
        with tempfile.TemporaryDirectory() as d,redirect_stdout(io.StringIO()):
            p=Path(d);s=DiagnosticSession(p,ROOT);py=p/'python312/bin/python';calls=[];installed=False
            pins=expected_versions(ROOT)
            def command(args,check=True):
                nonlocal installed
                args=list(map(str,args));calls.append(args)
                if 'venv' in args:
                    py.parent.mkdir(parents=True);py.touch();(py.parent.parent/'pyvenv.cfg').write_text('simulated dedicated venv')
                if 'install' in args:installed=True
                if '-c' in args:
                    versions=pins if installed else {key:None for key in pins}
                    return NS(stdout=json.dumps({'python':str(py),'version':[3,12],'packages':versions}),returncode=0)
                if 'preflight' in args:
                    (p/'project-001/preflight.json').write_text(json.dumps({'passed':True,'gpu_probe_passed':True,'basic_only':False,'python':str(py),'simulation':True,'stage':'complete'}))
                return NS(stdout='',stderr='',returncode=0,args=args)
            with patch.object(s,'command',side_effect=command),patch('shutil.disk_usage',return_value=NS(free=62*2**30)),patch('shutil.which',return_value=None):r,record=ensure_environment(s)
            self.assertTrue(r['passed']);self.assertTrue(record['simulation'])
            self.assertTrue(any('venv' in cmd for cmd in calls))
            for cmd in calls:
                if 'pip' in cmd:self.assertEqual(cmd[0],str(py))
            self.assertTrue(any('preflight' in cmd for cmd in calls))
            self.assertFalse(any(any(x in cmd for x in ['segment','person-review','matte','lock-models']) for cmd in calls))

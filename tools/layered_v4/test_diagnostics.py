"""CPU-only diagnostic orchestration/export tests; no CUDA, installs or model downloads."""
import io,json,os,sys,tempfile,unittest,zipfile
from pathlib import Path
from contextlib import redirect_stdout
from unittest.mock import patch
from types import SimpleNamespace as NS
from .diagnostics import DiagnosticSession,prepare,redact,expected_versions,install_plan,classify

ROOT=Path(__file__).resolve().parents[2]
class DiagnosticTests(unittest.TestCase):
    def test_exact_versions_skip_installs(self):
        p=expected_versions(ROOT);self.assertEqual(install_plan(p,p),(False,[]))
        wrong=dict(p,torch='2.6.0+cpu');self.assertTrue(install_plan(p,wrong)[0])
        wrong=dict(p,addict=None);self.assertEqual(install_plan(p,wrong),(False,['addict==2.4.0']))
    def test_export_is_allowlisted_and_secret_free(self):
        with tempfile.TemporaryDirectory() as d,redirect_stdout(io.StringIO()):
            Path(d,'private.png').write_bytes(b'private')
            s=DiagnosticSession(d,ROOT,{'HF_TOKEN':'private-value-123'})
            s.report['commands']=[{'argv':['pip'],'returncode':1,'stdout':'https://name:pw@example.com/?token=abc123 Authorization: Bearer abcdef','stderr':'private-value-123 hf_abcdef ghp_abcdef'}]
            s.report['message']='测试';p=s.finish()
            with zipfile.ZipFile(p) as z:
                text='\n'.join(z.read(n).decode() for n in z.namelist())
                for secret in ['private-value-123','name:pw','abc123','hf_abcdef','ghp_abcdef','abcdef']:self.assertNotIn(secret,text)
                self.assertNotIn('private.png',z.namelist());json.loads(z.read('report.json'))
    def test_chinese_advice_preserves_failure(self):
        for stage in ['cuda','vram','disk','pip-check','python','repository']:
            self.assertTrue(any('\u4e00'<=c<='\u9fff' for c in classify(stage,'error')))
    def test_command_failure_records_all_streams(self):
        with tempfile.TemporaryDirectory() as d:
            s=DiagnosticSession(d,ROOT)
            with self.assertRaises(Exception):s.command([sys.executable,'-c',"import sys;print('out');print('err',file=sys.stderr);sys.exit(8)"])
            self.assertEqual(s.report['commands'][0]['returncode'],8)
            self.assertEqual(s.report['commands'][0]['stderr'],'err\n')
    def test_mock_pipeline_reuses_environment_no_models(self):
        # Simulation validates state machine, NOT successful GPU inference.
        with tempfile.TemporaryDirectory() as d,redirect_stdout(io.StringIO()):
            base=Path(d);py=base/'python312/bin/python';py.parent.mkdir(parents=True);py.touch()
            s=DiagnosticSession(base,ROOT);pins=expected_versions(ROOT);calls=[]
            def command(argv,check=True):
                argv=list(map(str,argv));calls.append(argv)
                if '-c' in argv:return NS(stdout=json.dumps({'python':str(py),'version':[3,12],'packages':pins}),returncode=0)
                if 'preflight' in argv:
                    w=Path(argv[-1]);(w/'preflight.json').write_text(json.dumps({'passed':True,'gpu_probe_passed':True,'basic_only':False,'stage':'complete'}))
                return NS(stdout='',stderr='',returncode=0,args=argv)
            with patch.object(s,'command',side_effect=command),patch('shutil.disk_usage',return_value=NS(free=40*2**30)):
                r=prepare(s)
            self.assertTrue(r['passed']);self.assertTrue(Path(r['zip']).exists())
            self.assertFalse(any('install' in cmd for cmd in calls))
            self.assertFalse(any(any(x in cmd for x in ['segment','matte','background','lock-models']) for cmd in calls))
    def test_disk_failure_still_creates_zip(self):
        with tempfile.TemporaryDirectory() as d,redirect_stdout(io.StringIO()):
            s=DiagnosticSession(d,ROOT)
            with patch('shutil.disk_usage',return_value=NS(free=2*2**30)),patch('shutil.which',return_value=None):r=prepare(s)
            self.assertFalse(r['passed']);self.assertEqual(s.report['stage'],'disk')
            with zipfile.ZipFile(r['zip']) as z:self.assertFalse(json.loads(z.read('report.json'))['passed'])

    def test_install_failure_packaged_without_continuing(self):
        import subprocess
        with tempfile.TemporaryDirectory() as d,redirect_stdout(io.StringIO()):
            base=Path(d);py=base/'python312/bin/python';py.parent.mkdir(parents=True);py.touch()
            s=DiagnosticSession(base,ROOT);pins=expected_versions(ROOT);pins['addict']=None;calls=[]
            def command(argv,check=True):
                args=list(map(str,argv));calls.append(args)
                if '-c' in args:return NS(stdout=json.dumps({'python':str(py),'version':[3,12],'packages':pins}),returncode=0)
                if 'install' in args:raise subprocess.CalledProcessError(1,args,'install-output','specific-install-error')
                return NS(stdout='',stderr='',returncode=0)
            with patch.object(s,'command',side_effect=command),patch('shutil.disk_usage',return_value=NS(free=40*2**30)),patch('shutil.which',return_value=None):r=prepare(s)
            self.assertFalse(r['passed']);self.assertEqual(s.report['stage'],'install')
            self.assertFalse(any('preflight' in cmd for cmd in calls));self.assertTrue(Path(r['zip']).exists())
